/* ─────────────────────────────────────────────────────────────────────
   duplicates/inspect-modal.js — full-page inspect overlay with
   video player + theater mode. Mirrors legacy openInspectModal().
   ───────────────────────────────────────────────────────────────────── */

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;',
  }[c]));
}

function fmtBytes(b) {
  if (b >= 1e9) return (b / 1e9).toFixed(1) + ' GB';
  if (b >= 1e6) return (b / 1e6).toFixed(0) + ' MB';
  return (b / 1e3).toFixed(0) + ' KB';
}

function parseNasPath(nasPath) {
  if (!nasPath) return null;
  const s = nasPath.replace(/\\/g, '/');
  const m = s.match(/^(\/\/[^/]+\/[^/]+)(\/(.*))?$/);
  if (!m) return { volume: s, middle: '', titleFolder: '' };
  const volume = m[1];
  const rest   = m[3] || '';
  const slash  = rest.lastIndexOf('/');
  if (slash < 0) return { volume, middle: '', titleFolder: rest };
  return { volume, middle: '/' + rest.substring(0, slash), titleFolder: rest.substring(slash + 1) };
}

function videoTagPillsHtml(v) {
  const fn      = v.filename || '';
  const fnLower = fn.toLowerCase();
  const dot     = fn.lastIndexOf('.');
  const ext     = dot >= 0 ? fn.substring(dot + 1).toUpperCase() : null;
  const isHevc  = fnLower.includes('-h265');
  const is4k    = fnLower.includes('_4k') || fnLower.includes('-4k');
  let html = '';
  if (v.fileSize) html += `<span class="dup-chip">${fmtBytes(v.fileSize)}</span>`;
  if (ext)        html += `<span class="dup-chip dup-chip-ext">${esc(ext)}</span>`;
  if (isHevc)     html += `<span class="dup-chip dup-chip-hevc">HEVC</span>`;
  if (is4k)       html += `<span class="dup-chip dup-chip-4k">4K</span>`;
  return html;
}

function buildVideoSection(v) {
  const section = document.createElement('div');
  section.className = 'dup-inspect-video-section';
  const filename = v.filename || v.path?.split('/').pop() || `Video ${v.id}`;
  section.innerHTML = `
    <div class="dup-inspect-filename">${esc(filename)}</div>
    <div class="dup-inspect-pills">${videoTagPillsHtml(v)}</div>
    <div class="dup-inspect-meta" id="dup-inspect-meta-${v.id}">…</div>
    <div class="dup-inspect-player-wrap" id="dup-inspect-wrap-${v.id}">
      <video class="dup-inspect-player" id="dup-inspect-player-${v.id}"
             controls preload="none" src="/api/stream/${v.id}"></video>
      <button class="btn sm dup-inspect-theater-btn" type="button">Theater</button>
    </div>
  `;

  section.querySelector('.dup-inspect-theater-btn').addEventListener('click', () => {
    const wrap = section.querySelector(`#dup-inspect-wrap-${v.id}`);
    const active = wrap.classList.toggle('dup-theater');
    section.querySelector('.dup-inspect-theater-btn').textContent = active ? 'Exit Theater' : 'Theater';
  });

  // Load metadata async
  fetch(`/api/videos/${v.id}/info`)
    .then(r => r.json())
    .then(info => {
      const el = document.getElementById(`dup-inspect-meta-${v.id}`);
      if (!el || !info) return;
      const parts = [];
      if (info.duration)   parts.push(info.duration);
      if (info.resolution) parts.push(info.resolution);
      if (info.videoCodec) parts.push(info.videoCodec);
      if (info.bitrate)    parts.push(info.bitrate);
      el.textContent = parts.join(' · ') || '—';
    })
    .catch(() => {
      const el = document.getElementById(`dup-inspect-meta-${v.id}`);
      if (el) el.textContent = '—';
    });

  return section;
}

export function openInspectModal(title, locIdx, loc, videos) {
  // Remove any existing overlay
  document.querySelector('.dup-inspect-overlay')?.remove();

  const overlay = document.createElement('div');
  overlay.className = 'dup-inspect-overlay';

  const modal = document.createElement('div');
  modal.className = 'dup-inspect-modal';

  // Header
  const header = document.createElement('div');
  header.className = 'dup-inspect-header';
  const parsed = parseNasPath(loc.nasPath || '');
  const pathHtml = parsed
    ? `<span class="dup-inspect-path-volume">${esc(parsed.volume)}</span>` +
      (parsed.middle ? `<span class="dup-inspect-path-middle">${esc(parsed.middle)}/</span>` : '') +
      (parsed.titleFolder ? `<span class="dup-inspect-path-title">${esc(parsed.titleFolder)}</span>` : '')
    : esc(loc.nasPath || '');
  header.innerHTML = `
    <div class="dup-inspect-header-info">
      <span class="dup-inspect-code">${esc(title.code)}</span>
      <span class="dup-inspect-loc-num">Location ${locIdx + 1} of ${(title.locationEntries || []).length}</span>
    </div>
    <div class="dup-inspect-path-line">${pathHtml}</div>
  `;

  const closeBtn = document.createElement('button');
  closeBtn.type = 'button';
  closeBtn.className = 'dup-inspect-close';
  closeBtn.textContent = '✕';
  header.appendChild(closeBtn);
  modal.appendChild(header);

  // Body
  const body = document.createElement('div');
  body.className = 'dup-inspect-body';
  for (const v of videos) body.appendChild(buildVideoSection(v));
  modal.appendChild(body);

  overlay.appendChild(modal);
  document.body.appendChild(overlay);

  // Dismiss wiring
  const ac = new AbortController();
  const close = () => { overlay.remove(); ac.abort(); };
  closeBtn.addEventListener('click', close, { signal: ac.signal });
  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); }, { signal: ac.signal });
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') close(); }, { signal: ac.signal });
}
