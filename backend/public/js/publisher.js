(() => {
  'use strict';

  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];

  const logBox = $('#log-console');
  function log(msg) {
    const time = new Date().toLocaleTimeString();
    if (logBox) {
      logBox.textContent = `[${time}] ${msg}\n` + logBox.textContent;
    }
  }

  async function fetchStatus() {
    try {
      // 1. Fetch Latest UI Release
      const relRes = await fetch('/api/v1/ui-updates/latest');
      if (relRes.ok) {
        const rel = await relRes.json();
        $('#kpi-version').textContent = `v${rel.version}`;
        $('#kpi-build').textContent = `Build: ${rel.buildId}`;
      }

      // 2. Fetch Devices
      const devRes = await fetch('/api/v1/devices');
      if (devRes.ok) {
        const devData = await devRes.json();
        const count = devData.devices?.length || 0;
        $('#kpi-devices').textContent = count.toString();
      }

      // 3. Fetch History
      loadHistory();
    } catch (e) {
      console.error('[Publisher] Status fetch failed:', e);
    }
  }

  async function loadHistory() {
    try {
      const res = await fetch('/api/v1/ui-releases/history');
      if (!res.ok) return;
      const data = await res.json();
      const activeVer = data.activeVersion;
      const tbody = $('#history-body');
      if (!tbody) return;

      tbody.innerHTML = '';
      if (!data.releases || data.releases.length === 0) {
        tbody.innerHTML = '<tr><td colspan="7" style="text-align:center;color:#8b949e">No release history found.</td></tr>';
        return;
      }

      data.releases.forEach(rel => {
        const isActive = rel.version === activeVer;
        const tr = document.createElement('tr');
        tr.innerHTML = `
          <td><b style="font-family:var(--font-mono);font-size:13px">${rel.version}</b></td>
          <td><span style="font-family:var(--font-mono);color:#8b949e">${rel.buildId}</span></td>
          <td>
            <span class="status-pill ${isActive ? 'status-active' : 'status-archived'}">
              ${isActive ? '● ACTIVE' : 'ARCHIVED'}
            </span>
          </td>
          <td>${rel.releaseNotes}</td>
          <td><code style="font-size:10px;color:#00e5ff">${rel.checksumSha256.substring(0, 16)}...</code></td>
          <td style="color:#8b949e;font-size:11px">${new Date(rel.releaseDate).toLocaleString()}</td>
          <td>
            ${!isActive ? `<button class="btn" style="padding:4px 10px;font-size:11px" onclick="window.rollbackTo('${rel.version}')">Activate</button>` : '<span style="color:#00e676;font-size:11px;font-weight:600">Current</span>'}
          </td>
        `;
        tbody.appendChild(tr);
      });
    } catch (e) {
      console.error('[Publisher] Failed to load history:', e);
    }
  }

  async function publishUiRelease() {
    const version = $('#rel-version').value.trim();
    const buildId = $('#rel-build').value.trim();
    const releaseNotes = $('#rel-notes').value.trim();
    const adminKey = $('#admin-key').value.trim();
    const statusEl = $('#publish-status');

    if (!version || !buildId || !releaseNotes) {
      alert('Please fill in version, build ID, and release notes.');
      return;
    }

    const selectedComponents = $$('.checkbox-group input:checked').map(cb => cb.value);

    statusEl.textContent = 'Publishing release...';
    statusEl.style.color = '#00e5ff';
    log(`Initiating live UI publication for v${version} (${buildId})...`);

    try {
      const res = await fetch('/api/v1/admin/ui-releases', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Admin-Key': adminKey
        },
        body: JSON.stringify({
          version,
          buildId,
          releaseNotes,
          componentsUpdated: selectedComponents,
          isMandatory: false
        })
      });

      const data = await res.json();
      if (!res.ok) {
        statusEl.textContent = `Publish failed: ${data.error || 'Server error'}`;
        statusEl.style.color = '#f87171';
        log(`[ERROR] Release failed: ${data.error}`);
        return;
      }

      statusEl.textContent = `✓ Release v${version} live! Broadcasted to phones.`;
      statusEl.style.color = '#00e676';
      log(`[SUCCESS] Release v${version} published! Signature: ${data.release?.signature.substring(0, 16)}...`);
      log(`[SIGNALING] Sent UI_UPDATE_AVAILABLE event to all connected devices.`);

      fetchStatus();

      // Bump version for next iteration
      const parts = version.split('.');
      if (parts.length === 3) {
        $('#rel-version').value = `${parts[0]}.${parts[1]}.${parseInt(parts[2], 10) + 1}`;
        $('#rel-build').value = `MAHI-BUILD-${new Date().toISOString().slice(0, 10).replace(/-/g, '')}-${Math.floor(Math.random() * 90 + 10)}`;
      }
    } catch (e) {
      statusEl.textContent = `Network error: ${e.message}`;
      statusEl.style.color = '#f87171';
      log(`[ERROR] Network error during publish: ${e.message}`);
    }
  }

  async function rollbackTo(targetVersion) {
    const adminKey = $('#admin-key').value.trim();
    const statusEl = $('#publish-status');

    statusEl.textContent = `Rolling back to v${targetVersion || 'previous'}...`;
    statusEl.style.color = '#fbbf24';
    log(`Requesting rollback to v${targetVersion || 'previous'}...`);

    try {
      const res = await fetch('/api/v1/admin/ui-releases/rollback', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Admin-Key': adminKey
        },
        body: JSON.stringify({ targetVersion })
      });

      const data = await res.json();
      if (!res.ok) {
        statusEl.textContent = `Rollback failed: ${data.error}`;
        statusEl.style.color = '#f87171';
        log(`[ERROR] Rollback failed: ${data.error}`);
        return;
      }

      statusEl.textContent = `✓ Rolled back to v${data.activeRelease?.version}!`;
      statusEl.style.color = '#00e676';
      log(`[SUCCESS] Rolled back to v${data.activeRelease?.version}. Broadcasted update to clients.`);
      fetchStatus();
    } catch (e) {
      statusEl.textContent = `Rollback error: ${e.message}`;
      statusEl.style.color = '#f87171';
    }
  }

  window.rollbackTo = rollbackTo;

  $('#btn-publish-ui')?.addEventListener('click', publishUiRelease);
  $('#btn-rollback-ui')?.addEventListener('click', () => rollbackTo());

  fetchStatus();
  setInterval(fetchStatus, 8000);
})();
