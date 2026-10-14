using System.Net.WebSockets;
using System.Text.Json;


// Configure the running server and shared key.
var endpoint = args.ElementAtOrDefault(0) ?? "http://localhost:5080";
var key = Environment.GetEnvironmentVariable("Olvii__AccessKey") ?? throw new Exception("Set Olvii__AccessKey");
using var http = new HttpClient
{
    BaseAddress = new Uri(endpoint)
};

// Check unauthenticated access and public client delivery.
Check((await http.GetAsync("/api/devices")).StatusCode == System.Net.HttpStatusCode.Unauthorized, "Authentication required");
Check((await http.GetStringAsync("/")).Contains("RTCPeerConnection"), "Web client served");
Check((await http.PostAsync("/api/tickets", null)).StatusCode == System.Net.HttpStatusCode.Unauthorized, "Ticket requires key");

// Exchange valid credentials for a browser ticket.
using var ticketRequest = new HttpRequestMessage(HttpMethod.Post, "/api/tickets");
ticketRequest.Headers.Authorization = new("Bearer", key);
using var ticketResponse = await http.SendAsync(ticketRequest);
ticketResponse.EnsureSuccessStatusCode();

// Verify that tickets cannot be cached.
Check(ticketResponse.Headers.CacheControl?.NoStore == true, "Ticket not cached");
using var ticketJson = JsonDocument.Parse(await ticketResponse.Content.ReadAsStringAsync());
var ticket = ticketJson.RootElement.GetProperty("ticket").GetString();

// Build the browser upgrade URL using the one-use ticket.
var browserUri = new UriBuilder(endpoint)
{
    Scheme = endpoint.StartsWith("https") ? "wss" : "ws",
    Path = "/api/connect",
    Query = $"id={Guid.NewGuid()}&name=Browser&ticket={ticket}"
}.Uri;

// Reject a cross-origin upgrade without consuming the ticket.
using (var foreign = new ClientWebSocket())
{
    foreign.Options.SetRequestHeader("Origin", "https://foreign.example");
    var denied = false;
    try
    {
        await foreign.ConnectAsync(browserUri, CancellationToken.None);
    }
    catch (WebSocketException)
    {
        denied = true;
    }

    Check(denied, "Foreign origin rejected");
}


// Accept the same ticket from the server origin.
using (var browser = new ClientWebSocket())
{
    browser.Options.SetRequestHeader("Origin", endpoint.TrimEnd('/'));
    await browser.ConnectAsync(browserUri, CancellationToken.None);
    await Read(browser, "welcome");
    Check(true, "Browser ticket accepted");

    // Reject reuse even while the first socket is alive.
    using var replay = new ClientWebSocket();
    replay.Options.SetRequestHeader("Origin", endpoint.TrimEnd('/'));
    var denied = false;
    try
    {
        await replay.ConnectAsync(browserUri, CancellationToken.None);
    }
    catch (WebSocketException)
    {
        denied = true;
    }

    Check(denied, "Ticket replay rejected");
}


// Create independent caller, recipient and unrelated device.
var aid = Guid.NewGuid().ToString();
var bid = Guid.NewGuid().ToString();
using var a = await Connect(aid);
using var b = await Connect(bid);
using var c = await Connect(Guid.NewGuid().ToString());

// Start a call and wait for both ringing events.
await Send(a, new { type = "call", target = bid });
var first = (await Read(a, "ringing")).GetProperty("callId").GetString()!;
await Read(b, "incoming");

// Prevent negotiation before explicit acceptance.
await Send(a, new { type = "offer", callId = first, sdp = "premature" });
Check((await Read(a, "error")).GetProperty("message").GetString() == "Invalid call state", "No media negotiation before consent");

// Protect a busy recipient from a second caller.
await Send(c, new { type = "call", target = bid });
Check((await Read(c, "error")).GetProperty("message").GetString() == "Device busy", "Busy callee protected");

// Reject commands sent by a nonparticipant.
await Send(c, new { type = "hangup", callId = first });
await Read(c, "error");

// Notify both endpoints when the recipient rejects.
await Send(b, new { type = "reject", callId = first });
await Read(a, "ended");
await Read(b, "ended");
await Send(a, new { type = "call", target = bid });

// Accept a fresh call and assign caller/recipient negotiation roles.
var second = (await Read(a, "ringing")).GetProperty("callId").GetString()!;
await Read(b, "incoming");
await Send(b, new { type = "accept", callId = second });
Check((await Read(a, "accepted")).GetProperty("initiator").GetBoolean(), "Caller offers");
Check(!(await Read(b, "accepted")).GetProperty("initiator").GetBoolean(), "Callee answers");

// Relay the caller offer to the recipient.
await Send(a, new { type = "offer", callId = second, sdp = "test-offer" });
Check((await Read(b, "offer")).GetProperty("sdp").GetString() == "test-offer", "Offer forwarded");

// Relay the recipient answer back to the caller.
await Send(b, new { type = "answer", callId = second, sdp = "test-answer" });
await Read(a, "answer");

// Relay a candidate without rewriting its payload.
await Send(a, new { type = "ice", callId = second, candidate = new { sdpMid = "0", sdpMLineIndex = 0, candidate = "test-candidate" } });
await Read(b, "ice");

// Release both endpoints after hangup.
await Send(a, new { type = "hangup", callId = second });
await Read(a, "ended");
await Read(b, "ended");
await Send(a, new { type = "call", target = bid });
await Read(a, "ringing");
await Read(b, "incoming");

// End ringing when a participant disconnects.
await b.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "bye", CancellationToken.None);
Check((await Read(a, "ended")).GetProperty("reason").GetString() == "disconnected", "Disconnect ends call");
await Send(a, new { type = "call", target = bid });
await Read(a, "error");

// Report success only after all protocol checks complete.
Console.WriteLine("PASS: authentication, explicit consent, busy, ownership, rejection, SDP/ICE, hangup, disconnect, offline target.");

// Create and authenticate a real WebSocket test client.
async Task<ClientWebSocket> Connect(string id)
{
    // Configure native-client authentication.
    var socket = new ClientWebSocket();
    socket.Options.SetRequestHeader("Authorization", $"Bearer {key}");


    // Supply a distinct test identity through the upgrade URL.
    var uri = new UriBuilder(endpoint)
    {
        Scheme = endpoint.StartsWith("https") ? "wss" : "ws",
        Path = "/api/connect",
        Query = $"id={id}&name=Smoke"
    };

    // Registration must be confirmed before this client starts a call.
    await socket.ConnectAsync(uri.Uri, CancellationToken.None);
    await Read(socket, "welcome");
    return socket;
}


// Serialize commands using the same JSON format as application clients.
static async Task Send(ClientWebSocket socket, object message)
{
    var bytes = JsonSerializer.SerializeToUtf8Bytes(message);
    await socket.SendAsync(bytes, WebSocketMessageType.Text, true, CancellationToken.None);
}

// Read one expected event while allowing presence snapshots.
static async Task<JsonElement> Read(ClientWebSocket socket, string type)
{
    using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
    var buffer = new byte[65536];
    while (true)
    {
        // Collect fragmented WebSocket messages before parsing.
        using var stream = new MemoryStream();
        WebSocketReceiveResult result;
        do
        {
            result = await socket.ReceiveAsync(buffer, timeout.Token);
            if (result.MessageType == WebSocketMessageType.Close)
                throw new Exception("Unexpected close");
            stream.Write(buffer, 0, result.Count);
        }
        while (!result.EndOfMessage);

        // Detach the expected payload from its temporary JSON document.
        using var json = JsonDocument.Parse(stream.ToArray());
        if (json.RootElement.GetProperty("type").GetString() == type)
            return json.RootElement.Clone();
        if (json.RootElement.GetProperty("type").GetString() != "devices")
            throw new Exception($"Expected {type}, got {json.RootElement}");
    }
}


// Fail fast with the name of the violated invariant.
static void Check(bool value, string description)
{
    if (!value)
        throw new Exception(description);
    Console.WriteLine($"PASS: {description}");
}
