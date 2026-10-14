using System.Collections.Concurrent;
using System.Security.Cryptography;

namespace Olvii.Server;

/// <summary>Authenticates API requests and issues one-use browser tickets.</summary>
/// <param name="key">Nonempty shared key configured by the host.</param>
internal sealed class ApiAccess(string key)
{
    private readonly ConcurrentDictionary<string, DateTimeOffset> tickets = new();

    /// <summary>Allows API requests authenticated by a key or a valid browser ticket.</summary>
    /// <param name="context">Current HTTP request.</param>
    /// <param name="next">Next middleware in the pipeline.</param>
    public async Task Authorize(HttpContext context, RequestDelegate next)
    {
        // Native clients send the key in the Authorization header.
        var authorized = context.Request.Headers.Authorization == $"Bearer {key}";
        if (!authorized && IsBrowserSocket(context))
            authorized = ConsumeTicket(context);

        // Only API routes require credentials.
        if (context.Request.Path.StartsWithSegments("/api") && !authorized)
        {
            context.Response.StatusCode = StatusCodes.Status401Unauthorized;
            return;
        }

        // Keep normal routing and endpoint handling unchanged.
        await next(context);
    }

    /// <summary>Exchanges an authenticated request for a 30-second, one-use ticket.</summary>
    /// <param name="context">Request already authenticated by the middleware.</param>
    /// <returns>The ticket, or HTTP 429 if the outstanding-ticket limit is reached.</returns>
    public IResult CreateTicket(HttpContext context)
    {
        // Remove expired entries before checking the outstanding-ticket limit.
        foreach (var entry in tickets.Where(t => t.Value <= DateTimeOffset.UtcNow))
            tickets.TryRemove(entry.Key, out _);
        if (tickets.Count >= 1024)
            return Results.StatusCode(StatusCodes.Status429TooManyRequests);

        // Browser WebSocket cannot supply an Authorization header.
        var ticket = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
        tickets[ticket] = DateTimeOffset.UtcNow.AddSeconds(30);
        context.Response.Headers.CacheControl = "no-store";
        return Results.Ok(new
        {
            ticket
        });
    }

    private static bool IsBrowserSocket(HttpContext context) =>
        context.Request.Path == "/api/connect"
        && context.WebSockets.IsWebSocketRequest
        && context.Request.Headers.Origin == $"{context.Request.Scheme}://{context.Request.Host}";

    private bool ConsumeTicket(HttpContext context)
    {
        // Remove atomically so simultaneous attempts cannot reuse the ticket.
        var ticket = context.Request.Query["ticket"].ToString();
        return tickets.TryRemove(ticket, out var expires) && expires > DateTimeOffset.UtcNow;
    }
}
