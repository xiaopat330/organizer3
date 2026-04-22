// Background thumbnails — tiny always-present status chip (bottom-left).
// Shows whether the background worker is enabled, last-generated info, and
// lets the user toggle with one click. Orthogonal to the utility task lock;
// persists across restarts via the server-side state file.

const POLL_MS = 15000;

let chipEl = null;
let pollTimer = null;

export function installBgThumbnailChip() {
  if (chipEl) return;
  chipEl = document.createElement('button');
  chipEl.type = 'button';
  chipEl.id = 'bg-thumbnails-chip';
  chipEl.className = 'bg-thumbnails-chip';
  chipEl.addEventListener('click', toggle);
  document.body.appendChild(chipEl);
  refresh();
  pollTimer = setInterval(refresh, POLL_MS);
}

async function refresh() {
  try {
    const res = await fetch('/api/bg-thumbnails/status');
    if (!res.ok) return;
    render(await res.json());
  } catch { /* network blips ignored; next poll retries */ }
}

async function toggle() {
  chipEl.classList.add('pending');
  try {
    const res = await fetch('/api/bg-thumbnails/toggle', { method: 'POST' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    render(await res.json());
  } catch (err) {
    console.error('Failed to toggle background thumbnails', err);
  } finally {
    chipEl.classList.remove('pending');
  }
}

function render(s) {
  if (!chipEl) return;
  chipEl.classList.toggle('on',  !!s.enabled);
  chipEl.classList.toggle('off', !s.enabled);

  const meta = s.enabled
      ? `${s.totalGenerated || 0} this session`
      : 'paused';

  chipEl.innerHTML = `
    <span class="bg-chip-dot"></span>
    <span class="bg-chip-label">Thumbnails</span>
    <span class="bg-chip-state">${s.enabled ? 'ON' : 'OFF'}</span>
    <span class="bg-chip-meta">· ${meta}</span>
  `;

  const tip = [
    `Background thumbnails: ${s.enabled ? 'enabled' : 'disabled'}`,
    `Generated this session: ${s.totalGenerated || 0}`,
    `Evicted this session:   ${s.totalEvicted || 0}`,
    `Queue size (last cycle): ${s.queueSize || 0}`,
  ];
  if (s.lastGeneratedCode) {
    tip.push(`Last generated: ${s.lastGeneratedCode} (${ago(s.lastGeneratedAgoMs)} ago)`);
  }
  tip.push('', 'Click to toggle.');
  chipEl.title = tip.join('\n');
}

function ago(ms) {
  if (ms == null) return '?';
  const s = Math.floor(ms / 1000);
  if (s < 60) return s + 's';
  const m = Math.floor(s / 60);
  if (m < 60) return m + 'm';
  const h = Math.floor(m / 60);
  return h + 'h';
}
