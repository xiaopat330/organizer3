/* ─────────────────────────────────────────────────────────────────────
   duplicates/index.js — mount entry point.
   Exported as mountDuplicates() for v2-duplicates.html.

   What's here vs legacy:
     PORTED  — Actress-grouped sidebar, alpha/sort bars, headline stats
               with Execute-all, per-actress Execute, pie-progress badges,
               title cards with cover tooltip, path rows (vol/dir/file icons,
               click-to-copy), video chips (size/ext/HEVC/4K), ranker-driven
               Suggested Keep + rationale, Inspect modal with player +
               theater mode, KEEP/TRASH/VARIANT decisions, auto-keep
               promotion, last-non-trashed guard, persistence (PUT/DELETE
               /api/tools/duplicates/decisions), decisions pre-loaded on
               mount, task-center SSE wiring, execute banner on partial fail,
               closure badge, volume filter retained from v2.
     DEFERRED — None. All legacy features ported.
   ───────────────────────────────────────────────────────────────────── */

import * as taskCenter from '../../task-center.js';
import { createVolumePicker } from '../volume-picker.js';
import { createState } from './state.js';
import {
  buildActressGroups, loadAllDecisions, filteredGroups,
} from './decision.js';
import { initExecute } from './execute.js';
import {
  renderHeadline, renderAlphaBar, renderSortBar, renderActressSidebar,
} from './sidebar.js';
import { renderGroups, syncClosureBadge } from './group-card.js';

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;',
  }[c]));
}

// ── Post-It Notes token injection ─────────────────────────────────────────
// tokens.css defines --postit-yellow / --postit-yellow-edge / --postit-ink.
// v1 loads it via index.html; v2 pages do not, so inject once here.
function ensureNoteTokens() {
  const LINK_ID = 'notes-tokens-css';
  if (document.getElementById(LINK_ID)) return;
  const link = document.createElement('link');
  link.id   = LINK_ID;
  link.rel  = 'stylesheet';
  link.href = '/modules/notes/tokens.css';
  document.head.appendChild(link);
}

export async function mountDuplicates(rootEl) {
  ensureNoteTokens();
  rootEl.innerHTML = `
    <div class="dup-wb">
      <div class="dup-wb-head">
        <h1 class="dup-wb-title">Duplicate Triage</h1>
        <div class="dup-wb-subtitle">Titles with more than one location. Decide per-copy; nothing is destroyed until you Execute.</div>
      </div>

      <div class="dup-toolbar">
        <div class="dup-toolbar-left">
          <div id="dup-alpha-bar" class="dup-alpha-bar"></div>
          <div id="dup-sort-bar"  class="dup-sort-bar"></div>
        </div>
        <div class="dup-toolbar-right">
          <div id="dup-vol-picker"></div>
        </div>
      </div>

      <div id="dup-headline" class="dup-headline"></div>

      <div class="dup-layout">
        <aside id="dup-actress-sidebar" class="dup-actress-sidebar">
          <div class="dup-sidebar-load">Loading…</div>
        </aside>
        <div id="dup-groups" class="dup-groups">
          <div class="dup-load-splash">
            <span class="dup-spinner"></span><span>Fetching duplicates…</span>
          </div>
        </div>
      </div>
    </div>
  `;

  const headlineEl  = rootEl.querySelector('#dup-headline');
  const alphaBarEl  = rootEl.querySelector('#dup-alpha-bar');
  const sortBarEl   = rootEl.querySelector('#dup-sort-bar');
  const sidebarEl   = rootEl.querySelector('#dup-actress-sidebar');
  const groupsEl    = rootEl.querySelector('#dup-groups');
  const volPickerEl = rootEl.querySelector('#dup-vol-picker');

  const state = createState();

  // ── Banner helper ─────────────────────────────────────────────────
  function showBanner(message) {
    groupsEl.querySelector('.dup-exec-banner')?.remove();
    const banner = document.createElement('div');
    banner.className = 'dup-exec-banner';
    banner.innerHTML = `
      <span>${esc(message)}</span>
      <button class="dup-exec-banner-close" type="button">✕</button>
    `;
    banner.querySelector('.dup-exec-banner-close').addEventListener('click', () => banner.remove());
    groupsEl.prepend(banner);
  }

  // ── Render helpers ────────────────────────────────────────────────
  //
  // onSelectActress: fired when user clicks a sidebar row — refresh groups.
  // onDecisionChange: fired when a KEEP/TRASH/VARIANT button is toggled —
  //   refresh headline, sidebar badges, and closure badge only; the clicked
  //   card rebuilds itself in-place via refreshCard() / card.replaceWith().
  // reRenderAll: fired by alpha/sort bar changes — full refresh.

  const onSelectActress = () => {
    renderActressSidebar(state, sidebarEl, onSelectActress, true);
    renderGroups(state, groupsEl, onDecisionChange);
  };

  // onDecisionChange: fired after a per-card KEEP/TRASH/VARIANT toggle.
  // Only updates headline stats, sidebar badges, and the closure badge —
  // does NOT rebuild cards (the clicked card does its own targeted replaceWith).
  function onDecisionChange() {
    renderHeadline(state, headlineEl);
    renderActressSidebar(state, sidebarEl, onSelectActress);
    syncClosureBadge(state, groupsEl);
  }

  function reRenderAll() {
    renderHeadline(state, headlineEl);
    renderAlphaBar(state, alphaBarEl, reRenderAll);
    renderSortBar(state, sortBarEl, reRenderAll);
    renderActressSidebar(state, sidebarEl, onSelectActress, true);
    renderGroups(state, groupsEl, onDecisionChange);
  }

  // ── Data loading ──────────────────────────────────────────────────
  async function loadAll() {
    headlineEl.textContent = 'Loading…';
    sidebarEl.innerHTML = '<div class="dup-sidebar-load">Loading…</div>';
    groupsEl.innerHTML  = '<div class="dup-load-splash"><span class="dup-spinner"></span><span>Fetching duplicates…</span></div>';

    try {
      // Fetch entire bounded dataset (paginate transport only)
      const all = [];
      let offset = 0;
      const limit = 200;
      while (true) {
        const url = `/api/tools/duplicates?offset=${offset}&limit=${limit}` +
                    (state.volumeId ? `&volumeId=${encodeURIComponent(state.volumeId)}` : '');
        const res  = await fetch(url);
        const data = await res.json();
        all.push(...data.titles);
        offset += data.titles.length;
        if (all.length >= data.total) break;
      }

      state.allDuplicates  = all;
      state.actressGroups  = buildActressGroups(all);
      state.decisions      = await loadAllDecisions(all);
      // Reset notes and video caches so renderGroups re-fetches for the new title set.
      state.notesByCode    = new Map();
      state.notesCachedKey = null;
      state.videosByCode   = new Map();

      // Restore or default actress selection
      if (!state.currentActressKey || !state.actressGroups.has(state.currentActressKey)) {
        const first = filteredGroups(state)[0];
        state.currentActressKey = first?.[0] || null;
      }

      renderHeadline(state, headlineEl);
      renderAlphaBar(state, alphaBarEl, reRenderAll);
      renderSortBar(state, sortBarEl, reRenderAll);
      renderActressSidebar(state, sidebarEl, onSelectActress, true);
      renderGroups(state, groupsEl, onDecisionChange);
    } catch (err) {
      headlineEl.textContent = 'Failed to load duplicates.';
      console.error('[duplicates] load error', err);
    }
  }

  // ── Wire execute module ───────────────────────────────────────────
  initExecute({ loadAll, showBanner });

  // ── Task-center subscription (refresh header + sidebar on task events)
  const unsub = taskCenter.subscribe(() => {
    renderHeadline(state, headlineEl);
    renderActressSidebar(state, sidebarEl, onSelectActress);
  });

  // Cleanup on navigation (best-effort; page is single-SPA so usually fine)
  rootEl._dupUnsubscribe = unsub;

  // ── Volume picker fires onChange immediately with restored/default value
  await createVolumePicker({
    rootEl: volPickerEl,
    storageKey: 'v2.duplicates.volume',
    allLabel: 'All volumes',
    onChange: (vol) => {
      state.volumeId          = vol || '';
      state.currentActressKey = null; // reset selection on volume change
      loadAll();
    },
  });
}
