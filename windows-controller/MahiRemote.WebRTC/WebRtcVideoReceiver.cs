using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace MahiRemote.WebRTC;

public class VideoStreamStatistics
{
    public int Fps { get; set; } = 30;
    public int BitrateKbps { get; set; } = 2048;
    public string Resolution { get; set; } = "1080x1920";
    public int Width { get; set; } = 1080;
    public int Height { get; set; } = 1920;
    public int RoundTripTimeMs { get; set; } = 28;
    public float PacketLossPercent { get; set; } = 0.005f;
    public long TotalFramesRendered { get; set; } = 0;
    public long TotalFramesDropped { get; set; } = 0;
    public string IceConnectionType { get; set; } = "srflx (STUN NAT)";
}

public class IceServerConfig
{
    public string[] Urls { get; set; } = new[]
    {
        "stun:stun.l.google.com:19302",
        "turn:turn.mahi-remote.cloud:3478"
    };
    public string Username { get; set; } = "mahi_operator";
    public string Credential { get; set; } = "mahi-turn-shared-secret-auth-key";
}

public class WebRtcVideoReceiver
{
    public event Action<WriteableBitmap>? OnFrameBitmapReady;
    public event Action<VideoStreamStatistics>? OnStatsUpdated;

    public VideoStreamStatistics CurrentStats { get; } = new();
    public IceServerConfig IceConfig { get; } = new();

    private WriteableBitmap? _screenBitmap;
    private bool _isRunning = false;
    private readonly object _renderLock = new();

    public WriteableBitmap? GetCurrentBitmap() => _screenBitmap;

    public void InitializeSurface(int width = 1080, int height = 1920)
    {
        lock (_renderLock)
        {
            CurrentStats.Width = width;
            CurrentStats.Height = height;
            CurrentStats.Resolution = $"{width}x{height}";

            Application.Current?.Dispatcher.Invoke(() =>
            {
                _screenBitmap = new WriteableBitmap(
                    width,
                    height,
                    96,
                    96,
                    PixelFormats.Bgra32,
                    null
                );
                RenderPatternFrame(width, height);
                OnFrameBitmapReady?.Invoke(_screenBitmap);
            });
        }
    }

    public void StartReceiving(string sdpAnswer)
    {
        _isRunning = true;
        if (_screenBitmap == null)
        {
            InitializeSurface(1080, 1920);
        }
    }

    public void ProcessDecodedVideoBuffer(byte[] rawBgraBytes, int width, int height)
    {
        if (!_isRunning || rawBgraBytes == null || rawBgraBytes.Length == 0) return;

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
                    unsafe
                    {
                        IntPtr pBackBuffer = _screenBitmap.BackBuffer;
                        System.Runtime.InteropServices.Marshal.Copy(rawBgraBytes, 0, pBackBuffer, rawBgraBytes.Length);
                    }
                    _screenBitmap.AddDirtyRect(new Int32Rect(0, 0, width, height));
                }
                finally
                {
                    _screenBitmap.Unlock();
                }

                CurrentStats.TotalFramesRendered++;
                OnFrameBitmapReady?.Invoke(_screenBitmap);
            }
        });
    }

    public void RenderPatternFrame(int width, int height)
    {
        if (_screenBitmap == null) return;

        _screenBitmap.Lock();
        try
        {
            int stride = width * 4;
            byte[] pixels = new byte[height * stride];

            // Render modern dark charcoal theme screen with cyan status badge
            for (int y = 0; y < height; y++)
            {
                for (int x = 0; x < width; x++)
                {
                    int idx = (y * stride) + (x * 4);
                    // Blue, Green, Red, Alpha
                    if (y < 80) // Top status bar
                    {
                        pixels[idx] = 0x22;     // B
                        pixels[idx + 1] = 0x1B; // G
                        pixels[idx + 2] = 0x16; // R
                        pixels[idx + 3] = 0xFF; // A
                    }
                    else if (y > height - 100) // Bottom nav bar
                    {
                        pixels[idx] = 0x22;
                        pixels[idx + 1] = 0x1B;
                        pixels[idx + 2] = 0x16;
                        pixels[idx + 3] = 0xFF;
                    }
                    else
                    {
                        // Background wallpaper gradient
                        byte grad = (byte)(18 + (y * 15 / height));
                        pixels[idx] = (byte)(grad + 6);
                        pixels[idx + 1] = grad;
                        pixels[idx + 2] = (byte)(grad - 4);
                        pixels[idx + 3] = 0xFF;
                    }
                }
            }

            unsafe
            {
                IntPtr pBackBuffer = _screenBitmap.BackBuffer;
                System.Runtime.InteropServices.Marshal.Copy(pixels, 0, pBackBuffer, pixels.Length);
            }
            _screenBitmap.AddDirtyRect(new Int32Rect(0, 0, width, height));
        }
        finally
        {
            _screenBitmap.Unlock();
        }
    }

    public void UpdateMetrics(int fps, int bitrateKbps, int rttMs, float packetLoss = 0.005f)
    {
        CurrentStats.Fps = fps;
        CurrentStats.BitrateKbps = bitrateKbps;
        CurrentStats.RoundTripTimeMs = rttMs;
        CurrentStats.PacketLossPercent = packetLoss;
        CurrentStats.TotalFramesRendered += fps;

        OnStatsUpdated?.Invoke(CurrentStats);
    }

    public void Stop()
    {
        _isRunning = false;
    }
}
