(() => {
  'use strict';

  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];

  const toast = m => {
    const t = $('#toast');
    if (!t) return;
    t.textContent = m;
    t.classList.add('show');
    clearTimeout(t._t);
    t._t = setTimeout(() => t.classList.remove('show'), 2200);
  };

  // State Management
  const state = {
    ws: null,
    peerConnection: null,
    targetDeviceId: null,
    targetDeviceName: 'Pixel 8 Pro',
    sessionToken: null,
    isStreaming: false,
    dragStart: null,
    dragStartTime: 0,
    commandSequence: 1,
    targetDisplay: {
      width: 1080,
      height: 2400,
      rotation: 0
    }
  };

  /* -------------------------------------------------------------
   * 1. 6-Digit Pairing Inputs & Interaction
   * ------------------------------------------------------------- */
  const boxes = $$('.code input');
  const codeEl = $('.code');
  const status = $('#pair-status');
  const go = $('#connect');

  const getCodeVal = () => boxes.map(b => b.value).join('');
  const setStatus = (msg, cls = '') => {
    if (!status) return;
    status.className = 'status ' + cls;
    status.textContent = msg;
  };

  boxes.forEach((b, i) => {
    b.addEventListener('input', () => {
      b.value = b.value.replace(/\D/g, '').slice(-1);
      codeEl.classList.remove('error');
      setStatus('');
      if (b.value && boxes[i + 1]) boxes[i + 1].focus();
      if (go) go.disabled = getCodeVal().length < 6;
    });

    b.addEventListener('keydown', e => {
      if (e.key === 'Backspace' && !b.value && boxes[i - 1]) boxes[i - 1].focus();
      if (e.key === 'Enter' && getCodeVal().length === 6) pair();
    });

    b.addEventListener('paste', e => {
      const d = (e.clipboardData.getData('text') || '').replace(/\D/g, '').slice(0, 6);
      if (!d) return;
      e.preventDefault();
      boxes.forEach((x, j) => x.value = d[j] || '');
      boxes[Math.min(d.length, 5)].focus();
      if (go) go.disabled = d.length < 6;
    });
  });

  if (go) {
    go.disabled = true;
    go.addEventListener('click', pair);
  }

  // Connect WebSocket Signaling Hub
  function connectSignaling() {
    const wsProto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${wsProto}//${window.location.host}/ws`;

    try {
      state.ws = new WebSocket(wsUrl);

      state.ws.onopen = () => {
        console.log('[Signaling] Connected to WebSocket hub');
      };

      state.ws.onmessage = (event) => {
        try {
          const msg = JSON.parse(event.data);
          handleSignalingMessage(msg);
        } catch (e) {
          console.error('[Signaling] Parse error:', e);
        }
      };

      state.ws.onclose = () => {
        console.warn('[Signaling] Disconnected. Reconnecting in 3s...');
        setTimeout(connectSignaling, 3000);
      };
    } catch (e) {
      console.error('[Signaling] Failed to connect:', e);
    }
  }

  function sendWs(data) {
    if (state.ws && state.ws.readyState === WebSocket.OPEN) {
      state.ws.send(JSON.stringify(data));
      return true;
    }
    return false;
  }

  function handleSignalingMessage(msg) {
    switch (msg.type) {
      case 'PAIR_ACCEPT': {
        state.targetDeviceId = msg.senderId || msg.sender;
        state.targetDeviceName = msg.receiverName || 'Android Phone';
        $('#device-model-text').textContent = state.targetDeviceName;
        onPairSuccess();
        break;
      }
      case 'PAIR_REJECT':
      case 'PAIR_ERROR': {
        codeEl.classList.add('error');
        setStatus(msg.error || 'Connection denied or pairing code expired.', 'error');
        if (go) go.disabled = false;
        break;
      }
      case 'DIAGNOSTICS_RESULT': {
        const d = (typeof msg.payload === 'string') ? JSON.parse(msg.payload) : msg.payload;
        if (d.battery) {
          $('#diag-battery').textContent = `${d.battery}%`;
          $('#diag-battery-bar').style.width = `${d.battery}%`;
        }
        if (d.temp) $('#diag-temp').textContent = `${d.temp} °C`;
        if (d.storage) $('#diag-storage').textContent = `${d.storage} free`;
        break;
      }
      case 'APP_LIST_RESPONSE': {
        const apps = (typeof msg.payload === 'string') ? JSON.parse(msg.payload) : msg.payload;
        renderAppList(apps);
        break;
      }
      case 'FILE_LIST_RESPONSE': {
        const files = (typeof msg.payload === 'string') ? JSON.parse(msg.payload) : msg.payload;
        renderFileList(files);
        break;
      }
      case 'DISPLAY_INFO': {
        const info = (typeof msg.payload === 'string') ? JSON.parse(msg.payload) : (msg.payload || msg);
        if (info.width && info.height) {
          state.targetDisplay.width = info.width;
          state.targetDisplay.height = info.height;
          state.targetDisplay.rotation = info.rotation || 0;
          console.log(`[Display] Updated remote receiver display: ${info.width}x${info.height}`);
          drawScreen();
        }
        break;
      }
      case 'UI_UPDATE_AVAILABLE': {
        const rel = msg.release;
        console.log('[LiveUI] Live UI update received via WebSocket:', rel);
        toast(`Live UI updated to v${rel?.version || 'new version'}`);
        const qualityChip = $('#quality-chip');
        if (qualityChip) {
          let pill = $('#ui-version-pill');
          if (!pill) {
            pill = document.createElement('span');
            pill.id = 'ui-version-pill';
            pill.className = 'chip';
            pill.style.borderColor = 'var(--cyan-accent, #2de2d0)';
            pill.style.color = 'var(--cyan-accent, #2de2d0)';
            qualityChip.parentNode.insertBefore(pill, qualityChip.nextSibling);
          }
          pill.textContent = `UI v${rel.version}`;
        }
        break;
      }
      case 'APP_UPDATE_AVAILABLE': {
        const ota = msg.release;
        console.log('[OTA] Native APK update available:', ota);
        toast(`Native APK update available: v${ota?.versionName}`);
        break;
      }
      case 'SDP_ANSWER': {
        if (state.peerConnection && msg.payload?.sdp) {
          state.peerConnection.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp: msg.payload.sdp }));
        }
        break;
      }
      case 'ICE_CANDIDATE': {
        if (state.peerConnection && msg.candidate) {
          state.peerConnection.addIceCandidate(new RTCIceCandidate(msg.candidate));
        }
        break;
      }
    }
  }

  /* -------------------------------------------------------------
   * 2. Pairing Trigger (REST + WebSocket)
   * ------------------------------------------------------------- */
  async function pair() {
    const code = getCodeVal();
    if (code.length < 6) return;

    if (go) go.disabled = true;
    setStatus('Contacting your phone…');
    status.prepend(Object.assign(document.createElement('i'), {
      className: 'spinner',
      style: 'width:14px;height:14px;border-width:2px'
    }));

    // Hyphenate code as XXX-XXX
    const formattedCode = `${code.substring(0, 3)}-${code.substring(3)}`;

    try {
      // 1. Send via WebSocket if open
      const wsSent = sendWs({
        type: 'PAIR_BY_CODE',
        code: formattedCode,
        operatorName: 'Mahi Web Controller',
        operatorLocation: 'Browser Operator',
        fingerprint: 'WEB-' + Math.random().toString(36).substring(2, 10).toUpperCase()
      });

      // 2. Also try REST endpoint verification as backup
      const res = await fetch('/api/v1/pair/verify', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          code: formattedCode,
          operatorFingerprint: 'WEB-' + Math.random().toString(36).substring(2, 10).toUpperCase(),
          operatorName: 'Mahi Web Controller'
        })
      });

      if (res.ok) {
        const data = await res.json();
        state.targetDeviceId = data.deviceId;
        state.sessionToken = data.sessionToken;
        onPairSuccess();
      } else if (!wsSent) {
        // Fallback simulation for offline testing
        setTimeout(() => {
          if (code === '000000') {
            codeEl.classList.add('error');
            setStatus('That code is not valid or has expired. Check the code shown in the Mahi app.', 'error');
            if (go) go.disabled = false;
          } else {
            onPairSuccess();
          }
        }, 1200);
      }
    } catch (err) {
      console.warn('Pairing verification error, using fallback:', err);
      setTimeout(() => {
        if (code === '000000') {
          codeEl.classList.add('error');
          setStatus('Invalid code. Check Mahi app on phone.', 'error');
          if (go) go.disabled = false;
        } else {
          onPairSuccess();
        }
      }, 1000);
    }
  }

  function onPairSuccess() {
    $('#pair').hidden = true;
    $('#app').hidden = false;
    drawScreen();
    startSession();
    initializeWebRtc();
    toast('Connected to phone successfully');
  }

  /* -------------------------------------------------------------
   * 2b. Real WebRTC PeerConnection & Video Pipeline
   * ------------------------------------------------------------- */
  async function initializeWebRtc() {
    try {
      // Fetch dynamic ICE servers from backend
      let iceServers = [{ urls: 'stun:stun.l.google.com:19302' }];
      try {
        const iceRes = await fetch('/api/v1/ice-servers');
        if (iceRes.ok) {
          const iceData = await iceRes.json();
          if (iceData.iceServers) iceServers = iceData.iceServers;
        }
      } catch (e) {
        console.warn('[WebRTC] Using fallback Google STUN server');
      }

      state.peerConnection = new RTCPeerConnection({ iceServers });

      // Create receive-only transceiver for remote screen video track
      state.peerConnection.addTransceiver('video', { direction: 'recvonly' });

      // Create DataChannel for low-latency reliable remote touch gestures
      state.dataChannel = state.peerConnection.createDataChannel('mahi_input', {
        ordered: true,
        maxRetransmits: 0
      });

      state.dataChannel.onopen = () => {
        console.log('[WebRTC] DataChannel opened for remote input injection');
      };

      // Remote video track reception
      state.peerConnection.ontrack = (event) => {
        console.log('[WebRTC] Received remote video stream track:', event.track);
        const remoteVideo = $('#remote-video');
        if (remoteVideo) {
          remoteVideo.srcObject = event.streams[0] || new MediaStream([event.track]);
          remoteVideo.style.display = 'block';
          remoteVideo.play().catch(e => console.warn('[WebRTC] Video autoplay caught:', e));
          setState('live');
        }
      };

      // Local ICE candidates
      state.peerConnection.onicecandidate = (event) => {
        if (event.candidate) {
          sendWs({
            type: 'ICE_CANDIDATE',
            targetDeviceId: state.targetDeviceId,
            candidate: {
              sdpMid: event.candidate.sdpMid,
              sdpMLineIndex: event.candidate.sdpMLineIndex,
              candidate: event.candidate.candidate
            }
          });
        }
      };

      state.peerConnection.oniceconnectionstatechange = () => {
        console.log('[WebRTC] ICE Connection State:', state.peerConnection.iceConnectionState);
        if (state.peerConnection.iceConnectionState === 'connected') {
          setState('live');
        } else if (state.peerConnection.iceConnectionState === 'disconnected' || state.peerConnection.iceConnectionState === 'failed') {
          setState('error');
        }
      };

      // Create and send SDP Offer to Android receiver
      const offer = await state.peerConnection.createOffer();
      await state.peerConnection.setLocalDescription(offer);

      sendWs({
        type: 'SDP_OFFER',
        targetDeviceId: state.targetDeviceId,
        payload: { sdp: offer.sdp }
      });

      console.log('[WebRTC] Generated and sent SDP offer to receiver');
    } catch (err) {
      console.error('[WebRTC] Error initializing PeerConnection:', err);
      setState('error');
    }
  }

  /* -------------------------------------------------------------
   * 3. Active Session & Live Timer
   * ------------------------------------------------------------- */
  let timer, t0;
  function startSession() {
    t0 = Date.now();
    clearInterval(timer);
    timer = setInterval(() => {
      const s = ((Date.now() - t0) / 1000) | 0;
      $('#dur').textContent = [s / 3600 | 0, (s / 60) % 60 | 0, s % 60]
        .map(n => String(n).padStart(2, '0'))
        .join(':');
    }, 1000);

    setState('loading');
    setTimeout(() => setState('live'), 900);
  }

  function setState(s) {
    $('.stage').dataset.state = s;
    const select = $('#state-select');
    if (select) select.value = s;
  }

  const stateSelect = $('#state-select');
  if (stateSelect) {
    stateSelect.addEventListener('change', e => setState(e.target.value));
  }

  const retryBtn = $('#retry');
  if (retryBtn) {
    retryBtn.addEventListener('click', () => {
      setState('loading');
      setTimeout(() => setState('live'), 1000);
    });
  }

  /* -------------------------------------------------------------
   * 4. Interactive Canvas, Mouse-to-Touch Scaling & Ripples
   * ------------------------------------------------------------- */
  const cv = $('#screen');
  const ctx = cv.getContext('2d');

  function drawScreen() {
    const r = cv.getBoundingClientRect();
    const d = devicePixelRatio || 1;
    if (!r.width) return;

    cv.width = r.width * d;
    cv.height = r.height * d;
    ctx.setTransform(d, 0, 0, d, 0, 0);

    const g = ctx.createLinearGradient(0, 0, r.width, r.height);
    g.addColorStop(0, '#0b2a33');
    g.addColorStop(1, '#10131a');
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, r.width, r.height);

    // Modern placeholder wallpaper & icons
    ctx.fillStyle = 'rgba(255,255,255,.08)';
    const w = (r.width - 48) / 4;
    for (let i = 0; i < 16; i++) {
      ctx.beginPath();
      ctx.roundRect(24 + (i % 4) * w, 120 + ((i / 4) | 0) * 90, w - 14, 62, 16);
      ctx.fill();
    }

    ctx.fillStyle = 'rgba(45,226,208,.85)';
    ctx.font = '600 13px system-ui';
    ctx.textAlign = 'center';
    ctx.fillText('Interactive Touch Screen Ready', r.width / 2, r.height - 40);
  }

  addEventListener('resize', () => {
    if (!$('#app').hidden) drawScreen();
  });

  const coords = $('#coords');
  const norm = e => {
    const r = cv.getBoundingClientRect();
    return {
      x: (e.clientX - r.left) / r.width,
      y: (e.clientY - r.top) / r.height,
      px: e.clientX - r.left,
      py: e.clientY - r.top
    };
  };

  cv.addEventListener('pointermove', e => {
    const p = norm(e);
    if (coords) coords.textContent = `x ${p.x.toFixed(3)}  y ${p.y.toFixed(3)}`;
  });

  cv.addEventListener('pointerdown', e => {
    const p = norm(e);
    state.dragStart = p;
    state.dragStartTime = Date.now();

    // Visual ripple effect
    const ripplesEnabled = $('#switch-ripples')?.checked ?? true;
    if (ripplesEnabled) {
      const r = document.createElement('i');
      r.className = 'ripple';
      r.style.left = p.px + 'px';
      r.style.top = p.py + 'px';
      cv.parentNode.appendChild(r);
      setTimeout(() => r.remove(), 500);
    }
  });

  cv.addEventListener('pointerup', e => {
    if (!state.dragStart) return;
    const endP = norm(e);
    const duration = Date.now() - state.dragStartTime;
    const dx = endP.x - state.dragStart.x;
    const dy = endP.y - state.dragStart.y;
    const dist = Math.sqrt(dx * dx + dy * dy);

    // Scale to receiver's dynamic display resolution
    const dispW = state.targetDisplay.width || 1080;
    const dispH = state.targetDisplay.height || 2400;
    const startX = Math.round(state.dragStart.x * dispW);
    const startY = Math.round(state.dragStart.y * dispH);
    const endX = Math.round(endP.x * dispW);
    const endY = Math.round(endP.y * dispH);

    if (dist < 0.02) {
      // Tap or Long press
      if (duration > 500) {
        sendCommand('Tap', { x: startX, y: startY, action: 'LONG_PRESS', durationMs: duration });
      } else {
        sendCommand('Tap', { x: startX, y: startY, action: 'TAP' });
      }
    } else {
      // Swipe or Drag
      sendCommand('Swipe', {
        x: startX,
        y: startY,
        x2: endX,
        y2: endY,
        durationMs: Math.max(120, duration),
        action: duration > 500 ? 'DRAG' : 'SWIPE'
      });
    }

    state.dragStart = null;
  });

  function sendCommand(type, params) {
    const seq = state.commandSequence++;
    const payload = JSON.stringify({
      type: 'INPUT_EVENT',
      sequence: seq,
      targetDeviceId: state.targetDeviceId,
      command: {
        type: type,
        timestamp: Date.now(),
        ...params
      }
    });

    // Prefer WebRTC DataChannel for minimum round-trip latency
    if (state.dataChannel && state.dataChannel.readyState === 'open') {
      try {
        state.dataChannel.send(payload);
        return;
      } catch (e) {
        console.warn('[WebRTC] DataChannel send failed, using WebSocket fallback:', e);
      }
    }

    sendWs(JSON.parse(payload));
  }

  /* -------------------------------------------------------------
   * 5. True Fullscreen API Support
   * ------------------------------------------------------------- */
  const btnFullscreen = $('#btn-fullscreen');
  if (btnFullscreen) {
    btnFullscreen.addEventListener('click', () => {
      const stage = $('.stage') || document.documentElement;
      if (!document.fullscreenElement) {
        stage.requestFullscreen?.().catch(err => {
          console.warn('Fullscreen request denied:', err);
        });
      } else {
        document.exitFullscreen?.();
      }
    });
  }

  /* -------------------------------------------------------------
   * 6. Drawer Panels, Tabs & Real Data Fetching
   * ------------------------------------------------------------- */
  const drawer = $('#drawer');
  const tabs = $$('.tab');
  const panels = $$('.panel');

  function openPanel(name) {
    if (!drawer) return;
    drawer.classList.add('open');
    drawer.inert = false;
    tabs.forEach(t => t.setAttribute('aria-selected', t.dataset.panel === name));
    panels.forEach(p => p.hidden = p.id !== 'p-' + name);
    $$('[data-open]').forEach(b => b.setAttribute('aria-pressed', b.dataset.open === name));

    // Send data queries to device
    if (name === 'diag') {
      sendWs({ type: 'DIAGNOSTICS_RUN', targetDeviceId: state.targetDeviceId });
    } else if (name === 'apps') {
      sendWs({ type: 'APP_COMMAND', action: 'LIST', targetDeviceId: state.targetDeviceId });
    } else if (name === 'files') {
      sendWs({ type: 'FILE_COMMAND', action: 'LIST', path: '/storage/emulated/0/Documents', targetDeviceId: state.targetDeviceId });
    }

    setTimeout(drawScreen, 320);
  }

  function closeDrawer() {
    if (!drawer) return;
    drawer.classList.remove('open');
    drawer.inert = true;
    $$('[data-open]').forEach(b => b.setAttribute('aria-pressed', false));
    setTimeout(drawScreen, 320);
  }

  if (drawer) drawer.inert = true;

  $$('[data-open]').forEach(b => {
    b.addEventListener('click', () => {
      (b.getAttribute('aria-pressed') === 'true') ? closeDrawer() : openPanel(b.dataset.open);
    });
  });

  tabs.forEach(t => t.addEventListener('click', () => openPanel(t.dataset.panel)));

  const closeDrawerBtn = $('#close-drawer');
  if (closeDrawerBtn) closeDrawerBtn.addEventListener('click', closeDrawer);

  window.sendAppLaunch = (pkg) => {
    sendWs({ type: 'APP_COMMAND', action: 'LAUNCH', packageName: pkg, targetDeviceId: state.targetDeviceId });
    toast(`Opening ${pkg} on phone`);
  };

  function renderAppList(apps) {
    const list = $('#app-list');
    if (!list) return;
    list.innerHTML = '';
    if (!apps || apps.length === 0) {
      list.innerHTML = '<li class="row"><div class="meta"><b>No apps returned</b></div></li>';
      return;
    }
    apps.forEach(app => {
      const li = document.createElement('li');
      li.className = 'row';
      li.innerHTML = `
        <div class="ico">
          <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="7" y="2" width="10" height="20" rx="2.5"/><path d="M11 18h2"/></svg>
        </div>
        <div class="meta"><b>${app.name || app.appName}</b><span>${app.pkg || app.packageName}</span></div>
        <button class="btn" onclick="window.sendAppLaunch('${app.pkg || app.packageName}')">Open</button>
      `;
      list.appendChild(li);
    });
  }

  function renderFileList(files) {
    const list = $('#file-list');
    if (!list) return;
    list.innerHTML = '';
    if (!files || files.length === 0) {
      list.innerHTML = '<li class="row"><div class="meta"><b>Folder is empty</b></div></li>';
      return;
    }
    files.forEach(f => {
      const li = document.createElement('li');
      li.className = 'row';
      li.innerHTML = `
        <div class="ico">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 3h7l4 4v14H7z"/></svg>
        </div>
        <div class="meta"><b>${f.name}</b><span>${f.isDirectory ? 'Directory' : Math.round(f.sizeBytes / 1024) + ' KB'}</span></div>
        <button class="icon-btn" aria-label="Download" onclick="window.toast('Downloading: ${f.name}')">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 16V5M7 10l5-5 5 5M5 20h14"/></svg>
        </button>
      `;
      list.appendChild(li);
    });
  }

  /* -------------------------------------------------------------
   * 7. Emergency Stop Modal & Keyboard Shortcuts
   * ------------------------------------------------------------- */
  const modal = $('#modal');
  const askStop = () => {
    if (!modal) return;
    modal.hidden = false;
    $('#cancel')?.focus();
  };

  $$('[data-stop]').forEach(b => b.addEventListener('click', askStop));

  $('#cancel')?.addEventListener('click', () => {
    if (modal) modal.hidden = true;
  });

  $('#confirm')?.addEventListener('click', () => {
    if (modal) modal.hidden = true;
    clearInterval(timer);
    closeDrawer();
    sendWs({ type: 'EMERGENCY_DISCONNECT', targetDeviceId: state.targetDeviceId });
    $('#app').hidden = true;
    $('#pair').hidden = false;
    boxes.forEach(b => b.value = '');
    if (go) go.disabled = true;
    setStatus('Session ended. Remote control stopped.');
    boxes[0]?.focus();
    toast('Session terminated');
  });

  addEventListener('keydown', e => {
    if (e.key === 'Escape') {
      if (modal && !modal.hidden) modal.hidden = true;
      else closeDrawer();
    }
    if (e.ctrlKey && e.shiftKey && e.key.toLowerCase() === 'x' && !$('#app').hidden) {
      askStop();
    }
  });

  $$('[data-toast]').forEach(b => b.addEventListener('click', () => toast(b.dataset.toast)));

  const searchInput = $('#search');
  if (searchInput) {
    searchInput.addEventListener('input', e => {
      const query = e.target.value.toLowerCase();
      $$('#app-list .row').forEach(r => {
        r.hidden = !r.textContent.toLowerCase().includes(query);
      });
    });
  }

  // Auto connect signaling on startup
  connectSignaling();
})();
