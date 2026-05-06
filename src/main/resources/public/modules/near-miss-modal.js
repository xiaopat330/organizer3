// near-miss-modal.js
// Curation modal for resolving unresolved kanji stage names.
// API: mount(containerEl, { kanji, primarySlug? }) / unmount()
//
// States: loading | translating | ready | saving | error
// Dispatches 'near-miss-resolved' on window after a successful save.

import { esc } from './utils.js';

const POLL_MS = 2000;
const DEBOUNCE_MS = 200;

let _container = null;
let _kanji = null;
let _primarySlug = null;
let _mountId = 0;
let _pollTimer = null;
let _debounceTimer = null;

// State
let _state = 'loading';
let _romaji = null;
let _unresolvedDraftCount = 0;
let _selectedActressId = null;
let _errorMessage = null;
let _candidates = [];
let _outcome = 'ALIAS';

export function mount(containerEl, { kanji, primarySlug = null }) {
  unmount();
  _container = containerEl;
  _kanji = kanji;
  _primarySlug = primarySlug;
  _state = 'loading';
  _romaji = null;
  _unresolvedDraftCount = 0;
  _selectedActressId = null;
  _errorMessage = null;
  _candidates = [];
  _outcome = 'ALIAS';
  const id = ++_mountId;
  renderOverlay();
  loadStatus(id);
}

export function unmount() {
  if (_pollTimer !== null) { clearInterval(_pollTimer); _pollTimer = null; }
  if (_debounceTimer !== null) { clearTimeout(_debounceTimer); _debounceTimer = null; }
  if (_container) { _container.innerHTML = ''; }
  _container = null;
  _kanji = null;
  _primarySlug = null;
  _state = 'loading';
  _romaji = null;
  _unresolvedDraftCount = 0;
  _selectedActressId = null;
  _errorMessage = null;
  _candidates = [];
  _outcome = 'ALIAS';
}

// ── Load / poll ────────────────────────────────────────────────────────────

async function loadStatus(id) {
  if (_mountId !== id || !_container) return;
  try {
    const res = await fetch(`/api/translation/stage-name-status?kanji=${encodeURIComponent(_kanji)}`);
    if (_mountId !== id || !_container) return;
    if (!res.ok) { transitionError(id, `status fetch failed (${res.status})`); return; }
    const data = await res.json();
    if (_mountId !== id || !_container) return;
    _unresolvedDraftCount = data.unresolvedDraftCount ?? 0;
    await handleStatusData(id, data);
  } catch (e) {
    if (_mountId !== id || !_container) return;
    transitionError(id, 'network error loading status');
  }
}

async function handleStatusData(id, data) {
  if (data.status === 'ready') {
    _romaji = data.romaji ?? null;
    _state = 'ready';
    render(id);
    if (_romaji) fetchCandidates(id, _romaji);
  } else if (data.status === 'queued') {
    _state = 'translating';
    render(id);
    startPolling(id);
  } else {
    // missing — kick off the LLM call, then continue polling
    await triggerTranslation(id);
  }
}

async function triggerTranslation(id) {
  if (_mountId !== id || !_container) return;
  try {
    const res = await fetch('/api/translation/stage-name-translate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ kanji: _kanji }),
    });
    if (_mountId !== id || !_container) return;
    if (!res.ok) { transitionError(id, `translate trigger failed (${res.status})`); return; }
    const data = await res.json();
    if (_mountId !== id || !_container) return;
    _unresolvedDraftCount = data.unresolvedDraftCount ?? _unresolvedDraftCount;
    if (data.status === 'ready') {
      _romaji = data.romaji ?? null;
      _state = 'ready';
      render(id);
      if (_romaji) fetchCandidates(id, _romaji);
    } else {
      _state = 'translating';
      render(id);
      startPolling(id);
    }
  } catch (e) {
    if (_mountId !== id || !_container) return;
    transitionError(id, 'network error triggering translation');
  }
}

function startPolling(id) {
  if (_pollTimer !== null) return;
  _pollTimer = setInterval(() => pollStatus(id), POLL_MS);
}

async function pollStatus(id) {
  if (_mountId !== id || !_container) { clearInterval(_pollTimer); _pollTimer = null; return; }
  try {
    const res = await fetch(`/api/translation/stage-name-status?kanji=${encodeURIComponent(_kanji)}`);
    if (_mountId !== id || !_container) return;
    if (!res.ok) return;
    const data = await res.json();
    if (_mountId !== id || !_container) return;
    _unresolvedDraftCount = data.unresolvedDraftCount ?? _unresolvedDraftCount;
    if (data.status === 'ready') {
      clearInterval(_pollTimer);
      _pollTimer = null;
      _romaji = data.romaji ?? null;
      _state = 'ready';
      render(id);
      if (_romaji) fetchCandidates(id, _romaji);
    }
  } catch { /* retry on next tick */ }
}

async function fetchCandidates(id, romaji) {
  if (_mountId !== id || !_container) return;
  try {
    const res = await fetch(`/api/curation/fuzzy-candidates?romaji=${encodeURIComponent(romaji)}`);
    if (_mountId !== id || !_container) return;
    if (!res.ok) return;
    const data = await res.json();
    if (_mountId !== id || !_container) return;
    _candidates = data;
    renderCandidateList();
  } catch { /* non-critical; picker shows empty */ }
}

function transitionError(id, msg) {
  if (_mountId !== id || !_container) return;
  _state = 'error';
  _errorMessage = msg;
  render(id);
}

// ── Render ─────────────────────────────────────────────────────────────────

function renderOverlay() {
  if (!_container) return;
  _container.innerHTML = `
    <div class="nm-overlay" id="nm-overlay">
      <div class="nm-card" id="nm-card">
        <div class="nm-header">
          <span class="nm-header-title">Resolve kanji: <span class="nm-kanji">${esc(_kanji)}</span></span>
          <button class="nm-close" id="nm-close" title="Cancel">×</button>
        </div>
        <div class="nm-body" id="nm-body"></div>
        <div class="nm-footer" id="nm-footer"></div>
      </div>
    </div>`;

  _container.querySelector('#nm-overlay').addEventListener('click', e => {
    if (e.target.id === 'nm-overlay') doCancel();
  });
  _container.querySelector('#nm-close').addEventListener('click', doCancel);
  document.addEventListener('keydown', onKeydown);
}

function onKeydown(e) {
  if (e.key === 'Escape') { document.removeEventListener('keydown', onKeydown); doCancel(); }
}

function render(id) {
  if (_mountId !== id || !_container) return;
  renderBody();
  renderFooter();
}

function renderBody() {
  const body = _container.querySelector('#nm-body');
  if (!body) return;

  if (_state === 'loading' || _state === 'translating') {
    body.innerHTML = `
      <div class="nm-translation-row">
        Translation: <span class="nm-translating">Translating…</span>
      </div>
      ${formFieldsHtml(true)}
      ${outcomeSectionHtml(true)}
      ${confirmLineHtml()}`;
  } else if (_state === 'ready' || _state === 'saving') {
    const disabled = _state === 'saving';
    body.innerHTML = `
      <div class="nm-translation-row">
        Translation: <span class="nm-translation-value">${esc(_romaji ?? '')}</span>
      </div>
      ${formFieldsHtml(disabled)}
      ${outcomeSectionHtml(disabled)}
      ${confirmLineHtml()}`;
    bindFormEvents();
  } else if (_state === 'error') {
    body.innerHTML = `
      <div class="nm-translation-row">
        Translation: <span class="nm-translation-value">${esc(_romaji ?? '')}</span>
      </div>
      ${formFieldsHtml(false)}
      ${outcomeSectionHtml(false)}
      ${confirmLineHtml()}
      <div class="nm-error">${esc(_errorMessage ?? 'An error occurred.')}</div>`;
    bindFormEvents();
  }
}

function formFieldsHtml(disabled) {
  const { first, last } = splitRomaji(_romaji);
  const d = disabled ? ' disabled' : '';
  return `
    <div class="nm-name-row">
      <div class="nm-name-field">
        <div class="nm-label">First</div>
        <input class="nm-input" id="nm-first" type="text" value="${esc(first ?? '')}" placeholder="(optional)"${d}>
      </div>
      <div class="nm-name-field">
        <div class="nm-label">Last</div>
        <input class="nm-input" id="nm-last" type="text" value="${esc(last ?? '')}"${d}>
      </div>
    </div>`;
}

function outcomeSectionHtml(disabled) {
  const d = disabled ? ' disabled' : '';
  const isAlias = _outcome === 'ALIAS';
  const aliasHidden = isAlias ? '' : ' nm-hidden';
  const canonHidden = isAlias ? ' nm-hidden' : '';
  // CANONICAL is always available regardless of whether primarySlug was passed in.
  // When null (Tools-page entry), the backend auto-picks the oldest unresolved draft
  // for this kanji — see PROPOSAL_NEAR_MISS_RESOLVER.md §4.4.
  const canonicalDisabled = d;

  return `
    <div class="nm-outcome-section">
      <label class="nm-radio-row">
        <input type="radio" name="nm-outcome" value="ALIAS" ${isAlias ? 'checked' : ''}${d}>
        Alias of existing Actress
      </label>
      <div class="nm-alias-panel${aliasHidden}" id="nm-alias-panel">
        <input class="nm-input" id="nm-search" type="text" placeholder="Search actresses…"${d}>
        <div class="nm-candidate-list" id="nm-candidate-list">
          ${candidateListInnerHtml()}
        </div>
      </div>

      <label class="nm-radio-row">
        <input type="radio" name="nm-outcome" value="CANONICAL" ${!isAlias ? 'checked' : ''}${canonicalDisabled}>
        New canonical Actress
      </label>
      <div class="nm-canonical-panel${canonHidden}" id="nm-canonical-panel">
        <div class="nm-canonical-hint">
          Uses the English name above; no folder is created until the draft is published.
        </div>
      </div>
    </div>`;
}

function candidateListInnerHtml() {
  if (!_candidates || _candidates.length === 0) {
    return `<div class="nm-no-candidates">No suggestions</div>`;
  }
  return _candidates.map(c => {
    const selected = _selectedActressId === c.actressId ? ' selected' : '';
    const labelClass = c.rule.startsWith('strong') ? 'nm-label-strong' : 'nm-label-weak';
    return `<div class="nm-candidate${selected}" data-actress-id="${c.actressId}">
      <span class="nm-candidate-name">${esc(c.canonicalName ?? '')}</span>
      <span class="${labelClass}">${esc(c.rule)}</span>
    </div>`;
  }).join('');
}

function renderCandidateList() {
  const el = _container && _container.querySelector('#nm-candidate-list');
  if (!el) return;
  el.innerHTML = candidateListInnerHtml();
  bindCandidateClicks(el);
}

function confirmLineHtml() {
  return `<div class="nm-confirm-line">
    This will update <span class="nm-confirm-count">${_unresolvedDraftCount}</span>
    draft${_unresolvedDraftCount !== 1 ? 's' : ''} using kanji <span class="nm-kanji">${esc(_kanji)}</span>.
  </div>`;
}

function renderFooter() {
  const footer = _container.querySelector('#nm-footer');
  if (!footer) return;
  const loading = _state === 'loading' || _state === 'translating' || _state === 'saving';
  const saveDisabled = loading || !isSaveEnabled();

  footer.innerHTML = `
    <button class="nm-btn nm-btn-cancel" id="nm-cancel">Cancel</button>
    ${_state === 'error' ? '<button class="nm-btn nm-btn-retry" id="nm-retry">Retry</button>' : ''}
    <button class="nm-btn nm-btn-primary" id="nm-save" ${saveDisabled ? 'disabled' : ''}>Save &amp; Cascade</button>`;

  footer.querySelector('#nm-cancel').addEventListener('click', doCancel);
  footer.querySelector('#nm-save').addEventListener('click', doSave);
  if (_state === 'error') footer.querySelector('#nm-retry').addEventListener('click', doRetry);
}

function isSaveEnabled() {
  if (_state !== 'ready' && _state !== 'error') return false;
  const lastInput = _container && _container.querySelector('#nm-last');
  const lastVal = lastInput ? lastInput.value.trim() : '';
  if (_outcome === 'ALIAS') return _selectedActressId != null;
  return lastVal.length > 0;
}

function bindFormEvents() {
  const body = _container.querySelector('#nm-body');
  if (!body) return;

  body.querySelectorAll('input[name="nm-outcome"]').forEach(radio => {
    radio.addEventListener('change', e => {
      _outcome = e.target.value;
      const id = _mountId;
      render(id);
    });
  });

  const lastInput = body.querySelector('#nm-last');
  if (lastInput) {
    lastInput.addEventListener('input', () => {
      const footer = _container && _container.querySelector('#nm-footer');
      if (!footer) return;
      const saveBtn = footer.querySelector('#nm-save');
      if (saveBtn) saveBtn.disabled = !isSaveEnabled();
    });
  }

  const searchInput = body.querySelector('#nm-search');
  if (searchInput) {
    searchInput.addEventListener('input', e => {
      clearTimeout(_debounceTimer);
      const val = e.target.value.trim();
      _debounceTimer = setTimeout(() => {
        const id = _mountId;
        if (val.length > 0) fetchCandidates(id, val);
        else if (_romaji) fetchCandidates(id, _romaji);
      }, DEBOUNCE_MS);
    });
  }

  const candidateList = body.querySelector('#nm-candidate-list');
  if (candidateList) bindCandidateClicks(candidateList);
}

function bindCandidateClicks(el) {
  el.querySelectorAll('.nm-candidate').forEach(row => {
    row.addEventListener('click', () => {
      const aid = Number(row.dataset.actressId);
      _selectedActressId = _selectedActressId === aid ? null : aid;
      el.querySelectorAll('.nm-candidate').forEach(r => {
        r.classList.toggle('selected', Number(r.dataset.actressId) === _selectedActressId);
      });
      const footer = _container && _container.querySelector('#nm-footer');
      if (!footer) return;
      const saveBtn = footer.querySelector('#nm-save');
      if (saveBtn) saveBtn.disabled = !isSaveEnabled();
    });
  });
}

// ── Actions ────────────────────────────────────────────────────────────────

function doCancel() {
  document.removeEventListener('keydown', onKeydown);
  unmount();
}

async function doSave() {
  if (!_container) return;
  const firstInput = _container.querySelector('#nm-first');
  const lastInput  = _container.querySelector('#nm-last');
  const englishFirst = firstInput ? firstInput.value.trim() || null : null;
  const englishLast  = lastInput  ? lastInput.value.trim()  || null : null;

  const body = {
    kanji:       _kanji,
    outcome:     _outcome,
    englishFirst,
    englishLast,
    llmRomaji:   _romaji ?? null,
  };
  if (_outcome === 'ALIAS' && _selectedActressId != null) {
    body.aliasOfActressId = _selectedActressId;
  }
  if (_outcome === 'CANONICAL' && _primarySlug != null) {
    body.primarySlug = _primarySlug;
  }

  _state = 'saving';
  const id = _mountId;
  render(id);

  try {
    const res = await fetch('/api/curation/near-miss/resolve', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (_mountId !== id || !_container) return;
    if (!res.ok) {
      const data = await res.json().catch(() => ({}));
      transitionError(id, data.error ?? `save failed (${res.status})`);
      return;
    }
    const result = await res.json();
    const kanji = _kanji;
    const outcome = _outcome;
    document.removeEventListener('keydown', onKeydown);
    unmount();
    window.dispatchEvent(new CustomEvent('near-miss-resolved', {
      detail: { kanji, outcome, updatedDrafts: result.updatedDrafts, insertedAliases: result.insertedAliases },
    }));
  } catch (e) {
    if (_mountId !== id || !_container) return;
    transitionError(id, 'network error saving');
  }
}

function doRetry() {
  const id = _mountId;
  _state = 'saving';
  render(id);
  doSave();
}

// ── Utilities ──────────────────────────────────────────────────────────────

function splitRomaji(romaji) {
  if (!romaji) return { first: null, last: null };
  const parts = romaji.trim().replace(/\s+/g, ' ').split(' ');
  if (parts.length === 1) return { first: null, last: parts[0] };
  if (parts.length === 2) return { first: parts[0], last: parts[1] };
  return { first: parts[0], last: parts.slice(1).join(' ') };
}
