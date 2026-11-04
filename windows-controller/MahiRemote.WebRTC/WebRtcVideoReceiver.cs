using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace MahiRemote.WebRTC;

/// <summary>
/// Bitmap sink for decoded WebRTC frames. The current WPF application does not
/// yet contain a PeerConnection/decoder adapter that calls ProcessDecodedVideoBuffer.
/// This class therefore never fabricates a phone screen, system bars, or telemetry.
/// </summary>
public class VideoStreamStatistics
{
    public int Fps { get; set; }
    public int BitrateKbps { get; set; }
    public string Resolution { get; set; } = "unknown";
    public int Width { get; set; }
    public int Height { get; set; }
    public int RoundTripTimeMs { get; set; }
    public float PacketLossPercent { get; set; }
    public long TotalFramesRendered { get; set; }
    public long TotalFramesDropped { get; set; }
    public string IceConnectionType { get; set; } = "unknown";
}

public class IceServerConfig
{
    // Deployment TURN details must be loaded from the authenticated runtime
    // configuration. No shared TURN secret is embedded in source code.
    public string[] Urls { get; set; } = new[] { "stun:stun.l.google.com:19302" };
    public string Username { get; set; } = string.Empty;
    public string Credential { get; set; } = string.Empty;
}

public class WebRtcVideoReceiver
{
    public event Action<WriteableBitmap>? OnFrameBitmapReady;
    public event Action<VideoStreamStatistics>? OnStatsUpdated;
    public event Action<string>? OnStatusChanged;

    public VideoStreamStatistics CurrentStats { get; } = new();
    public IceServerConfig IceConfig { get; } = new();

    private WriteableBitmap? _screenBitmap;
    private bool _isRunning;
    private readonly object _renderLock = new();

    public bool IsReceivingVideo => _isRunning && CurrentStats.TotalFramesRendered > 0;

    public WriteableBitmap? GetCurrentBitmap() => _screenBitmap;

    public void InitializeSurface(int width = 1080, int height = 1920)
    {
        if (width <= 0 || height <= 0) throw new ArgumentOutOfRangeException(nameof(width));
        lock (_renderLock)
        {
            CurrentStats.Width = width;
            CurrentStats.Height = height;
            CurrentStats.Resolution = $"{width}x{height}";

            Application.Current?.Dispatcher.Invoke(() =>
            {
                _screenBitmap = new WriteableBitmap(width, height, 96, 96, PixelFormats.Bgra32, null);
                ClearSurface();
                OnFrameBitmapReady?.Invoke(_screenBitmap);
            });
        }
    }

    /// <summary>
    /// Marks the surface as ready for decoded frames. This method does not perform
    /// WebRTC negotiation; the native WPF PeerConnection/decoder integration is a
    /// remaining engineering task.
    /// </summary>
    public void StartReceiving(string sdpAnswer)
    {
        _isRunning = true;
        if (_screenBitmap == null) InitializeSurface();
        OnStatusChanged?.Invoke(
            "Waiting for a native WebRTC decoder. The WPF receiver is not connected to a PeerConnection yet; use the browser controller for live screen viewing."
        );
    }

    public void ProcessDecodedVideoBuffer(byte[] rawBgraBytes, int width, int height)
    {
        if (!_isRunning || rawBgraBytes == null || rawBgraBytes.Length == 0 || width <= 0 || height <= 0)
            return;

        int requiredLength = checked(width * height * 4);
        if (rawBgraBytes.Length < requiredLength)
        {
            CurrentStats.TotalFramesDropped++;
            OnStatsUpdated?.Invoke(CurrentStats);
            return;
        }

        Application.Current?.Dispatcher.Invoke(() =>
        {
            lock (_renderLock)
            {
                if (_screenBitmap == null || _screenBitmap.PixelWidth != width || _screenBitmap.PixelHeight != height)
                {
                    _screenBitmap = new WriteableBitmap(width, height, 96, 96, PixelFormats.Bgra32, null);
                }

                _screenBitmap.Lock();
                try
                {
                    var backBuffer = _screenBitmap.BackBuffer;
                    int stride = _screenBitmap.BackBufferStride;
                    for (int row = 0; row < height; row++)
                    {
                        System.Runtime.InteropServices.Marshal.Copy(
                            rawBgraBytes,
                            row * width * 4,
                            backBuffer + row * stride,
                            width * 4
                        );
                    }
                    _screenBitmap.AddDirtyRect(new Int32Rect(0, 0, width, height));
                }
                finally
                {
                    _screenBitmap.Unlock();
                }

                CurrentStats.Width = width;
                CurrentStats.Height = height;
                CurrentStats.Resolution = $"{width}x{height}";
                CurrentStats.TotalFramesRendered++;
                OnFrameBitmapReady?.Invoke(_screenBitmap);
                OnStatsUpdated?.Invoke(CurrentStats);
            }
        });
    }

    private void ClearSurface()
    {
        if (_screenBitmap == null) return;
        _screenBitmap.Lock();
        try
        {
            int stride = _screenBitmap.BackBufferStride;
            byte[] row = new byte[stride];
            for (int y = 0; y < _screenBitmap.PixelHeight; y++)
            {
                System.Runtime.InteropServices.Marshal.Copy(
                    row, 0, _screenBitmap.BackBuffer + y * stride, stride
                );
            }
            _screenBitmap.AddDirtyRect(new Int32Rect(0, 0, _screenBitmap.PixelWidth, _screenBitmap.PixelHeight));
        }
        finally
        {
            _screenBitmap.Unlock();
        }
    }

    public void UpdateMetrics(int fps, int bitrateKbps, int rttMs, float packetLoss = 0f)
    {
        // Accept metrics only from a future real RTCP statistics collector.
        CurrentStats.Fps = Math.Max(0, fps);
        CurrentStats.BitrateKbps = Math.Max(0, bitrateKbps);
        CurrentStats.RoundTripTimeMs = Math.Max(0, rttMs);
        CurrentStats.PacketLossPercent = Math.Max(0f, packetLoss);
        OnStatsUpdated?.Invoke(CurrentStats);
    }

    public void Stop()
    {
        _isRunning = false;
        OnStatusChanged?.Invoke("Video receiver stopped.");
    }
}
