using Olvii.Server;

// Require the shared key before starting any listeners.
var builder = WebApplication.CreateBuilder(args);
var key = builder.Configuration["Olvii:AccessKey"];
if (string.IsNullOrWhiteSpace(key))
    throw new InvalidOperationException("Set Olvii__AccessKey to a non-empty shared key.");

// Share presence and deadlines across all connection handlers.
builder.Services.AddSingleton<CallRegistry>();
builder.Services.AddHostedService<CallExpiry>();
var app = builder.Build();
var access = new ApiAccess(key);

// Serve the web client before applying API authentication.
app.UseDefaultFiles();
app.UseStaticFiles();
app.UseWebSockets(SocketEndpoint.Options);
app.MapGet("/health", () => Results.Ok(new { status = "ok" }));

// Preserve the same routes and wire contracts for both clients.
app.Use(access.Authorize);
app.MapPost("/api/tickets", access.CreateTicket);
app.MapGet("/api/devices", (CallRegistry registry) => registry.Devices());
app.Map("/api/connect", SocketEndpoint.Connect);

// Run until the host receives a shutdown signal.
app.Run();
