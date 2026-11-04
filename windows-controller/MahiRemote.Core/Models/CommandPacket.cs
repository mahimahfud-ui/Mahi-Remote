namespace MahiRemote.Core.Models;

public enum CommandType
{
    Tap,
    Swipe,
    Scroll,
    TextInput,
    GlobalAction,
    LaunchApp,
    UninstallRequest,
    FileBrowse,
    FileDownload,
    FileUpload,
    DiagnosticsRun,
    EmergencyDisconnect
}

public record CommandPacket
{
    public required string CommandId { get; init; }
    public required CommandType Type { get; init; }
    public required string SessionId { get; init; }
    public long Timestamp { get; init; } = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    // Interaction Parameters
    public float? X { get; init; }
    public float? Y { get; init; }
    public float? X2 { get; init; }
    public float? Y2 { get; init; }
    public long? DurationMs { get; init; }
    public string? Action { get; init; }
    public string? Text { get; init; }
    public string? TargetPackage { get; init; }
    public string? Path { get; init; }

    public required string Signature { get; init; }
}
