// Actress Detail → Admin tab — rich duplicate-location cards (§4.3).
//
// Replaces the old flat-row renderDupSection with card-per-location layout:
// cover thumbnail, path breadcrumb, video chips, ranking badge, inline video
// preview, and Keep/Trash/Variant staging buttons.
//
// Cache strategy: per-location video lists are stored on titleData._locVideos
// (a Map<key, video[]>) so re-renders from staging clicks don't refetch.
// _dupDecisions cache is owned by card.js / attachCardListeners and threaded
// in here as serverDecisions.
//
// Auto-keep: trashing a location auto-stages KEEP on the lone survivor (if
// that survivor has no effective decision). An auto-keep has payload.auto=true
// and is revoked when its trash is undone — unless the user has manually set Keep.

import { esc } from '../utils.js';
import { rankLocations } from '../duplicate-ranker.js';
import * as state from './state.js';
import { displayPath, installPathClickToCopy } from './path-utils.js';

// ── Helpers ───────────────────────────────────────────────────────────────────

export function dupKey(volumeId, nasPath) {
  return `${volumeId}::${nasPath}`;
}

function fmtBytes(b) {
  if (!b) return null;
  if (b >= 1e9) return (b / 1e9).toFixed(1) + ' GB';
  if (b >= 1e6) return (b / 1e6).toFixed(0) + ' MB';
  return (b / 1e3).toFixed(0) + ' KB';
}

function videoChips(v) {
  const fn      = v.filename || '';
  const fnLower = fn.toLowerCase();
  const dot     = fn.lastIndexOf('.');
  const ext     = dot >= 0 ? fn.substring(dot + 1).toUpperCase() : null;
  const isHevc  = fnLower.includes('-h265') || (v.videoCodec || '').toLowerCase().includes('hevc') || (v.videoCodec || '').toLowerCase().includes('h265');
  const is4k    = fnLower.includes('_4k') || fnLower.includes('-4k') || (v.width && v.width >= 3840);

  let html = '';
  const bytes = fmtBytes(v.fileSize);
  if (bytes) html += `<span class="adm-dup-chip">${esc(bytes)}</span>`;
  if (ext)   html += `<span class="adm-dup-chip adm-dup-chip-ext">${esc(ext)}</span>`;
  if (isHevc) html += `<span class="adm-dup-chip adm-dup-chip-hevc">HEVC</span>`;
  if (is4k)   html += `<span class="adm-dup-chip adm-dup-chip-4k">4K</span>`;
  return html;
}

function effectiveDecision(code, volumeId, nasPath, serverDecisions) {
  const key = dupKey(volumeId, nasPath);
  const stage = state.findPendingStage(code, 'duplicate-decision', key);
  if (stage) return stage.payload.decision;
  if (!serverDecisions) return null;
  const d = serverDecisions.find(d => d.volumeId === volumeId && d.nasPath === nasPath);
  return d ? d.decision : null;
}

// Parse //server/share/middle/TitleFolder into parts for the breadcrumb.
function parseNasPath(nasPath) {
  if (!nasPath) return null;
  const s = nasPath.replace(/\\/g, '/');
  const m = s.match(/^(\/\/[^/]+\/[^/]+)(\/(.*))?$/);
  if (!m) return { volume: s, middle: '', titleFolder: '' };
  const volume = m[1];
  const rest   = m[3] || '';
  const slash  = rest.lastIndexOf('/');
  if (slash < 0) return { volume, middle: '', titleFolder: rest };
  return { volume, middle: rest.substring(0, slash), titleFolder: rest.substring(slash + 1) };
}

// ── Section header (rationale + suggested keep line) ─────────────────────────

function renderDupHeader(code, locationEntries, locVideos, serverDecisions) {
  if (!locVideos) {
    return `<div class="adm-dup-rationale adm-dup-rationale-loading">Computing suggestion…</div>`;
  }

  const locData = locationEntries.map(loc => ({
    ...loc,
    videos: locVideos.get(dupKey(loc.volumeId, loc.nasPath)) || [],
  }));
  const rank = rankLocations(locData);

  let rationaleHtml = '';
  if (rank.suggestedIndex === null) {
    rationaleHtml = `<div class="adm-dup-rationale adm-dup-rationale-identical">Locations look identical — your call.</div>`;
  } else {
    const sugLoc = locationEntries[rank.suggestedIndex];
    const parsed = sugLoc ? parseNasPath(sugLoc.nasPath) : null;
    const sugDesc = parsed
      ? `${esc(sugLoc.volumeId)} / ${esc(parsed.titleFolder || parsed.volume)}`
      : (sugLoc ? esc(sugLoc.nasPath) : '?');
    rationaleHtml = `
      <div class="adm-dup-rationale">${esc(rank.rationale)}</div>
      <div class="adm-dup-suggested-keep">Suggested keep: ${sugDesc}</div>`;
  }

  return rationaleHtml;
}

// ── Single location card (HTML string) ───────────────────────────────────────

function renderLocCard(code, loc, locIndex, locationEntries, locVideos, serverDecisions) {
  const key       = dupKey(loc.volumeId, loc.nasPath);
  const stage     = state.findPendingStage(code, 'duplicate-decision', key);
  const effDec    = stage ? stage.payload.decision : (() => {
    if (!serverDecisions) return null;
    const d = serverDecisions.find(d => d.volumeId === loc.volumeId && d.nasPath === loc.nasPath);
    return d ? d.decision : null;
  })();
  const isStaged  = !!stage;
  const isAuto    = isStaged && stage.payload.auto === true;

  // Suggested badge
  let isSuggested = false;
  if (locVideos) {
    const locData = locationEntries.map(l => ({
      ...l,
      videos: locVideos.get(dupKey(l.volumeId, l.nasPath)) || [],
    }));
    const rank = rankLocations(locData);
    isSuggested = rank.suggestedIndex === locIndex;
  }

  // Path breadcrumb — entire .adm-dup-path is click-to-copy (wired in attachDupCardListeners).
  const parsed = parseNasPath(loc.nasPath);
  const dispVol  = parsed ? displayPath(parsed.volume) : '';
  let pathHtml = '';
  if (parsed) {
    pathHtml = `
      <div class="adm-dup-path-vol">${esc(dispVol)}</div>
      ${parsed.middle ? `<div class="adm-dup-path-middle">${esc(parsed.middle)}</div>` : ''}
      <div class="adm-dup-path-leaf">${esc(parsed.titleFolder || dispVol)}</div>`;
  } else {
    pathHtml = `<div class="adm-dup-path-leaf">${esc(displayPath(loc.nasPath || ''))}</div>`;
  }

  // Video rows
  const videos = locVideos ? (locVideos.get(key) || []) : [];
  const videoRowsHtml = videos.length > 0
    ? videos.map(v => {
        const resHtml = (v.width && v.height)
          ? `<span class="adm-dup-res">${v.width}×${v.height}</span>`
          : '';
        const chips = videoChips(v);
        const fname = esc(v.filename || v.path?.split('/').pop() || `Video ${v.id}`);
        return `
          <div class="adm-dup-video-row" data-video-id="${esc(String(v.id))}">
            <span class="adm-dup-video-name">${fname}</span>
            <span class="adm-dup-video-chips">${resHtml}${chips}</span>
          </div>`;
      }).join('')
    : (locVideos ? '<div class="adm-dup-no-videos">No videos found</div>' : '<div class="adm-dup-no-videos">Loading…</div>');

  // Decision buttons
  const btnHtml = (label, value) => {
    const isActive = effDec === value;
    const classes = [
      'adm-dup-btn',
      `adm-dup-btn-${label.toLowerCase()}`,
      isActive ? 'active' : '',
      (isActive && isStaged) ? 'staged' : '',
    ].filter(Boolean).join(' ');
    const autoTag = (isActive && isAuto && value === 'KEEP')
      ? ' <span class="adm-dup-auto-tag">(auto)</span>'
      : '';
    return `<button class="${classes}" data-dup-action="${esc(value)}" data-volume-id="${esc(loc.volumeId)}" data-nas-path="${esc(loc.nasPath)}">${label}${autoTag}</button>`;
  };

  // Inspect button — only if we have videos loaded and they have IDs
  const hasVideos = videos.length > 0;
  const inspectBtnHtml = hasVideos
    ? `<button class="adm-dup-inspect-btn" data-loc-key="${esc(key)}" type="button">▶ Inspect</button>`
    : '';

  const suggestedBadgeHtml = isSuggested
    ? '<span class="adm-dup-suggested-badge">⭐ Suggested</span>'
    : '';

  const cardClass = [
    'adm-dup-loc-card',
    effDec ? `adm-dup-loc-${effDec.toLowerCase()}` : '',
    isSuggested ? 'adm-dup-loc-suggested' : '',
  ].filter(Boolean).join(' ');

  return `
    <div class="${cardClass}" data-loc-key="${esc(key)}" data-volume-id="${esc(loc.volumeId)}" data-nas-path="${esc(loc.nasPath)}">
      <div class="adm-dup-loc-header">
        <div class="adm-dup-vol-badge">${esc(loc.volumeId)}</div>
        ${suggestedBadgeHtml}
      </div>
      <div class="adm-dup-path" data-raw-path="${esc(loc.nasPath || '')}">${pathHtml}</div>
      <div class="adm-dup-videos">${videoRowsHtml}</div>
      <div class="adm-dup-inspect-wrap" data-loc-key="${esc(key)}">
        ${inspectBtnHtml}
        <!-- inline video player mounts here lazily -->
      </div>
      <div class="adm-dup-actions">
        ${btnHtml('Keep', 'KEEP')}${btnHtml('Trash', 'TRASH')}${btnHtml('Variant', 'VARIANT')}
      </div>
    </div>`;
}

// ── Main render function ──────────────────────────────────────────────────────

export function renderDupSection(code, locationEntries, serverDecisions) {
  if (!locationEntries || locationEntries.length < 2) return '';

  const titleData = state.getCardData(code);
  const locVideos = (titleData && Object.prototype.hasOwnProperty.call(titleData, '_locVideos'))
    ? titleData._locVideos
    : null;

  const loadingNote = serverDecisions === null
    ? '<div class="admin-card-dup-loading">Loading decisions…</div>'
    : '';

  const headerHtml = renderDupHeader(code, locationEntries, locVideos, serverDecisions);

  const cardsHtml = locationEntries.map((loc, i) =>
    renderLocCard(code, loc, i, locationEntries, locVideos, serverDecisions)
  ).join('');

  return `
    <div class="admin-card-dup-section">
      <div class="admin-card-dup-section-title">Duplicate folders</div>
      ${loadingNote}
      ${headerHtml}
      <div class="adm-dup-grid">${cardsHtml}</div>
    </div>`;
}

// ── Inline video player ───────────────────────────────────────────────────────

function mountInspectPlayer(wrapEl, videos) {
  const section = document.createElement('div');
  section.className = 'adm-dup-player-section';

  for (const v of videos) {
    const videoId = v.id;
    const fname   = v.filename || v.path?.split('/').pop() || `Video ${videoId}`;

    const vsec = document.createElement('div');
    vsec.className = 'adm-dup-player-video';
    vsec.innerHTML = `
      <div class="adm-dup-player-fname">${esc(fname)}</div>
      <div class="adm-dup-player-chips">${videoChips(v)}</div>
      <div class="adm-dup-player-wrap" id="adm-dup-wrap-${videoId}">
        <video class="adm-dup-player" controls preload="none" src="/api/stream/${videoId}"></video>
        <button class="adm-dup-theater-btn" type="button">Theater</button>
      </div>`;

    vsec.querySelector('.adm-dup-theater-btn').addEventListener('click', () => {
      const pw = vsec.querySelector('.adm-dup-player-wrap');
      const active = pw.classList.toggle('adm-dup-theater-mode');
      vsec.querySelector('.adm-dup-theater-btn').textContent = active ? 'Exit Theater' : 'Theater';
    });

    section.appendChild(vsec);
  }

  wrapEl.appendChild(section);
}

// ── Listener attachment (called by card.js attachCardListeners) ───────────────

export function attachDupCardListeners(code, card, titleData, renderCardInPlace) {
  const locationEntries = titleData.locationEntries || [];

  // §A: Path click-to-copy (full nasPath, with .local stripped on non-mac)
  card.querySelectorAll('.adm-dup-path[data-raw-path]').forEach(el => {
    installPathClickToCopy(el, el.dataset.rawPath);
  });

  // §B: Decision buttons (Keep / Trash / Variant)
  // (per-loc video + decisions fetches are now bootstrapped together in card.js)
  card.querySelectorAll('.adm-dup-btn[data-dup-action]').forEach(btn => {
    btn.addEventListener('click', () => {
      const clickedDecision = btn.dataset.dupAction;
      const volumeId        = btn.dataset.volumeId;
      const nasPath         = btn.dataset.nasPath;
      const key             = dupKey(volumeId, nasPath);

      const pendingStage = state.findPendingStage(code, 'duplicate-decision', key);

      const serverDec = (() => {
        const decs = titleData._dupDecisions;
        if (!Array.isArray(decs)) return null;
        const d = decs.find(d => d.volumeId === volumeId && d.nasPath === nasPath);
        return d ? d.decision : null;
      })();
      const effDec = pendingStage ? pendingStage.payload.decision : serverDec;

      if (clickedDecision === effDec) {
        // Toggle off — undo the pending stage
        state.removePendingStage(code, 'duplicate-decision', key);
        if (serverDec !== null) {
          state.addStage(code, 'duplicate-decision', { volumeId, nasPath, decision: null }, key);
        }

        // §C undo: if undoing a TRASH, remove any auto-Keep on the lone survivor
        if (clickedDecision === 'TRASH') {
          _removeAutoKeepIfNeeded(code, locationEntries, titleData);
        }
      } else {
        state.addStage(code, 'duplicate-decision', { volumeId, nasPath, decision: clickedDecision }, key);

        // §C: auto-keep lone survivor after a TRASH
        if (clickedDecision === 'TRASH') {
          _autoKeepLoneSurvivor(code, locationEntries, titleData);
        }
      }

      renderCardInPlace(code);
    });
  });

  // §D: Inspect button — toggle inline player
  card.querySelectorAll('.adm-dup-inspect-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const locKey  = btn.dataset.locKey;
      const wrapEl  = card.querySelector(`.adm-dup-inspect-wrap[data-loc-key="${CSS.escape(locKey)}"]`);
      if (!wrapEl) return;

      const existing = wrapEl.querySelector('.adm-dup-player-section');
      if (existing) {
        // Collapse: pause all videos and remove
        wrapEl.querySelectorAll('video').forEach(v => v.pause());
        existing.remove();
        btn.textContent = '▶ Inspect';
        return;
      }

      // Pause any other open players in this card
      card.querySelectorAll('.adm-dup-player-section video').forEach(v => v.pause());
      card.querySelectorAll('.adm-dup-player-section').forEach(s => s.remove());
      card.querySelectorAll('.adm-dup-inspect-btn').forEach(b => { b.textContent = '▶ Inspect'; });

      const videos = titleData._locVideos ? (titleData._locVideos.get(locKey) || []) : [];
      if (videos.length === 0) return;

      btn.textContent = '◼ Close';
      mountInspectPlayer(wrapEl, videos);
    });
  });
}

// ── §C Auto-keep helpers ──────────────────────────────────────────────────────

function _effectiveDecForLoc(code, loc, titleData) {
  const key   = dupKey(loc.volumeId, loc.nasPath);
  const stage = state.findPendingStage(code, 'duplicate-decision', key);
  if (stage) return stage.payload.decision;
  const decs = titleData._dupDecisions;
  if (!Array.isArray(decs)) return null;
  const d = decs.find(d => d.volumeId === loc.volumeId && d.nasPath === loc.nasPath);
  return d ? d.decision : null;
}

function _autoKeepLoneSurvivor(code, locationEntries, titleData) {
  const survivors = locationEntries.filter(loc => _effectiveDecForLoc(code, loc, titleData) !== 'TRASH');
  if (survivors.length !== 1) return;
  const lone = survivors[0];
  const loneKey = dupKey(lone.volumeId, lone.nasPath);
  if (_effectiveDecForLoc(code, lone, titleData) !== null) return;
  state.addStage(code, 'duplicate-decision', { volumeId: lone.volumeId, nasPath: lone.nasPath, decision: 'KEEP', auto: true }, loneKey);
}

function _removeAutoKeepIfNeeded(code, locationEntries, titleData) {
  // After undoing a trash, recount survivors.
  // If > 1 survivor and there's an auto-Keep somewhere, remove it.
  const survivors = locationEntries.filter(loc => _effectiveDecForLoc(code, loc, titleData) !== 'TRASH');
  if (survivors.length <= 1) return;
  for (const loc of locationEntries) {
    const key   = dupKey(loc.volumeId, loc.nasPath);
    const stage = state.findPendingStage(code, 'duplicate-decision', key);
    if (stage && stage.payload.auto === true && stage.payload.decision === 'KEEP') {
      state.removePendingStage(code, 'duplicate-decision', key);
    }
  }
}
