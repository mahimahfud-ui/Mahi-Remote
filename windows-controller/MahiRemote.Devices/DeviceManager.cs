using System.Collections.ObjectModel;
using MahiRemote.Core.Models;

namespace MahiRemote.Devices;

public class DeviceManager
{
    public ObservableCollection<DeviceInfo> PairedDevices { get; } = new();

    public DeviceManager()
    {
        // Seed default device for demonstration / immediate operator testing
        PairedDevices.Add(new DeviceInfo
        {
            DeviceId = "MAHI-SAM-9B7C12F0",
            DeviceName = "Samsung Galaxy S24 Ultra",
            Fingerprint = "7A:B2:34:CD:5E:6F:10:99:88:77:66:55:44:33:22:11",
            AndroidVersion = "Android 14 (API 34)",
            BatteryPercent = 86,
            IsCharging = true,
            NetworkType = "Wi-Fi 6 (Noakhali)",
            IpAddress = "103.145.112.45",
            IsOnline = true
        });
    }

    public void AddDevice(DeviceInfo device)
    {
        var existing = PairedDevices.FirstOrDefault(d => d.DeviceId == device.DeviceId);
        if (existing != null)
        {
            PairedDevices.Remove(existing);
        }
        PairedDevices.Insert(0, device);
    }

    public void RemoveDevice(string deviceId)
    {
        var target = PairedDevices.FirstOrDefault(d => d.DeviceId == deviceId);
        if (target != null)
        {
            PairedDevices.Remove(target);
        }
    }
}
