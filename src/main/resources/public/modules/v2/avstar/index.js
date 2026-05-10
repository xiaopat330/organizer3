/* ─────────────────────────────────────────────────────────────────────
   v2/avstar/index.js
   Full port of legacy av-actress-detail.js + av-video-detail.js +
   av-screenshot-controls.js into the v2 design system.

   Layout: two-column (.avd-rail | .avd-right)
     Left rail  — portrait, name/stats/badges, action buttons,
                  screenshot controls, profile details
     Right pane — video toolbar + grid (default), or in-pane video
                  detail (on card click). Back button restores the grid.
                  In-pane navigation matches legacy; no URL routing.

   Endpoints: unchanged from legacy (/api/av/actresses/*, /api/av/videos/*).
   Dropped from prior v2 stub:
     • /api/utilities/avstars/actresses endpoint (non-legacy, replaced)
     • Tech summary tiles (codecs/resolutions) — not present in legacy
   ───────────────────────────────────────────────────────────────────── */

import { renderHero, wireHero, updateVisitedRow, unmountScreenshotControls } from './hero.js';
import { mountVideosPanel, updateVideo, refreshVideos }                       from './videos.js';
import { openVideoDetail }                                                     from './video-detail.js';

const VISIT_DELAY_MS = 5000;

// ── Module-level state ────────────────────────────────────────────────────

let _actressId   = null;
let _actress     = null;
let _allVideos   = [];
let _visitTimer  = null;

// ── Utils ─────────────────────────────────────────────────────────────────

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

async function fetchJson(url, fallback = null) {
  try {
    const r = await fetch(url, { cache: 'no-cache' });
    if (!r.ok) return fallback;
    return await r.json();
  } catch (e) {
    console.warn('[avstar-detail]', url, e);
    return fallback;
  }
}

// ── Entry point (exported, consumed by avstar-detail.js) ──────────────────

export async function mountAvStarDetail(rootEl, id) {
  // Cancel any lingering visit timer from a prior load
  if (_visitTimer !== null) {
    clearTimeout(_visitTimer);
    _visitTimer = null;
  }
  unmountScreenshotControls();

  if (!id) {
    rootEl.innerHTML = `
      <div class="lib-page">
        <div class="empty-state">
          <div class="empty-state-title">Missing AV star ID</div>
          <div class="empty-state-body">Append <code>?id=NUMBER</code> to the URL.</div>
        </div>
      </div>`;
    return;
  }

  _actressId = parseInt(id, 10);
  _actress   = null;
  _allVideos = [];

  // Scaffold
  rootEl.innerHTML = `
    <div class="avd-layout">
      <aside class="avd-rail" id="avd-rail">
        <div class="shelf-loading">Loading…</div>
      </aside>
      <section class="avd-right" id="avd-right">
        <div class="shelf-loading">Loading…</div>
      </section>
    </div>`;

  // Parallel fetch
  const [profile, videos] = await Promise.all([
    fetchJson(`/api/av/actresses/${_actressId}`, null),
    fetchJson(`/api/av/actresses/${_actressId}/videos`, []),
  ]);

  if (!profile) {
    rootEl.innerHTML = `
      <div class="lib-page">
        <div class="empty-state">
          <div class="empty-state-title">AV star not found</div>
          <div class="empty-state-body">No AV star with ID ${esc(id)}.</div>
        </div>
      </div>`;
    return;
  }

  _actress   = profile;
  _allVideos = Array.isArray(videos) ? videos : [];

  // Update breadcrumb + title
  const name = _actress.stageName || _actress.folderName || `#${id}`;
  const crumb = document.getElementById('crumb-name');
  if (crumb) crumb.textContent = name;
  document.title = `${name} — Organizer3 v2`;

  // Render left rail
  const rail = document.getElementById('avd-rail');
  if (rail) {
    rail.innerHTML = renderHero(_actress);
    wireHero(_actressId, _actress, _allVideos, null);
  }

  // Render right pane (video grid)
  _mountVideoGrid();

  // 5-second visit timer
  _visitTimer = setTimeout(() => {
    _visitTimer = null;
    fetch(`/api/av/actresses/${_actressId}/visit`, { method: 'POST' })
      .then(r => r.ok ? r.json() : null)
      .then(d => {
        if (d) updateVisitedRow(d.visitCount, d.lastVisitedAt);
      })
      .catch(() => {});
  }, VISIT_DELAY_MS);

  // Global event listeners for screenshot events
  _bindWindowEvents();
}

// ── Video grid (right pane default) ──────────────────────────────────────

function _mountVideoGrid() {
  const rightEl = document.getElementById('avd-right');
  if (!rightEl) return;
  mountVideosPanel(rightEl, _allVideos, _onVideoCardClick);
}

function _restoreVideoGrid() {
  _mountVideoGrid();
}

function _onVideoCardClick(videoId) {
  openVideoDetail(videoId, _restoreVideoGrid);
}

// ── Window events (screenshot queue coordination) ─────────────────────────

let _eventsWired = false;

function _bindWindowEvents() {
  if (_eventsWired) return;
  _eventsWired = true;

  // Per-video: update card thumbnail when a single video's screenshots are generated
  window.addEventListener('av-screenshots-generated', e => {
    const { videoId, count } = e.detail;
    if (!_allVideos.some(v => v.id === videoId)) return;
    updateVideo(videoId, {
      screenshotCount: count,
      firstScreenshotUrl: `/api/av/screenshots/${videoId}/0`,
    });
  });

  // Actress-wide: refresh full video list when the queue drains
  window.addEventListener('av-screenshots-queue-done', e => {
    if (e.detail.actressId !== _actressId) return;
    fetchJson(`/api/av/actresses/${_actressId}/videos`, null)
      .then(videos => {
        if (!videos) return;
        _allVideos = videos;
        refreshVideos(_allVideos);
        // Re-mount screenshot controls now that pending count is 0
        const ssContainer = document.getElementById('avd-ss-controls');
        if (ssContainer) {
          import('./screenshot-controls.js').then(m => {
            m.mount(ssContainer, _actressId, 0);
          });
        }
      })
      .catch(() => {});
  });
}
