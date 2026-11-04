(() => {
  const $ = (s, r = document) => r.querySelector(s), $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const toast = m => { const t = $('#toast'); t.textContent = m; t.classList.add('show'); clearTimeout(t._t); t._t = setTimeout(() => t.classList.remove('show'), 2200); };

  /* Pairing */
  const boxes = $$('.code input'), codeEl = $('.code'), status = $('#pair-status'), go = $('#connect');
  const val = () => boxes.map(b => b.value).join('');
  const setStatus = (msg, cls = '') => { status.className = 'status ' + cls; status.textContent = msg; };
  boxes.forEach((b, i) => {
    b.addEventListener('input', () => { b.value = b.value.replace(/\D/g, '').slice(-1); codeEl.classList.remove('error'); setStatus(''); if (b.value && boxes[i + 1]) boxes[i + 1].focus(); go.disabled = val().length < 6; });
    b.addEventListener('keydown', e => { if (e.key === 'Backspace' && !b.value && boxes[i - 1]) boxes[i - 1].focus(); if (e.key === 'Enter' && val().length === 6) pair(); });
    b.addEventListener('paste', e => { const d = (e.clipboardData.getData('text') || '').replace(/\D/g, '').slice(0, 6); if (!d) return; e.preventDefault(); boxes.forEach((x, j) => x.value = d[j] || ''); boxes[Math.min(d.length, 5)].focus(); go.disabled = d.length < 6; });
  });
  go.disabled = true; go.addEventListener('click', pair);
  /* DEMO: replace with real signalling. "000000" simulates a failed pairing. */
  function pair() {
    go.disabled = true; setStatus('Contacting your phone…');
    status.prepend(Object.assign(document.createElement('i'), { className: 'spinner', style: 'width:14px;height:14px;border-width:2px' }));
    setTimeout(() => {
      if (val() === '000000') { codeEl.classList.add('error'); setStatus('That code is not valid or has expired. Check the code shown in the Mahi app.', 'error'); go.disabled = false; return; }
      $('#pair').hidden = true; $('#app').hidden = false; drawScreen(); startSession();
    }, 1400);
  }

  /* Session */
  let timer, t0;
  function startSession() { t0 = Date.now(); clearInterval(timer); timer = setInterval(() => { const s = ((Date.now() - t0) / 1000) | 0; $('#dur').textContent = [s / 3600 | 0, s / 60 % 60 | 0, s % 60].map(n => String(n).padStart(2, '0')).join(':'); }, 1000); setState('loading'); setTimeout(() => setState('live'), 900); }
  function setState(s) { $('.stage').dataset.state = s; $('#state-select').value = s; }
  $('#state-select').addEventListener('change', e => setState(e.target.value));
  $('#retry').addEventListener('click', () => { setState('loading'); setTimeout(() => setState('live'), 1000); });

  /* Canvas placeholder + pointer */
  const cv = $('#screen'), ctx = cv.getContext('2d');
  function drawScreen() {
    const r = cv.getBoundingClientRect(), d = devicePixelRatio || 1; if (!r.width) return;
    cv.width = r.width * d; cv.height = r.height * d; ctx.setTransform(d, 0, 0, d, 0, 0);
    const g = ctx.createLinearGradient(0, 0, r.width, r.height); g.addColorStop(0, '#0b2a33'); g.addColorStop(1, '#10131a');
    ctx.fillStyle = g; ctx.fillRect(0, 0, r.width, r.height);
    ctx.fillStyle = 'rgba(255,255,255,.08)'; const w = (r.width - 48) / 4;
    for (let i = 0; i < 16; i++) { ctx.beginPath(); ctx.roundRect(24 + (i % 4) * w, 120 + (i / 4 | 0) * 90, w - 14, 62, 16); ctx.fill(); }
    ctx.fillStyle = 'rgba(255,255,255,.5)'; ctx.font = '600 13px system-ui'; ctx.textAlign = 'center'; ctx.fillText('WebRTC video renders here', r.width / 2, r.height - 40);
  }
  addEventListener('resize', () => !$('#app').hidden && drawScreen());
  const coords = $('#coords');
  const norm = e => { const r = cv.getBoundingClientRect(); return { x: (e.clientX - r.left) / r.width, y: (e.clientY - r.top) / r.height, px: e.clientX - r.left, py: e.clientY - r.top }; };
  cv.addEventListener('pointermove', e => { const p = norm(e); coords.textContent = `x ${p.x.toFixed(3)}  y ${p.y.toFixed(3)}`; });
  cv.addEventListener('pointerdown', e => {
    const p = norm(e), r = document.createElement('i'); r.className = 'ripple'; r.style.left = p.px + 'px'; r.style.top = p.py + 'px'; cv.parentNode.appendChild(r); setTimeout(() => r.remove(), 500);
    /* HOOK: send normalized tap over the data channel, e.g. channel.send(JSON.stringify({t:'tap',x:p.x,y:p.y})) */
  });

  /* Drawer + tabs */
  const drawer = $('#drawer'), tabs = $$('.tab'), panels = $$('.panel');
  function openPanel(name) {
    drawer.classList.add('open'); drawer.inert = false;
    tabs.forEach(t => t.setAttribute('aria-selected', t.dataset.panel === name));
    panels.forEach(p => p.hidden = p.id !== 'p-' + name);
    $$('[data-open]').forEach(b => b.setAttribute('aria-pressed', b.dataset.open === name));
    setTimeout(drawScreen, 320);
  }
  function closeDrawer() { drawer.classList.remove('open'); drawer.inert = true; $$('[data-open]').forEach(b => b.setAttribute('aria-pressed', false)); setTimeout(drawScreen, 320); }
  drawer.inert = true;
  $$('[data-open]').forEach(b => b.addEventListener('click', () => (b.getAttribute('aria-pressed') === 'true') ? closeDrawer() : openPanel(b.dataset.open)));
  tabs.forEach(t => t.addEventListener('click', () => openPanel(t.dataset.panel)));
  $('#close-drawer').addEventListener('click', closeDrawer);

  /* Emergency stop */
  const modal = $('#modal');
  const askStop = () => { modal.hidden = false; $('#cancel').focus(); };
  $$('[data-stop]').forEach(b => b.addEventListener('click', askStop));
  $('#cancel').addEventListener('click', () => modal.hidden = true);
  $('#confirm').addEventListener('click', () => { modal.hidden = true; clearInterval(timer); closeDrawer(); $('#app').hidden = true; $('#pair').hidden = false; boxes.forEach(b => b.value = ''); go.disabled = true; setStatus('Session ended. Remote control stopped.'); boxes[0].focus(); });
  addEventListener('keydown', e => { if (e.key === 'Escape') { if (!modal.hidden) modal.hidden = true; else closeDrawer(); } if (e.ctrlKey && e.shiftKey && e.key.toLowerCase() === 'x' && !$('#app').hidden) askStop(); });
  $$('[data-toast]').forEach(b => b.addEventListener('click', () => toast(b.dataset.toast)));
  $('#search').addEventListener('input', e => $$('#app-list .row').forEach(r => r.hidden = !r.textContent.toLowerCase().includes(e.target.value.toLowerCase())));
})();
