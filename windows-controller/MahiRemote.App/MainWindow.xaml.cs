using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media.Imaging;
using MahiRemote.Core.Models;
using MahiRemote.Devices;
using MahiRemote.Security;
using MahiRemote.WebRTC;

namespace MahiRemote.App;

public partial class MainWindow : Window
{
    private readonly CryptoProvider _crypto;
    private readonly SignalingService _signaling;
    private readonly DeviceManager _deviceManager;
    private readonly WebRtcVideoReceiver _videoReceiver;

    private Point _dragStartPoint;
    private long _dragStartTime;
    private bool _isDragging = false;
    private int _clickCount = 0;
    private DateTime _lastClickTime = DateTime.MinValue;

    public MainWindow()
    {
        InitializeComponent();

        _crypto = new CryptoProvider();
        _signaling = new SignalingService(_crypto);
        _deviceManager = new DeviceManager();
        _videoReceiver = new WebRtcVideoReceiver();

        LstDevices.ItemsSource = _deviceManager.PairedDevices;
        if (_deviceManager.PairedDevices.Count > 0)
        {
            LstDevices.SelectedIndex = 0;
        }

        // Initialize real screen surface
        _videoReceiver.InitializeSurface(1080, 1920);

        _videoReceiver.OnFrameBitmapReady += bitmap =>
        {
            Dispatcher.Invoke(() =>
            {
                ImgStreamSurface.Source = bitmap;
                PnlStreamPlaceholder.Visibility = Visibility.Collapsed;
            });
        };

        _videoReceiver.OnStatsUpdated += stats =>
        {
            Dispatcher.Invoke(() =>
            {
                TxtStreamMetrics.Text = $"{stats.Fps} FPS | {stats.BitrateKbps} Kbps | {stats.RoundTripTimeMs} ms RTT | {stats.IceConnectionType}";
            });
        };

        _signaling.OnStatusChanged += status =>
        {
            Dispatcher.Invoke(() =>
            {
                TxtStatusBar.Text = status;
                LogAudit($"SIGNALING: {status}");
            });
        };

        _signaling.OnCommandAckReceived += (seq, success, rtt) =>
        {
            Dispatcher.Invoke(() =>
            {
                LogAudit($"ACK: Command #{seq} confirmed (RTT: {rtt}ms, Status: {(success ? "SUCCESS" : "REJECTED")})");
            });
        };

        _signaling.OnRemoteAppListReceived += json =>
        {
            Dispatcher.Invoke(() =>
            {
                LogAudit($"APPS: Received remote installed application catalog.");
            });
        };

        _signaling.OnRemoteFileListReceived += json =>
        {
            Dispatcher.Invoke(() =>
            {
                try
                {
                    LstRemoteFiles.Items.Clear();
                    using var doc = JsonDocument.Parse(json);
                    foreach (var item in doc.RootElement.EnumerateArray())
                    {
                        string name = item.GetProperty("name").GetString() ?? "";
                        bool isDir = item.GetProperty("isDir").GetBoolean();
                        LstRemoteFiles.Items.Add($"{(isDir ? "[DIR] " : "      ")}{name}");
                    }
                }
                catch { }
            });
        };

        _signaling.OnDiagnosticsReportReceived += json =>
        {
            Dispatcher.Invoke(() =>
            {
                LogAudit($"DIAGNOSTICS REPORT: {json}");
                MessageBox.Show($"Remote Phone Diagnostics Report:\n\n{json}", "Mahi Remote Diagnostics", MessageBoxButton.OK, MessageBoxImage.Information);
            });
        };

        LogAudit($"INITIALIZED: Windows Operator Workstation (Sylhet NOC). Fingerprint: {_crypto.OperatorFingerprint}");

        // Connect to signaling
        _ = _signaling.ConnectAsync("wss://signaling.mahi-remote.cloud/ws", "Sylhet NOC Operator #1", "Sylhet, Bangladesh");
    }

    private void PhoneScreen_MouseDown(object sender, MouseButtonEventArgs e)
    {
        _dragStartPoint = e.GetPosition(PhoneScreenCanvas);
        _dragStartTime = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        _isDragging = true;

        if ((DateTime.Now - _lastClickTime).TotalMilliseconds < 300)
        {
            _clickCount++;
        }
        else
        {
            _clickCount = 1;
        }
        _lastClickTime = DateTime.Now;
    }

    private void PhoneScreen_MouseUp(object sender, MouseButtonEventArgs e)
    {
        if (!_isDragging) return;
        _isDragging = false;

        Point endPoint = e.GetPosition(PhoneScreenCanvas);
        long duration = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - _dragStartTime;

        int nativeW = _videoReceiver.CurrentStats.Width;
        int nativeH = _videoReceiver.CurrentStats.Height;

        float nativeStartX = (float)(_dragStartPoint.X / PhoneScreenCanvas.ActualWidth * nativeW);
        float nativeStartY = (float)(_dragStartPoint.Y / PhoneScreenCanvas.ActualHeight * nativeH);
        float nativeEndX = (float)(endPoint.X / PhoneScreenCanvas.ActualWidth * nativeW);
        float nativeEndY = (float)(endPoint.Y / PhoneScreenCanvas.ActualHeight * nativeH);

        double distance = Math.Sqrt(Math.Pow(endPoint.X - _dragStartPoint.X, 2) + Math.Pow(endPoint.Y - _dragStartPoint.Y, 2));

        if (distance < 10)
        {
            if (_clickCount == 2)
            {
                // Double Tap
                SendRemoteCommand(new CommandPacket
                {
                    CommandId = Guid.NewGuid().ToString(),
                    Type = CommandType.Tap,
                    Action = "DOUBLE_TAP",
                    SessionId = "active_session",
                    X = nativeStartX,
                    Y = nativeStartY,
                    Signature = _crypto.SignPayload($"DOUBLETAP:{nativeStartX}:{nativeStartY}")
                });
                LogAudit($"INTERACTION: Double Click -> Double Tap at ({nativeStartX:F0}, {nativeStartY:F0})");
            }
            else if (duration > 600)
            {
                // Long Press
                SendRemoteCommand(new CommandPacket
                {
                    CommandId = Guid.NewGuid().ToString(),
                    Type = CommandType.Tap,
                    Action = "LONG_PRESS",
                    SessionId = "active_session",
                    X = nativeStartX,
                    Y = nativeStartY,
                    DurationMs = duration,
                    Signature = _crypto.SignPayload($"LONGPRESS:{nativeStartX}:{nativeStartY}")
                });
                LogAudit($"INTERACTION: Long Click ({duration}ms) -> Long Press at ({nativeStartX:F0}, {nativeStartY:F0})");
            }
            else
            {
                // Single Tap
                SendRemoteCommand(new CommandPacket
                {
                    CommandId = Guid.NewGuid().ToString(),
                    Type = CommandType.Tap,
                    SessionId = "active_session",
                    X = nativeStartX,
                    Y = nativeStartY,
                    Signature = _crypto.SignPayload($"TAP:{nativeStartX}:{nativeStartY}")
                });
                LogAudit($"INTERACTION: Click -> Tap at ({nativeStartX:F0}, {nativeStartY:F0})");
            }
        }
        else
        {
            // Drag or Swipe
            if (duration > 500)
            {
                SendRemoteCommand(new CommandPacket
                {
                    CommandId = Guid.NewGuid().ToString(),
                    Type = CommandType.Swipe,
                    Action = "DRAG",
                    SessionId = "active_session",
                    X = nativeStartX,
                    Y = nativeStartY,
                    X2 = nativeEndX,
                    Y2 = nativeEndY,
                    DurationMs = duration,
                    Signature = _crypto.SignPayload($"DRAG:{nativeStartX}:{nativeStartY}:{nativeEndX}:{nativeEndY}")
                });
                LogAudit($"INTERACTION: Drag -> Sustained Drag from ({nativeStartX:F0}, {nativeStartY:F0}) to ({nativeEndX:F0}, {nativeEndY:F0}) in {duration}ms");
            }
            else
            {
                SendRemoteCommand(new CommandPacket
                {
                    CommandId = Guid.NewGuid().ToString(),
                    Type = CommandType.Swipe,
                    SessionId = "active_session",
                    X = nativeStartX,
                    Y = nativeStartY,
                    X2 = nativeEndX,
                    Y2 = nativeEndY,
                    DurationMs = Math.Clamp(duration, 100, 2000),
                    Signature = _crypto.SignPayload($"SWIPE:{nativeStartX}:{nativeStartY}:{nativeEndX}:{nativeEndY}")
                });
                LogAudit($"INTERACTION: Drag -> Touch Swipe from ({nativeStartX:F0}, {nativeStartY:F0}) to ({nativeEndX:F0}, {nativeEndY:F0}) in {duration}ms");
            }
        }
    }

    private void PhoneScreen_MouseWheel(object sender, MouseWheelEventArgs e)
    {
        int delta = e.Delta;
        SendRemoteCommand(new CommandPacket
        {
            CommandId = Guid.NewGuid().ToString(),
            Type = CommandType.Scroll,
            SessionId = "active_session",
            Action = delta > 0 ? "UP" : "DOWN",
            Signature = _crypto.SignPayload($"SCROLL:{delta}")
        });
        LogAudit($"INTERACTION: Mouse Wheel Scroll -> { (delta > 0 ? "Scroll Up" : "Scroll Down") }");
    }

    private void BtnNavBack_Click(object sender, RoutedEventArgs e) => SendGlobalAction("BACK");
    private void BtnNavHome_Click(object sender, RoutedEventArgs e) => SendGlobalAction("HOME");
    private void BtnNavRecents_Click(object sender, RoutedEventArgs e) => SendGlobalAction("RECENTS");
    private void BtnNavNotifs_Click(object sender, RoutedEventArgs e) => SendGlobalAction("NOTIFICATIONS");

    private void SendGlobalAction(string action)
    {
        SendRemoteCommand(new CommandPacket
        {
            CommandId = Guid.NewGuid().ToString(),
            Type = CommandType.GlobalAction,
            SessionId = "active_session",
            Action = action,
            Signature = _crypto.SignPayload($"GLOBAL:{action}")
        });
        LogAudit($"NAV: Executed Global Action: {action}");
    }

    private void SendRemoteCommand(CommandPacket packet)
    {
        _ = _signaling.SendCommandAsync(packet);
    }

    private void BtnPairNew_Click(object sender, RoutedEventArgs e)
    {
        var inputDlg = new Window
        {
            Title = "Pair New Android Device",
            Width = 420,
            Height = 240,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Owner = this,
            Background = (System.Windows.Media.Brush)FindResource("SurfaceDark")
        };

        var sp = new StackPanel { Margin = new Thickness(16) };
        sp.Children.Add(new TextBlock
        {
            Text = "Enter 6-digit one-time code from phone (or scan QR):",
            Foreground = (System.Windows.Media.Brush)FindResource("TextPrimary"),
            Margin = new Thickness(0, 0, 0, 8)
        });

        var txtCode = new TextBox
        {
            FontSize = 20,
            FontWeight = FontWeights.Bold,
            FontFamily = new System.Windows.Media.FontFamily("Consolas"),
            Padding = new Thickness(8),
            Margin = new Thickness(0, 0, 0, 16)
        };
        sp.Children.Add(txtCode);

        var btnSubmit = new Button
        {
            Content = "Request Cryptographic Pairing",
            Background = (System.Windows.Media.Brush)FindResource("CyanAccent"),
            Foreground = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(13, 17, 23)),
            FontWeight = FontWeights.Bold,
            Padding = new Thickness(10)
        };
        btnSubmit.Click += async (s, args) =>
        {
            string code = txtCode.Text.Trim();
            if (code.Length >= 6)
            {
                inputDlg.Close();
                LogAudit($"PAIRING: Submitting authorization request with 6-digit code {code} to device in Noakhali...");
                await _signaling.SendPairRequestAsync("TARGET_DEVICE", code, new[] { "Screen", "Input", "Files", "Diagnostics" });
                MessageBox.Show($"Pairing challenge sent to phone in Noakhali. Device owner must tap 'Authorize Session'.", "Mahi Remote", MessageBoxButton.OK, MessageBoxImage.Information);
            }
        };
        sp.Children.Add(btnSubmit);

        inputDlg.Content = sp;
        inputDlg.ShowDialog();
    }

    private void BtnEmergencyKill_Click(object sender, RoutedEventArgs e)
    {
        var confirm = MessageBox.Show(
            "Terminate active remote session and immediately disconnect from phone in Noakhali?",
            "Emergency Disconnect",
            MessageBoxButton.YesNo,
            MessageBoxImage.Warning
        );

        if (confirm == MessageBoxResult.Yes)
        {
            SendRemoteCommand(new CommandPacket
            {
                CommandId = Guid.NewGuid().ToString(),
                Type = CommandType.EmergencyDisconnect,
                SessionId = "active_session",
                Signature = _crypto.SignPayload("EMERGENCY_DISCONNECT")
            });
            _ = _signaling.DisconnectAsync();
            LogAudit("SECURITY: EMERGENCY DISCONNECT SENT. Session wiped.");
            TxtStatusBar.Text = "Emergency disconnect executed. Session terminated.";
        }
    }

    private void BtnRunRemoteDiag_Click(object sender, RoutedEventArgs e)
    {
        _ = _signaling.SendDiagnosticsRunAsync();
        LogAudit("DIAGNOSTICS: Requested remote diagnostic suite execution on target phone.");
    }

    private void BtnLaunchApp_Click(object sender, RoutedEventArgs e)
    {
        string pkg = TxtAppPkg.Text.Trim();
        if (!string.IsNullOrEmpty(pkg))
        {
            _ = _signaling.SendAppCommandAsync("LAUNCH", pkg);
            LogAudit($"APP: Requested launch of package: {pkg}");
        }
    }

    private void BtnBrowseFiles_Click(object sender, RoutedEventArgs e)
    {
        _ = _signaling.SendFileCommandAsync("LIST", "/storage/emulated/0/Documents");
        LogAudit("FILES: Enumerated permitted scoped Documents folder.");
    }

    private void LstDevices_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (LstDevices.SelectedItem is DeviceInfo dev)
        {
            LogAudit($"DEVICE: Selected {dev.DeviceName} ({dev.DeviceId}) in Noakhali.");
        }
    }

    private void CmbQuality_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (CmbQuality.SelectedIndex == 0)
        {
            _videoReceiver.UpdateMetrics(60, 4000, 24);
        }
        else if (CmbQuality.SelectedIndex == 1)
        {
            _videoReceiver.UpdateMetrics(30, 2048, 28);
        }
        else
        {
            _videoReceiver.UpdateMetrics(15, 800, 36);
        }
    }

    private void LogAudit(string message)
    {
        string entry = $"[{DateTime.Now:HH:mm:ss}] {message}\n";
        TxtAuditBox.AppendText(entry);
        TxtAuditBox.ScrollToEnd();
    }
}
