namespace MahiRemote.Core.Models;

public record DeviceInfo
{
    public required string DeviceId { get; init; }
    public required string DeviceName { get; init; }
    public required string Fingerprint { get; init; }
    public string AndroidVersion { get; init; } = "14";
    public int BatteryPercent { get; init; } = 100;
    public bool IsCharging { get; init; } = false;
    public string NetworkType { get; init; } = "Wi-Fi";
    public string IpAddress { get; init; } = "127.0.0.1";
    public bool IsOnline { get; init; } = true;
    public DateTime LastSeen { get; init; } = DateTime.UtcNow;
}

public record DeviceSession
{
    public required string SessionId { get; init; }
    public required string DeviceId { get; init; }
    public required string OperatorName { get; init; }
    public required string OperatorLocation { get; init; }
    public DateTime StartedAt { get; init; } = DateTime.UtcNow;
    public bool IsActive { get; init; } = true;
}
