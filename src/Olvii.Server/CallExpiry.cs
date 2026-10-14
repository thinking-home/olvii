namespace Olvii.Server;

/// <summary>Periodically expires calls that have not completed negotiation.</summary>
/// <param name="registry">Registry owning call deadlines.</param>
public sealed class CallExpiry(CallRegistry registry) : BackgroundService
{
    /// <summary>Checks deadlines once per second until shutdown.</summary>
    /// <param name="stoppingToken">Host shutdown token.</param>
    /// <returns>The task representing the expiration loop.</returns>
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // Avoid overlapping expiration passes.
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(1));
        while (await timer.WaitForNextTickAsync(stoppingToken))
            registry.Expire();
    }
}
