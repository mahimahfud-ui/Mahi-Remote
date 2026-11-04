// Mahi Remote Control — Browser Controller Client
(function () {
  'use strict';

  // State Management
  const state = {
    ws: null,
    peerConnection: null,
    operatorFingerprint: 'OP-WEB-' + Math.random().toString(36).substring(2, 10).toUpperCase(),
    operatorName: 'Web Operator (Chrome/Windows)',
    operatorLocation: 'Sylhet NOC',
    activeDeviceId: 'MAHI-SAM-9B7C12F0',
    pairedDevices: [
      {
        deviceId: 'MAHI-SAM-9B7C12F0',
        deviceName: 'Samsung Galaxy S24 Ultra',
        location: 'Noakhali, Bangladesh',
        battery: 86,
        isOnline: true
      }
    ],
    commandSequence: 1,
    dragStart: null,
    lastClickTime: 0,
    clickCount: 0,
    currentPath: '/storage/emulated/0/Documents'
  };

  // DOM Elements
  const el = {
    badge: document.getElementById('connectionBadge'),
    latency: document.getElementById('latencyDisplay'),
    hostPill: document.getElementById('hostPill'),
    deviceList: document.getElementById('deviceList'),
    canvas: document.getElementById('phoneCanvas'),
    video: document.getElementById('remoteVideo'),
    placeholder: document.getElementById('streamPlaceholder'),
    streamStats: document.getElementById('streamStats'),
    auditBox: document.getElementById('auditLogBox'),
    pairModal: document.getElementById('pairModal'),
    btnPair: document.getElementById('btnPairDevice'),
    btnCloseModal: document.getElementById('btnCloseModal'),
    btnSubmitPair: document.getElementById('btnSubmitPair'),
    inputPairCode: document.getElementById('inputPairCode'),
    btnEmergencyKill: document.getElementById('btnEmergencyKill'),
    btnRunDiag: document.getElementById('btnRunDiag'),
    btnLaunchPkg: document.getElementById('btnLaunchPkg'),
    launchPkgInput: document.getElementById('launchPkgInput'),
    appList: document.getElementById('appList'),
    fileList: document.getElementById('fileList'),
    currentFilePath: document.getElementById('currentFilePath'),
    btnTriggerUpload: document.getElementById('btnTriggerUpload'),
    fileUploadInput: document.getElementById('fileUploadInput'),
    fileUploadProgress: document.getElementById('fileUploadProgress'),
    progressBarFill: document.getElementById('progressBarFill'),
    progressText: document.getElementById('progressText'),
    btnNewFolder: document.getElementById('btnNewFolder'),
    btnRefreshFiles: document.getElementById('btnRefreshFiles'),
    btnClearLogs: document.getElementById('btnClearLogs'),
    qualitySelect: document.getElementById('qualitySelect'),
    diagBattery: document.getElementById('diagBattery'),
    diagTemp: document.getElementById('diagTemp'),
    diagStorage: document.getElementById('diagStorage')
  };

  const ctx = el.canvas.getContext('2d');

  // Initialize
  function init() {
    el.hostPill.textContent = window.location.host;
    setupTabs();
    setupCanvasMouse();
    setupToolbar();
    setupModal();
    setupFileUpload();
    renderDevices();
    connectSignaling();
    startCanvasRenderLoop();
    logAudit('INIT', `Browser Operator Console active. Fingerprint: ${state.operatorFingerprint}`, 'security');
  }

  // WebSocket Signaling Connection (Dynamically works with ANY IP or Cloudflare tunnel!)
  function connectSignaling() {
    const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${wsProtocol}//${window.location.host}/ws`;

    logAudit('SIGNALING', `Connecting to signaling socket at ${wsUrl}...`);
    el.badge.textContent = 'CONNECTING';
    el.badge.className = 'badge badge-warning';

    try {
      state.ws = new WebSocket(wsUrl);

      state.ws.onopen = () => {
        el.badge.textContent = 'ONLINE / READY';
        el.badge.className = 'badge badge-success';
        logAudit('SIGNALING', `Connected to signaling hub. Authenticated as ${state.operatorFingerprint}`, 'security');
        
        // Initial ping for latency
        sendPing();
        setInterval(sendPing, 15000);
      };

      state.ws.onmessage = (event) => {
        try {
          const msg = JSON.parse(event.data);
          handleSignalingMessage(msg);
        } catch (e) {
          console.error('Invalid WS payload', e);
        }
      };

      state.ws.onclose = () => {
        el.badge.textContent = 'OFFLINE';
        el.badge.className = 'badge badge-danger';
        logAudit('SIGNALING', 'Socket disconnected. Retrying in 5 seconds...', 'critical');
        setTimeout(connectSignaling, 5000);
      };

      state.ws.onerror = (err) => {
        console.error('WebSocket error:', err);
      };
    } catch (e) {
      console.error('Failed to init WebSocket:', e);
    }
  }

  function sendWs(data) {
    if (state.ws && state.ws.readyState === WebSocket.OPEN) {
      state.ws.send(JSON.stringify(data));
      return true;
    }
    return false;
  }

  let lastPingTimestamp = 0;
  function sendPing() {
    lastPingTimestamp = Date.now();
    sendWs({ type: 'PING', timestamp: lastPingTimestamp });
  }

  // Handle incoming messages
  function handleSignalingMessage(msg) {
    switch (msg.type) {
      case 'PONG': {
        const rtt = Date.now() - lastPingTimestamp;
        el.latency.textContent = `${rtt} ms RTT`;
        break;
      }
      case 'COMMAND_ACK': {
        logAudit('ACK', `Command #${msg.sequence} confirmed by Noakhali phone (${msg.rtt}ms RTT)`);
        break;
      }
      case 'APP_LIST_RESPONSE': {
        renderAppList(JSON.parse(msg.payload || '[]'));
        break;
      }
      case 'FILE_LIST_RESPONSE': {
        renderFileList(JSON.parse(msg.payload || '[]'));
        break;
      }
      case 'DIAGNOSTICS_RESULT': {
        try {
          const diag = JSON.parse(msg.payload);
          if (diag.battery) el.diagBattery.textContent = `${diag.battery}% (Nominal)`;
          if (diag.temp) el.diagTemp.textContent = `${diag.temp}°C`;
          if (diag.storage) el.diagStorage.textContent = `${diag.storage} free`;
          logAudit('DIAGNOSTICS', `Diagnostics telemetry update: Battery ${diag.battery}%, Temp ${diag.temp}°C`, 'security');
        } catch (e) {}
        break;
      }
      case 'SDP_ANSWER': {
        handleSdpAnswer(msg.payload.sdp);
        break;
      }
    }
  }

  // WebRTC Setup
  async function initWebRTC() {
    const config = {
      iceServers: [
        { urls: 'stun:stun.l.google.com:19302' },
        {
          urls: 'turn:turn.mahi-remote.cloud:3478',
          username: 'mahi_operator',
          credential: 'mahi-turn-shared-secret-auth-key'
        }
      ]
    };

    state.peerConnection = new RTCPeerConnection(config);

    state.peerConnection.ontrack = (event) => {
      el.video.srcObject = event.streams[0];
      el.placeholder.style.display = 'none';
      logAudit('WEBRTC', 'Video track received. Hardware H.264 rendering started.', 'security');
    };

    state.peerConnection.onicecandidate = (event) => {
      if (event.candidate) {
        sendWs({
          type: 'ICE_CANDIDATE',
          candidate: event.candidate.candidate,
          targetDeviceId: state.activeDeviceId
        });
      }
    };
  }

  async function handleSdpAnswer(sdp) {
    if (!state.peerConnection) await initWebRTC();
    await state.peerConnection.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp }));
  }

  // Mouse to Touch Translation Engine
  function setupCanvasMouse() {
    let isMouseDown = false;

    el.canvas.addEventListener('mousedown', (e) => {
      isMouseDown = true;
      state.dragStart = getNativeCoords(e);
      state.dragStartTime = Date.now();

      if (Date.now() - state.lastClickTime < 300) {
        state.clickCount++;
      } else {
        state.clickCount = 1;
      }
      state.lastClickTime = Date.now();
    });

    el.canvas.addEventListener('mouseup', (e) => {
      if (!isMouseDown) return;
      isMouseDown = false;

      const endCoords = getNativeCoords(e);
      const duration = Date.now() - state.dragStartTime;
      const dx = endCoords.x - state.dragStart.x;
      const dy = endCoords.y - state.dragStart.y;
      const dist = Math.sqrt(dx * dx + dy * dy);

      if (dist < 15) {
        if (state.clickCount === 2) {
          // Double Tap
          sendCommand('Tap', { x: state.dragStart.x, y: state.dragStart.y, action: 'DOUBLE_TAP' });
          logAudit('INPUT', `Double Tap at (${Math.round(state.dragStart.x)}, ${Math.round(state.dragStart.y)})`);
        } else if (duration > 600) {
          // Long Press
          sendCommand('Tap', { x: state.dragStart.x, y: state.dragStart.y, action: 'LONG_PRESS', durationMs: duration });
          logAudit('INPUT', `Long Press (${duration}ms) at (${Math.round(state.dragStart.x)}, ${Math.round(state.dragStart.y)})`);
        } else {
          // Single Tap
          sendCommand('Tap', { x: state.dragStart.x, y: state.dragStart.y, action: 'TAP' });
          logAudit('INPUT', `Tap at (${Math.round(state.dragStart.x)}, ${Math.round(state.dragStart.y)})`);
        }
      } else {
        if (duration > 500) {
          // Drag
          sendCommand('Swipe', {
            x: state.dragStart.x, y: state.dragStart.y,
            x2: endCoords.x, y2: endCoords.y,
            action: 'DRAG', durationMs: duration
          });
          logAudit('INPUT', `Sustained Drag from (${Math.round(state.dragStart.x)}, ${Math.round(state.dragStart.y)}) to (${Math.round(endCoords.x)}, ${Math.round(endCoords.y)})`);
        } else {
          // Swipe
          sendCommand('Swipe', {
            x: state.dragStart.x, y: state.dragStart.y,
            x2: endCoords.x, y2: endCoords.y,
            action: 'SWIPE', durationMs: Math.max(100, duration)
          });
          logAudit('INPUT', `Swipe from (${Math.round(state.dragStart.x)}, ${Math.round(state.dragStart.y)}) to (${Math.round(endCoords.x)}, ${Math.round(endCoords.y)}) in ${duration}ms`);
        }
      }
    });

    el.canvas.addEventListener('wheel', (e) => {
      e.preventDefault();
      const action = e.deltaY > 0 ? 'DOWN' : 'UP';
      sendCommand('Scroll', { action });
      logAudit('INPUT', `Scroll ${action}`);
    }, { passive: false });

    // Keyboard Text Input
    window.addEventListener('keydown', (e) => {
      // Ignore if user is typing into input fields
      if (['INPUT', 'TEXTAREA'].includes(document.activeElement.tagName)) return;

      if (e.key === 'Backspace') {
        sendCommand('GlobalAction', { action: 'BACK' });
      } else if (e.key === 'Home') {
        sendCommand('GlobalAction', { action: 'HOME' });
      } else if (e.key.length === 1 && !e.ctrlKey && !e.altKey && !e.metaKey) {
        sendCommand('TextInput', { text: e.key });
        logAudit('INPUT', `Key Typed: '${e.key}'`);
      }
    });
  }

  function getNativeCoords(event) {
    const rect = el.canvas.getBoundingClientRect();
    const clientX = event.clientX - rect.left;
    const clientY = event.clientY - rect.top;

    // Scale to native 1080x1920
    const scaleX = 1080 / rect.width;
    const scaleY = 1920 / rect.height;

    return {
      x: Math.max(0, Math.min(1080, clientX * scaleX)),
      y: Math.max(0, Math.min(1920, clientY * scaleY))
    };
  }

  function sendCommand(type, params) {
    const seq = state.commandSequence++;
    const payload = {
      type: 'INPUT_EVENT',
      sequence: seq,
      targetDeviceId: state.activeDeviceId,
      command: {
        commandId: 'cmd_' + Math.random().toString(36).substring(2, 9),
        type: type,
        timestamp: Date.now(),
        ...params
      }
    };
    sendWs(payload);
  }

  // Canvas Render Loop
  function startCanvasRenderLoop() {
    function render() {
      if (el.video.srcObject && el.video.readyState >= 2) {
        ctx.drawImage(el.video, 0, 0, el.canvas.width, el.canvas.height);
      } else {
        // Draw crisp modern UI frame on canvas
        ctx.fillStyle = '#0E1116';
        ctx.fillRect(0, 0, el.canvas.width, el.canvas.height);

        // Status Header
        ctx.fillStyle = '#161B22';
        ctx.fillRect(0, 0, el.canvas.width, 100);

        ctx.fillStyle = '#F0F6FC';
        ctx.font = 'bold 36px Inter, sans-serif';
        ctx.fillText('10:42', 60, 68);

        ctx.fillStyle = '#00E676';
        ctx.fillRect(el.canvas.width - 140, 42, 60, 26);
        ctx.fillStyle = '#000';
        ctx.font = 'bold 20px monospace';
        ctx.fillText('86%', el.canvas.width - 130, 62);

        // Grid lines
        ctx.strokeStyle = '#21262D';
        ctx.lineWidth = 2;
        for (let i = 200; i < el.canvas.height - 200; i += 200) {
          ctx.beginPath();
          ctx.moveTo(40, i);
          ctx.lineTo(el.canvas.width - 40, i);
          ctx.stroke();
        }

        // Navigation bar
        ctx.fillStyle = '#161B22';
        ctx.fillRect(0, el.canvas.height - 120, el.canvas.width, 120);

        ctx.fillStyle = '#8B949E';
        ctx.font = '32px sans-serif';
        ctx.fillText('◄', el.canvas.width * 0.25, el.canvas.height - 50);
        ctx.fillText('●', el.canvas.width * 0.50, el.canvas.height - 50);
        ctx.fillText('■', el.canvas.width * 0.75, el.canvas.height - 50);
      }
      requestAnimationFrame(render);
    }
    requestAnimationFrame(render);
  }

  // Navigation Toolbar actions
  function setupToolbar() {
    document.getElementById('btnNavBack').onclick = () => sendCommand('GlobalAction', { action: 'BACK' });
    document.getElementById('btnNavHome').onclick = () => sendCommand('GlobalAction', { action: 'HOME' });
    document.getElementById('btnNavRecents').onclick = () => sendCommand('GlobalAction', { action: 'RECENTS' });
    document.getElementById('btnNavNotifs').onclick = () => sendCommand('GlobalAction', { action: 'NOTIFICATIONS' });
    document.getElementById('btnNavSettings').onclick = () => sendCommand('GlobalAction', { action: 'QUICK_SETTINGS' });
    document.getElementById('btnVolDown').onclick = () => sendCommand('Volume', { action: 'DOWN' });
    document.getElementById('btnVolUp').onclick = () => sendCommand('Volume', { action: 'UP' });

    el.qualitySelect.onchange = () => {
      const q = el.qualitySelect.value;
      const stats = q === '1080p' ? '60 FPS | 4000 Kbps' : q === '720p' ? '30 FPS | 2048 Kbps' : '15 FPS | 800 Kbps';
      el.streamStats.textContent = stats;
      logAudit('STREAM', `Video profile switched to ${q} (${stats})`);
    };

    el.btnEmergencyKill.onclick = () => {
      if (confirm('Sever connection and terminate all remote sessions on the phone immediately?')) {
        sendWs({ type: 'EMERGENCY_DISCONNECT', targetDeviceId: state.activeDeviceId });
        logAudit('SECURITY', 'EMERGENCY DISCONNECT EXECUTED. Session wiped.', 'critical');
        el.badge.textContent = 'DISCONNECTED';
        el.badge.className = 'badge badge-danger';
      }
    };

    el.btnRunDiag.onclick = () => {
      sendWs({ type: 'DIAGNOSTICS_RUN', targetDeviceId: state.activeDeviceId });
      logAudit('DIAGNOSTICS', 'Requested remote diagnostic suite execution on target phone.');
    };

    el.btnLaunchPkg.onclick = () => {
      const pkg = el.launchPkgInput.value.trim();
      if (pkg) {
        sendWs({ type: 'APP_COMMAND', action: 'LAUNCH', packageName: pkg, targetDeviceId: state.activeDeviceId });
        logAudit('APP', `Requested launch of package: ${pkg}`);
      }
    };

    el.btnRefreshFiles.onclick = () => {
      sendWs({ type: 'FILE_COMMAND', action: 'LIST', path: state.currentPath, targetDeviceId: state.activeDeviceId });
      logAudit('FILES', `Refreshing directory: ${state.currentPath}`);
    };

    el.btnNewFolder.onclick = () => {
      const name = prompt('Enter new folder name:');
      if (name) {
        sendWs({ type: 'FILE_COMMAND', action: 'CREATE_FOLDER', path: state.currentPath, name: name, targetDeviceId: state.activeDeviceId });
        logAudit('FILES', `Created folder '${name}' in ${state.currentPath}`);
      }
    };

    el.btnClearLogs.onclick = () => { el.auditBox.innerHTML = ''; };
  }

  // File Upload Handling
  function setupFileUpload() {
    el.btnTriggerUpload.onclick = () => el.fileUploadInput.click();

    el.fileUploadInput.onchange = (e) => {
      const file = e.target.files[0];
      if (!file) return;

      const reader = new FileReader();
      el.fileUploadProgress.style.display = 'block';
      el.progressText.textContent = `Uploading ${file.name} (0%)...`;
      el.progressBarFill.style.width = '0%';

      reader.onload = async (evt) => {
        const arrayBuffer = evt.target.result;
        const totalBytes = arrayBuffer.byteLength;
        const chunkSize = 32768; // 32KB
        let offset = 0;

        while (offset < totalBytes) {
          const slice = arrayBuffer.slice(offset, offset + chunkSize);
          const base64 = btoa(String.fromCharCode.apply(null, new Uint8Array(slice)));

          sendWs({
            type: 'FILE_COMMAND',
            action: 'WRITE_CHUNK',
            path: `${state.currentPath}/${file.name}`,
            data: base64,
            append: offset > 0,
            targetDeviceId: state.activeDeviceId
          });

          offset += chunkSize;
          const percent = Math.min(100, Math.round((offset / totalBytes) * 100));
          el.progressBarFill.style.width = `${percent}%`;
          el.progressText.textContent = `Uploading ${file.name} (${percent}%)...`;

          // Small throttle to avoid socket congestion
          await new Promise(r => setTimeout(r, 20));
        }

        el.progressText.textContent = `Upload completed: ${file.name}`;
        logAudit('FILES', `Uploaded file ${file.name} (${totalBytes} bytes) successfully.`, 'security');
        setTimeout(() => { el.fileUploadProgress.style.display = 'none'; }, 2000);
      };

      reader.readAsArrayBuffer(file);
    };
  }

  // Pairing Modal
  function setupModal() {
    el.btnPair.onclick = () => {
      el.pairModal.style.display = 'flex';
      el.inputPairCode.focus();
    };

    el.btnCloseModal.onclick = () => { el.pairModal.style.display = 'none'; };

    el.btnSubmitPair.onclick = () => {
      const code = el.inputPairCode.value.trim();
      if (code.length >= 6) {
        el.pairModal.style.display = 'none';
        logAudit('PAIRING', `Submitting 6-digit pairing code: ${code}...`, 'security');
        sendWs({
          type: 'PAIR_REQUEST',
          pairingCode: code,
          targetDeviceId: state.activeDeviceId,
          operatorFingerprint: state.operatorFingerprint,
          operatorName: state.operatorName,
          operatorLocation: state.operatorLocation
        });
        alert('Pairing challenge sent to phone in Noakhali. Device owner must tap "Authorize Session".');
      }
    };
  }

  // Tab switching
  function setupTabs() {
    document.querySelectorAll('.tab-btn').forEach(btn => {
      btn.onclick = () => {
        document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
        document.querySelectorAll('.tab-pane').forEach(p => p.classList.remove('active'));
        btn.classList.add('active');
        document.getElementById(btn.dataset.tab).classList.add('active');

        // Trigger fetches
        if (btn.dataset.tab === 'tabApps') {
          sendWs({ type: 'APP_COMMAND', action: 'LIST', targetDeviceId: state.activeDeviceId });
        } else if (btn.dataset.tab === 'tabFiles') {
          sendWs({ type: 'FILE_COMMAND', action: 'LIST', path: state.currentPath, targetDeviceId: state.activeDeviceId });
        }
      };
    });
  }

  function renderDevices() {
    el.deviceList.innerHTML = '';
    state.pairedDevices.forEach(d => {
      const card = document.createElement('div');
      card.className = 'device-card active';
      card.innerHTML = `
        <div class="device-card-title">${d.deviceName}</div>
        <div class="device-card-id">${d.deviceId}</div>
        <div class="device-card-meta">${d.location} • <span class="text-mint">${d.battery}% Batt</span></div>
      `;
      el.deviceList.appendChild(card);
    });
  }

  function renderAppList(apps) {
    el.appList.innerHTML = '';
    if (apps.length === 0) {
      el.appList.innerHTML = '<p class="micro-text">No apps loaded.</p>';
      return;
    }
    apps.forEach(app => {
      const row = document.createElement('div');
      row.className = 'app-item';
      row.innerHTML = `
        <div>
          <strong>${app.name}</strong>
          <div class="micro-text mono-pill">${app.pkg}</div>
        </div>
        <button class="btn btn-small btn-secondary" onclick="window.launchApp('${app.pkg}')">Launch</button>
      `;
      el.appList.appendChild(row);
    });
  }
  window.launchApp = (pkg) => {
    sendWs({ type: 'APP_COMMAND', action: 'LAUNCH', packageName: pkg, targetDeviceId: state.activeDeviceId });
    logAudit('APP', `Launched package: ${pkg}`);
  };

  function renderFileList(files) {
    el.fileList.innerHTML = '';
    if (files.length === 0) {
      el.fileList.innerHTML = '<p class="micro-text">Folder is empty.</p>';
      return;
    }
    files.forEach(f => {
      const row = document.createElement('div');
      row.className = 'file-item';
      row.innerHTML = `
        <div>
          <span>${f.isDir ? '📁' : '📄'} ${f.name}</span>
          <div class="micro-text">${f.isDir ? 'Directory' : Math.round(f.size / 1024) + ' KB'}</div>
        </div>
        <button class="btn btn-small btn-danger" onclick="window.deleteFile('${f.path}')">Del</button>
      `;
      el.fileList.appendChild(row);
    });
  }
  window.deleteFile = (path) => {
    if (confirm(`Delete file '${path}' on device?`)) {
      sendWs({ type: 'FILE_COMMAND', action: 'DELETE', path: path, targetDeviceId: state.activeDeviceId });
      logAudit('FILES', `Deleted remote file: ${path}`, 'critical');
    }
  };

  function logAudit(category, message, severity = 'info') {
    const time = new Date().toLocaleTimeString();
    const div = document.createElement('div');
    div.className = `log-line ${severity}`;
    div.textContent = `[${time}] [${category}] ${message}`;
    el.auditBox.appendChild(div);
    el.auditBox.scrollTop = el.auditBox.scrollHeight;
  }

  // Start on DOM ready
  document.addEventListener('DOMContentLoaded', init);
})();
