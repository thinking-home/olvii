using System.Text.Json;

namespace Olvii.Server;

/// <summary>Serializes in-memory device presence and call transitions under one lock.</summary>
public sealed class CallRegistry
{
    private readonly object gate = new();
    private readonly Dictionary<string, DevicePeer> peers = [];
    private readonly Dictionary<string, Call> calls = [];

    /// <summary>Returns a snapshot of connected devices and their busy flags.</summary>
    /// <returns>Descriptions using the wire fields id, name and busy.</returns>
    public object[] Devices()
    {
        // Keep presence and busy flags consistent within the snapshot.
        lock (gate)
            return peers.Values.Select(DescribeDevice).ToArray();
    }

    /// <summary>Registers a device and broadcasts its presence.</summary>
    /// <param name="peer">Connection to register.</param>
    /// <returns>False if this device ID is already connected.</returns>
    public bool Join(DevicePeer peer)
    {
        lock (gate)
        {
            // Preserve the existing connection when an ID is reused.
            if (!peers.TryAdd(peer.Id, peer))
                return false;

            // Confirm registration before advertising the new device.
            peer.Post(new
            {
                type = "welcome",
                id = peer.Id
            });
            Broadcast();
            return true;
        }
    }

    /// <summary>Removes a device and ends calls involving it.</summary>
    /// <param name="peer">Previously registered connection.</param>
    public void Leave(DevicePeer peer)
    {
        lock (gate)
        {
            // Ignore duplicate disconnect notifications.
            if (!peers.Remove(peer.Id))
                return;

            // Copy the affected calls before removing them.
            var affected = calls.Values.Where(c => c.Contains(peer.Id)).ToArray();
            foreach (var call in affected)
                End(call, "disconnected");

            // Publish presence even when no call was active.
            Broadcast();
        }
    }

    /// <summary>Ends calls whose ringing or SDP deadline elapsed.</summary>
    public void Expire()
    {
        lock (gate)
        {
            // Completed negotiations no longer use the setup deadline.
            var expired = calls.Values.Where(IsExpired).ToArray();
            foreach (var call in expired)
                End(call, "timeout");
        }
    }

    /// <summary>Validates and applies one signaling command.</summary>
    /// <param name="peer">Authenticated connection sending the command.</param>
    /// <param name="message">JSON command in the API v0 wire format.</param>
    /// <remarks>Invalid call transitions produce an error event for the sender.</remarks>
    /// <exception cref="KeyNotFoundException">A required JSON field is absent.</exception>
    /// <exception cref="InvalidOperationException">A JSON field has the wrong type.</exception>
    public void Handle(DevicePeer peer, JsonElement message)
    {
        lock (gate)
        {
            // New calls reference a target instead of an existing call.
            var type = message.GetProperty("type").GetString();
            if (type == "call")
            {
                Start(peer, message);
                return;
            }

            // Only participants may modify an existing call.
            var callId = message.GetProperty("callId").GetString() ?? "";
            if (!calls.TryGetValue(callId, out var call) || !call.Contains(peer.Id))
            {
                Error(peer, "Call unavailable");
                return;
            }

            // Keep validation and transition inside the same lock.
            Apply(peer, call, type, message);
        }
    }

    private object DescribeDevice(DevicePeer peer) => new
    {
        id = peer.Id,
        name = peer.Name,
        busy = calls.Values.Any(call => call.Contains(peer.Id))
    };

    private static bool IsExpired(Call call) =>
        !call.Answered && call.Deadline <= DateTimeOffset.UtcNow;

    private static void Error(DevicePeer peer, string message) =>
        peer.Post(new
        {
            type = "error",
            message
        });

    private void Broadcast()
    {
        // Share one presence snapshot across all connections.
        var message = new
        {
            type = "devices",
            devices = Devices()
        };
        foreach (var peer in peers.Values)
            peer.Post(message);
    }

    private void Start(DevicePeer peer, JsonElement message)
    {
        // Reject offline targets and calls to oneself.
        var target = message.GetProperty("target").GetString() ?? "";
        if (target == peer.Id || !peers.ContainsKey(target))
        {
            Error(peer, "Device unavailable");
            return;
        }

        // Reserve at most one call per endpoint.
        if (calls.Values.Any(c => c.Contains(peer.Id) || c.Contains(target)))
        {
            Error(peer, "Device busy");
            return;
        }

        // Store the call before notifying its participants.
        var call = new Call(Guid.NewGuid().ToString(), peer.Id, target);
        calls.Add(call.Id, call);
        NotifyRinging(peer, peers[target], call);
        Broadcast();
    }

    private static void NotifyRinging(DevicePeer caller, DevicePeer callee, Call call)
    {
        // Confirm the destination to the caller.
        caller.Post(new
        {
            type = "ringing",
            callId = call.Id,
            peerId = callee.Id
        });

        // Include the caller's display name in the incoming event.
        callee.Post(new
        {
            type = "incoming",
            callId = call.Id,
            peerId = caller.Id,
            name = caller.Name
        });
    }

    private void Apply(DevicePeer peer, Call call, string? type, JsonElement message)
    {
        // Resolve the recipient once for forwarding.
        var otherId = peer.Id == call.Caller ? call.Callee : call.Caller;
        var other = peers[otherId];

        // Match only transitions allowed by the current call state.
        switch (type)
        {
            // Only the recipient can accept.
            case "accept" when peer.Id == call.Callee && !call.Accepted:
                Accept(peer, other, call);
                break;

            // Rejection applies only while ringing.
            case "reject" when peer.Id == call.Callee && !call.Accepted:
                End(call, "rejected");
                break;

            // Either participant can end a call.
            case "hangup":
                End(call, "hangup");
                break;

            // Offer once, then answer once, after acceptance.
            case "offer" when call.Accepted && !call.Offered && peer.Id == call.Caller:
            case "answer" when call.Offered && !call.Answered && peer.Id == call.Callee:
                ForwardDescription(peer, other, call, type, message);
                break;

            // ICE exchange also requires explicit acceptance.
            case "ice" when call.Accepted:
                ForwardCandidate(peer, other, call, message);
                break;

            // Leave call state untouched for invalid commands.
            default:
                Error(peer, "Invalid call state");
                break;
        }
    }

    private static void Accept(DevicePeer callee, DevicePeer caller, Call call)
    {
        // Give SDP negotiation its own setup deadline.
        call.Accepted = true;
        call.Deadline = DateTimeOffset.UtcNow.AddSeconds(30);

        // Prepare the recipient before asking the caller for an offer.
        callee.Post(new
        {
            type = "accepted",
            callId = call.Id,
            initiator = false
        });

        // Ask the caller to produce the first local description.
        caller.Post(new
        {
            type = "accepted",
            callId = call.Id,
            initiator = true
        });
    }

    private static void ForwardDescription(
        DevicePeer sender, DevicePeer recipient, Call call, string type, JsonElement message)
    {
        // Empty SDP must not advance negotiation.
        var sdp = message.GetProperty("sdp").GetString();
        if (string.IsNullOrWhiteSpace(sdp))
        {
            Error(sender, "Missing SDP");
            return;
        }

        // Mark the description before forwarding it.
        if (type == "offer")
            call.Offered = true;
        else
            call.Answered = true;

        // Media details remain opaque to the signaling server.
        recipient.Post(new
        {
            type,
            callId = call.Id,
            sdp
        });
    }

    private static void ForwardCandidate(
        DevicePeer sender, DevicePeer recipient, Call call, JsonElement message)
    {
        // Detach the payload from the incoming JSON document.
        var candidate = message.GetProperty("candidate").Clone();
        if (candidate.ValueKind != JsonValueKind.Object)
        {
            Error(sender, "Invalid ICE candidate");
            return;
        }

        // The receiving WebRTC implementation validates candidate fields.
        recipient.Post(new
        {
            type = "ice",
            callId = call.Id,
            candidate
        });
    }

    private void End(Call call, string reason)
    {
        // Release the endpoints before announcing completion.
        calls.Remove(call.Id);
        var message = new
        {
            type = "ended",
            callId = call.Id,
            reason
        };

        // A disconnected participant may already be absent.
        foreach (var id in new[] { call.Caller, call.Callee })
            if (peers.TryGetValue(id, out var peer))
                peer.Post(message);

        // Advertise the newly available endpoints.
        Broadcast();
    }

    private sealed class Call(string id, string caller, string callee)
    {
        // Fixed identities of the call and its endpoints.
        public string Id = id;
        public string Caller = caller;
        public string Callee = callee;

        // Negotiation progresses until completion or timeout.
        public bool Accepted;
        public bool Offered;
        public bool Answered;
        public DateTimeOffset Deadline = DateTimeOffset.UtcNow.AddSeconds(30);

        public bool Contains(string deviceId) => Caller == deviceId || Callee == deviceId;
    }
}
