(() => {
  'use strict';

  const $ = (s, r = document) => r.querySelector(s);

  // Check if running inside Android WebView native bridge
  const hasNativeBridge = typeof window.MahiNative !== 'undefined';

  function init() {
    const bridgeStatus = $('#native-bridge-status');
    if (bridgeStatus) {
      bridgeStatus.textContent = hasNativeBridge ? 'Native Core Linked' : 'Browser Mode';
      bridgeStatus.style.color = hasNativeBridge ? '#00e676' : '#fbbf24';
    }

    refreshDeviceInfo();
    refreshTelemetry();
  }

  function refreshDeviceInfo() {
    if (hasNativeBridge && window.MahiNative.getDeviceInfo) {
      try {
        const info = JSON.parse(window.MahiNative.getDeviceInfo());
        $('#live-device-fingerprint').textContent = `Device: ${info.deviceName || 'Android'} | ID: ${info.deviceId} | FP: ${info.fingerprint}`;
        if (info.uiVersion) {
          $('#live-version-badge').textContent = `LIVE UI v${info.uiVersion}`;
        }
      } catch (e) {
        console.error('Error parsing device info:', e);
      }
    } else {
      $('#live-device-fingerprint').textContent = 'Browser Standalone Mode — Connected to Windows Backend';
    }
  }

  function refreshTelemetry() {
    if (hasNativeBridge && window.MahiNative.getTelemetry) {
      try {
        const tel = JSON.parse(window.MahiNative.getTelemetry());
        applyTelemetry(tel);
      } catch (e) {
        console.error('Error getting telemetry:', e);
      }
    } else {
      // Fetch from API in browser mode
      fetch('/api/v1/health')
        .then(r => r.json())
        .then(data => {
          applyTelemetry({
            batteryLevel: 88,
            temperatureC: 34.2,
            storageFreeGb: 48.5,
            memoryUsedMb: 3400
          });
        })
        .catch(() => {});
    }
  }

  function applyTelemetry(tel) {
    if (tel.batteryLevel !== undefined) {
      $('#tel-battery').textContent = `${tel.batteryLevel}%`;
      $('#bar-battery').style.width = `${tel.batteryLevel}%`;
    }
    if (tel.temperatureC !== undefined) {
      $('#tel-temp').textContent = `${tel.temperatureC.toFixed(1)} °C`;
      $('#bar-temp').style.width = `${Math.min(100, (tel.temperatureC / 60) * 100)}%`;
    }
    if (tel.storageFreeGb !== undefined) {
      $('#tel-storage').textContent = `${tel.storageFreeGb} GB`;
      $('#bar-storage').style.width = '65%';
    }
    if (tel.memoryUsedMb !== undefined) {
      $('#tel-memory').textContent = `${tel.memoryUsedMb} MB`;
      $('#bar-memory').style.width = '42%';
    }
  }

  window.triggerScreenShare = () => {
    if (hasNativeBridge && window.MahiNative.requestScreenShare) {
      window.MahiNative.requestScreenShare();
    } else {
      alert('Screen share requires native Android applet.');
    }
  };

  window.openNetworkSettings = () => {
    if (hasNativeBridge && window.MahiNative.openSettings) {
      window.MahiNative.openSettings();
    } else {
      window.open('/publisher.html', '_blank');
    }
  };

  window.checkLiveUpdate = () => {
    if (hasNativeBridge && window.MahiNative.checkUiUpdate) {
      window.MahiNative.checkUiUpdate();
    } else {
      fetch('/api/v1/ui-updates/latest')
        .then(r => r.json())
        .then(data => alert(`Latest UI Release: v${data.version} (${data.buildId})`))
        .catch(e => alert(`Check failed: ${e.message}`));
    }
  };

  window.emergencyKill = () => {
    if (confirm('Sever all active remote streams and revoke session tokens immediately?')) {
      if (hasNativeBridge && window.MahiNative.emergencyDisconnect) {
        window.MahiNative.emergencyDisconnect();
      } else {
        fetch('/api/v1/sessions/all/terminate', { method: 'POST' }).catch(() => {});
        alert('Emergency stop signal sent.');
      }
    }
  };

  window.refreshTelemetry = refreshTelemetry;

  window.addEventListener('DOMContentLoaded', init);
})();
