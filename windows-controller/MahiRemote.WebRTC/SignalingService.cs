using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using MahiRemote.Core.Models;
using MahiRemote.Security;

namespace MahiRemote.WebRTC;

public class SignalingService
{
    private ClientWebSocket? _ws;
    private readonly CryptoProvider _crypto;
    private CancellationTokenSource? _cts;
    private long _commandSequence = 1;
    private string? _lastServerUrl;
    private string? _lastOperatorName;
    private string? _lastLocation;

    public event Action<string>? OnStatusChanged;
    public event Action<string, string>? OnSdpReceived;
    public event Action<string>? OnIceCandidateReceived;
    public event Action<long, bool, int>? OnCommandAckReceived;
    public event Action<string>? OnRemoteAppListReceived;
    public event Action<string>? OnRemoteFileListReceived;
    public event Action<string, long, string>? OnRemoteFileChunkReceived;
    public event Action<string>? OnDiagnosticsReportReceived;
    public event Action<int, int, string>? OnResolutionSyncReceived;

    public bool IsConnected => _ws?.State == WebSocketState.Open;

    public SignalingService(CryptoProvider crypto)
    {
        _crypto = crypto;
    }

    public async Task ConnectAsync(string serverUrl, string operatorName, string location)
    {
        _lastServerUrl = serverUrl;
        _lastOperatorName = operatorName;
        _lastLocation = location;

        _cts?.Cancel();
        _cts = new CancellationTokenSource();

        _ws = new ClientWebSocket();
        _ws.Options.SetRequestHeader("X-Operator-Fingerprint", _crypto.OperatorFingerprint);
        _ws.Options.SetRequestHeader("X-Operator-Name", operatorName);
        _ws.Options.SetRequestHeader("X-Operator-Location", location);

        OnStatusChanged?.Invoke($"Connecting to signaling server at {serverUrl}...");

        try
        {
            await _ws.ConnectAsync(new Uri(serverUrl), _cts.Token);
            OnStatusChanged?.Invoke("Connected & Authenticated with Signaling Server");

            _ = ReceiveLoopAsync(_cts.Token);
            _ = HeartbeatLoopAsync(_cts.Token);
        }
        catch (Exception ex)
        {
            OnStatusChanged?.Invoke($"Signaling connection failed: {ex.Message}. Scheduling auto-reconnect...");
            ScheduleReconnect();
        }
    }

    public async Task SendPairRequestAsync(string targetDeviceId, string pairingCode, string[] scopes)
    {
        var payload = new
        {
            type = "PAIR_REQUEST",
            targetDeviceId,
            pairingCode,
            operatorFingerprint = _crypto.OperatorFingerprint,
            requestedScopes = scopes,
            timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
        };

        await SendJsonAsync(payload);
    }

    public async Task<long> SendCommandAsync(CommandPacket command)
    {
        long seq = Interlocked.Increment(ref _commandSequence);

        var message = new
        {
            type = "INPUT_EVENT",
            sequence = seq,
            command
        };

        await SendJsonAsync(message);
        return seq;
    }

    public async Task SendAppCommandAsync(string action, string packageName = "")
    {
        var message = new
        {
            type = "APP_COMMAND",
            action,
            packageName
        };
        await SendJsonAsync(message);
    }

    public async Task SendFileCommandAsync(string action, string path, string name = "", long offset = 0, int length = 32768, string data = "")
    {
        var message = new
        {
            type = "FILE_COMMAND",
            action,
            path,
            name,
            offset,
            length,
            data
        };
        await SendJsonAsync(message);
    }

    public async Task SendDiagnosticsRunAsync()
    {
        var message = new
        {
            type = "DIAGNOSTICS_RUN"
        };
        await SendJsonAsync(message);
    }

    private async Task SendJsonAsync(object data)
    {
        if (_ws == null || _ws.State != WebSocketState.Open) return;
        string json = JsonSerializer.Serialize(data);
        byte[] bytes = Encoding.UTF8.GetBytes(json);
        await _ws.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, CancellationToken.None);
    }

    private async Task ReceiveLoopAsync(CancellationToken ct)
    {
        var buffer = new byte[65536];
        while (!ct.IsCancellationRequested && _ws?.State == WebSocketState.Open)
        {
            try
            {
                var result = await _ws.ReceiveAsync(new ArraySegment<byte>(buffer), ct);
                if (result.MessageType == WebSocketMessageType.Close)
                {
                    await _ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "Closing", CancellationToken.None);
                    OnStatusChanged?.Invoke("Disconnected from signaling server");
                    ScheduleReconnect();
                    break;
                }

                string msg = Encoding.UTF8.GetString(buffer, 0, result.Count);
                ProcessMessage(msg);
            }
            catch (Exception ex)
            {
                OnStatusChanged?.Invoke($"Signaling socket interrupted: {ex.Message}");
                ScheduleReconnect();
                break;
            }
        }
    }

    private void ProcessMessage(string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            if (!doc.RootElement.TryGetProperty("type", out var typeElem)) return;
            string type = typeElem.GetString() ?? "";

            switch (type)
            {
                case "SDP_ANSWER":
                    if (doc.RootElement.TryGetProperty("payload", out var p) && p.TryGetProperty("sdp", out var sdpElem))
                        OnSdpReceived?.Invoke("answer", sdpElem.GetString() ?? "");
                    break;

                case "ICE_CANDIDATE":
                    if (doc.RootElement.TryGetProperty("candidate", out var candElem))
                        OnIceCandidateReceived?.Invoke(candElem.GetString() ?? "");
                    break;

                case "COMMAND_ACK":
                    long seq = doc.RootElement.TryGetProperty("sequence", out var seqElem) ? seqElem.GetInt64() : 0;
                    bool ok = doc.RootElement.TryGetProperty("success", out var okElem) && okElem.GetBoolean();
                    int rtt = doc.RootElement.TryGetProperty("rtt", out var rttElem) ? rttElem.GetInt32() : 25;
                    OnCommandAckReceived?.Invoke(seq, ok, rtt);
                    break;

                case "APP_LIST_RESPONSE":
                    if (doc.RootElement.TryGetProperty("payload", out var appListElem))
                        OnRemoteAppListReceived?.Invoke(appListElem.GetString() ?? "[]");
                    break;

                case "FILE_LIST_RESPONSE":
                    if (doc.RootElement.TryGetProperty("payload", out var fileListElem))
                        OnRemoteFileListReceived?.Invoke(fileListElem.GetString() ?? "[]");
                    break;

                case "FILE_CHUNK_RESPONSE":
                    string path = doc.RootElement.TryGetProperty("path", out var pathEl) ? pathEl.GetString() ?? "" : "";
                    long offset = doc.RootElement.TryGetProperty("offset", out var offEl) ? offEl.GetInt64() : 0;
                    string data = doc.RootElement.TryGetProperty("data", out var datEl) ? datEl.GetString() ?? "" : "";
                    OnRemoteFileChunkReceived?.Invoke(path, offset, data);
                    break;

                case "DIAGNOSTICS_RESULT":
                    if (doc.RootElement.TryGetProperty("payload", out var diagElem))
                        OnDiagnosticsReportReceived?.Invoke(diagElem.GetString() ?? "{}");
                    break;

                case "RESOLUTION_SYNC":
                    int w = doc.RootElement.TryGetProperty("width", out var wEl) ? wEl.GetInt32() : 1080;
                    int h = doc.RootElement.TryGetProperty("height", out var hEl) ? hEl.GetInt32() : 1920;
                    string orient = doc.RootElement.TryGetProperty("orientation", out var oEl) ? oEl.GetString() ?? "PORTRAIT" : "PORTRAIT";
                    OnResolutionSyncReceived?.Invoke(w, h, orient);
                    break;
            }
        }
        catch { }
    }

    private async Task HeartbeatLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && _ws?.State == WebSocketState.Open)
        {
            try
            {
                await Task.Delay(15000, ct);
                if (_ws?.State == WebSocketState.Open)
                {
                    await SendJsonAsync(new { type = "PING", timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() });
                }
            }
            catch { break; }
        }
    }

    private void ScheduleReconnect()
    {
        Task.Run(async () =>
        {
            await Task.Delay(5000);
            if (!IsConnected && !string.IsNullOrEmpty(_lastServerUrl) && !string.IsNullOrEmpty(_lastOperatorName))
            {
                OnStatusChanged?.Invoke("Attempting automatic reconnection to signaling hub...");
                await ConnectAsync(_lastServerUrl, _lastOperatorName, _lastLocation ?? "Sylhet");
            }
        });
    }

    public async Task DisconnectAsync()
    {
        _cts?.Cancel();
        if (_ws != null && _ws.State == WebSocketState.Open)
        {
            await _ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "Operator disconnected", CancellationToken.None);
        }
        _ws?.Dispose();
        _ws = null;
        OnStatusChanged?.Invoke("Signaling session terminated.");
    }
}
