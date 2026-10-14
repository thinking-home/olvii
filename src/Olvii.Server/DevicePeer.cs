using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;

namespace Olvii.Server;

/// <summary>Queues events for one device and serializes WebSocket writes.</summary>
/// <param name="id">Registered device UUID.</param>
/// <param name="name">Display name visible to other devices.</param>
/// <param name="socket">Socket owned by the connection endpoint.</param>
public sealed class DevicePeer(string id, string name, WebSocket socket)
{
    private readonly Channel<string> outgoing = Channel.CreateBounded<string>(128);

    /// <summary>Gets the registered device ID.</summary>
    public string Id { get; } = id;

    /// <summary>Gets the display name.</summary>
    public string Name { get; } = name;

    /// <summary>Queues an event or aborts the connection if its queue is full.</summary>
    /// <param name="message">Object in the signaling wire format.</param>
    public void Post(object message)
    {
        // A slow device must not block the shared registry.
        var json = JsonSerializer.Serialize(message);
        if (!outgoing.Writer.TryWrite(json))
            socket.Abort();
    }

    /// <summary>Sends queued events until cancellation or socket failure.</summary>
    /// <param name="token">Connection lifetime token.</param>
    /// <returns>The task representing the socket's only writer.</returns>
    public async Task SendLoop(CancellationToken token)
    {
        try
        {
            // WebSocket permits only one concurrent sender.
            await foreach (var message in outgoing.Reader.ReadAllAsync(token))
                await socket.SendAsync(Encoding.UTF8.GetBytes(message), WebSocketMessageType.Text, true, token);
        }
        catch
        {
            // Wake the receiver when events can no longer be delivered.
            socket.Abort();
            throw;
        }
    }
}
