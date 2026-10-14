using System.Net.WebSockets;
using System.Text.Json;

namespace Olvii.Server;

/// <summary>Owns registration, message reception and cleanup for a signaling socket.</summary>
internal static class SocketEndpoint
{
    /// <summary>Gets heartbeat settings used to detect lost signaling connections.</summary>
    public static WebSocketOptions Options => new()
    {
        KeepAliveInterval = TimeSpan.FromSeconds(15),
        KeepAliveTimeout = TimeSpan.FromSeconds(15)
    };

    /// <summary>Upgrades a validated request and runs the device connection.</summary>
    /// <param name="context">Authenticated upgrade request containing id and name.</param>
    /// <param name="registry">Shared presence and call registry.</param>
    public static async Task Connect(HttpContext context, CallRegistry registry)
    {
        // Validate device metadata before accepting the socket.
        var id = context.Request.Query["id"].ToString();
        var name = context.Request.Query["name"].ToString();
        if (!IsValidRequest(context, id, name))
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            return;
        }

        // Reject duplicate identities without replacing an existing connection.
        using var socket = await context.WebSockets.AcceptWebSocketAsync();
        var peer = new DevicePeer(id, name, socket);
        if (!registry.Join(peer))
        {
            await RejectDuplicate(socket);
            return;
        }

        // Both socket loops share the request's cancellation lifetime.
        using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(context.RequestAborted);
        var sender = peer.SendLoop(lifetime.Token);
        try
        {
            await Receive(socket, peer, registry, lifetime.Token);
        }
        catch (Exception error) when (IsDisconnect(error))
        {
            // Socket loss and request cancellation are normal disconnect paths.
        }
        finally
        {
            // Remove presence before stopping the remaining writer.
            registry.Leave(peer);
            lifetime.Cancel();
            await FinishSender(sender);
        }
    }

    private static bool IsValidRequest(HttpContext context, string id, string name) =>
        Guid.TryParse(id, out _)
        && !string.IsNullOrWhiteSpace(name)
        && name.Length <= 64
        && context.WebSockets.IsWebSocketRequest;

    private static Task RejectDuplicate(WebSocket socket) =>
        socket.CloseAsync(WebSocketCloseStatus.PolicyViolation,
            "Device already connected", CancellationToken.None);

    private static bool IsDisconnect(Exception error) =>
        error is WebSocketException or OperationCanceledException;

    private static async Task FinishSender(Task sender)
    {
        try
        {
            // Observe the writer's completion to avoid unobserved exceptions.
            await sender;
        }
        catch (Exception error) when (IsDisconnect(error))
        {
            // Expected after the receiver cancels or aborts the socket.
        }
    }

    private static async Task Receive(
        WebSocket socket, DevicePeer peer, CallRegistry registry, CancellationToken token)
    {
        // Reuse the receive buffer across messages.
        var buffer = new byte[8192];
        while (socket.State == WebSocketState.Open)
        {
            // Reassemble fragments before parsing a complete command.
            var bytes = await ReadMessage(socket, buffer, token);
            if (bytes is null)
                return;

            // Malformed commands do not tear down a healthy connection.
            Dispatch(peer, registry, bytes);
        }
    }

    private static async Task<byte[]?> ReadMessage(
        WebSocket socket, byte[] buffer, CancellationToken token)
    {
        // Each JSON command may span multiple WebSocket frames.
        using var message = new MemoryStream();
        WebSocketReceiveResult part;
        do
        {
            // A close frame ends reception without parsing a payload.
            part = await socket.ReceiveAsync(buffer, token);
            if (part.MessageType == WebSocketMessageType.Close)
                return null;

            // Bound allocations and reject binary messages.
            if (part.MessageType != WebSocketMessageType.Text || message.Length + part.Count > 65536)
            {
                await socket.CloseOutputAsync(WebSocketCloseStatus.InvalidPayloadData, "Invalid message", token);
                return null;
            }

            // Append only the bytes returned by this receive operation.
            message.Write(buffer, 0, part.Count);
        } while (!part.EndOfMessage);

        // Parsing happens outside the frame assembly loop.
        return message.ToArray();
    }

    private static void Dispatch(DevicePeer peer, CallRegistry registry, byte[] bytes)
    {
        try
        {
            // The registry clones any payload that must outlive this document.
            using var json = JsonDocument.Parse(bytes);
            registry.Handle(peer, json.RootElement);
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or KeyNotFoundException)
        {
            // Return a protocol error without exposing parser details.
            peer.Post(new
            {
                type = "error",
                message = "Invalid command"
            });
        }
    }
}
