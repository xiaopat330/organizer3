// modules/chrome/status-bar.js
// Polls sync + translation APIs and rewrites .app-status on every v2 page.

const SYNC_INTERVAL_MS        = 10_000;
const TRANSLATION_INTERVAL_MS = 15_000;

const SYNC_ICON = '<svg viewBox="0 0 24 24"><path d="M3 12a9 9 0 0 1 15-6.7L21 8"/><polyline points="21 3 21 8 16 8"/></svg>';

const SYNC_TASK_IDS = new Set(['volume.sync', 'volume.sync_coherent', 'volume.clean_stale_locations']);

export function mountStatusBar() {
  const footer = document.querySelector('.app-status');
  if (!footer) return;

  let syncState  = { dot: 'ok',   text: 'sync …' };
  let transState = { dot: 'idle', text: 'translation …' };

  function render() {
    footer.innerHTML =
      `<span class="status-item">` +
        `<span class="status-dot ${syncState.dot}"></span>` +
        SYNC_ICON +
        `<b>sync</b> ${syncState.text}` +
      `</span>` +
      `<span class="status-item">` +
        `<span class="status-dot ${transState.dot}"></span>` +
        `<b>translation</b> ${transState.text}` +
      `</span>` +
      `<span class="status-spacer"></span>`;
  }

  async function pollSync() {
    try {
      const [activeRes, volumesRes] = await Promise.all([
        fetch('/api/utilities/active',  { cache: 'no-cache' }),
        fetch('/api/utilities/volumes', { cache: 'no-cache' }),
      ]);
      const active  = activeRes.ok  ? await activeRes.json()  : null;
      const volumes = volumesRes.ok ? await volumesRes.json() : null;

      const isSync = active?.active && SYNC_TASK_IDS.has(active.taskId);

      if (isSync) {
        syncState = { dot: 'live', text: 'running' };
      } else if (volumes && volumes.length) {
        const latest = volumes.reduce((best, v) =>
          (!best.lastSyncedAt || (v.lastSyncedAt && v.lastSyncedAt > best.lastSyncedAt)) ? v : best,
          volumes[0]);
        const ageMs  = latest.lastSyncedAt
          ? Date.now() - new Date(latest.lastSyncedAt).getTime()
          : Infinity;
        const ageSec = Math.floor(ageMs / 1000);
        const timeStr = ageSec < 60    ? ageSec + 's'
                      : ageSec < 3600  ? Math.floor(ageSec / 60)   + 'm'
                      :                  Math.floor(ageSec / 3600)  + 'h';
        const dot = ageMs > 86_400_000 ? 'warn' : 'ok';
        syncState = { dot, text: `ok · ${latest.id} · ${timeStr}` };
      }
    } catch { /* keep last-known state — no console spam */ }
    render();
  }

  async function pollTranslation() {
    try {
      const res = await fetch('/api/translation/stats', { cache: 'no-cache' });
      if (!res.ok) return;
      const stats   = await res.json();
      const inFlight = stats.queueInFlight || 0;
      const pending  = (stats.queuePending  || 0) + inFlight;
      const dot  = inFlight > 0 ? 'live'
                 : pending > 100 ? 'warn'
                 : pending > 0   ? 'warn'
                 : 'idle';
      const text = pending === 0 ? 'caught up' : `${pending} pending`;
      transState = { dot, text };
    } catch { /* keep last-known state */ }
    render();
  }

  render(); // immediate placeholder render
  pollSync();
  pollTranslation();
  setInterval(pollSync,        SYNC_INTERVAL_MS);
  setInterval(pollTranslation, TRANSLATION_INTERVAL_MS);
}
