// alias-capture-modal.js
// Prompts the user to add kanji/romaji aliases when manually linking a draft
// cast slot to an existing Actress whose canonical_name doesn't already cover
// those names.
//
// API: openAliasCaptureModal({ actressId, canonicalName, kanjiAlias, romajiAlias })
//   kanjiAlias  — string or null (null means kanji already covered, row not shown)
//   romajiAlias — string or null (null means romaji already covered, row not shown)
//
// Logs (greppable per spec §5.4):
//   console.info('alias-capture: trigger actressId=X needs={kanji=Y, romaji=Z}')
//   console.info('alias-capture: dismissed via=add_both|add_kanji|add_romaji|skip')

import { esc } from './utils.js';

// ── State ──────────────────────────────────────────────────────────────────

let _mount      = null;   // container div injected into document.body
let _actressId  = null;
let _kanji      = null;
let _romaji     = null;

// ── Public API ─────────────────────────────────────────────────────────────

/**
 * Opens the alias-capture modal.
 *
 * @param {object} opts
 * @param {number}  opts.actressId     - id of the linked actress
 * @param {string}  opts.canonicalName - actress's canonical name (display + alias merge)
 * @param {string|null} opts.kanjiAlias  - kanji alias to offer, or null to omit row
 * @param {string|null} opts.romajiAlias - romaji alias to offer, or null to omit row
 */
export function openAliasCaptureModal({ actressId, canonicalName, kanjiAlias, romajiAlias }) {
  _close();

  _actressId = actressId;
  _kanji     = kanjiAlias;
  _romaji    = romajiAlias;

  console.info(
    `alias-capture: trigger actressId=${actressId} needs={kanji=${kanjiAlias}, romaji=${romajiAlias}}`
  );

  // Mirror trigger to server log for §5.4 measurement.
  const needs = [kanjiAlias && 'kanji', romajiAlias && 'romaji'].filter(Boolean);
  fetch('/api/curation/alias-capture-event', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type: 'trigger', actressId, needs }),
  }).catch(() => {});

  _mount = document.createElement('div');
  _mount.id = 'alias-capture-modal-mount';
  document.body.appendChild(_mount);

  _render(canonicalName);
}

// ── Render ─────────────────────────────────────────────────────────────────

function _render(canonicalName) {
  if (!_mount) return;

  const bothNeeded = _kanji && _romaji;
  const onlyKanji  = _kanji && !_romaji;
  const onlyRomaji = !_kanji && _romaji;

  const rowsHtml = [
    _kanji  ? `<div class="ac-alias-row"><span class="ac-alias-text">${esc(_kanji)}</span><span class="ac-alias-kind">kanji</span></div>`  : '',
    _romaji ? `<div class="ac-alias-row"><span class="ac-alias-text">${esc(_romaji)}</span><span class="ac-alias-kind">romaji from translation</span></div>` : '',
  ].join('');

  let buttonsHtml;
  if (bothNeeded) {
    buttonsHtml = `
      <button class="ac-btn ac-btn-primary" id="ac-add-both">Add both</button>
      <button class="ac-btn ac-btn-secondary" id="ac-add-kanji">Add kanji only</button>
      <button class="ac-btn ac-btn-secondary" id="ac-add-romaji">Add romaji only</button>
      <button class="ac-btn ac-btn-cancel" id="ac-skip">Skip</button>`;
  } else {
    buttonsHtml = `
      <button class="ac-btn ac-btn-primary" id="ac-add-one">Add alias</button>
      <button class="ac-btn ac-btn-cancel" id="ac-skip">Skip</button>`;
  }

  _mount.innerHTML = `
    <div class="ac-overlay" id="ac-overlay">
      <div class="ac-card" id="ac-card">
        <div class="ac-header">
          <span class="ac-header-title">Add aliases of <strong>${esc(canonicalName)}</strong>?</span>
          <button class="ac-close" id="ac-close" title="Skip">×</button>
        </div>
        <div class="ac-body">
          <p class="ac-intro">Add the following as aliases of <strong>${esc(canonicalName)}</strong> so future titles auto-link?</p>
          <div class="ac-alias-list">${rowsHtml}</div>
        </div>
        <div class="ac-footer">${buttonsHtml}</div>
      </div>
    </div>`;

  // Backdrop click = skip.
  _mount.querySelector('#ac-overlay').addEventListener('click', e => {
    if (e.target.id === 'ac-overlay') _dismiss('skip');
  });
  _mount.querySelector('#ac-close').addEventListener('click', () => _dismiss('skip'));

  const skipBtn = _mount.querySelector('#ac-skip');
  if (skipBtn) skipBtn.addEventListener('click', () => _dismiss('skip'));

  if (bothNeeded) {
    _mount.querySelector('#ac-add-both').addEventListener('click', () => _submit('add_both'));
    _mount.querySelector('#ac-add-kanji').addEventListener('click', () => _submit('add_kanji'));
    _mount.querySelector('#ac-add-romaji').addEventListener('click', () => _submit('add_romaji'));
  } else {
    _mount.querySelector('#ac-add-one').addEventListener('click', () => {
      const via = onlyKanji ? 'add_kanji' : 'add_romaji';
      _submit(via);
    });
  }

  document.addEventListener('keydown', _onKeydown);
}

function _onKeydown(e) {
  if (e.key === 'Escape') { document.removeEventListener('keydown', _onKeydown); _dismiss('skip'); }
}

// ── Actions ────────────────────────────────────────────────────────────────

async function _submit(via) {
  const toAdd = [];
  if (via === 'add_both'   || via === 'add_kanji')  { if (_kanji)  toAdd.push(_kanji); }
  if (via === 'add_both'   || via === 'add_romaji') { if (_romaji) toAdd.push(_romaji); }

  // Read existing aliases, merge, write back.
  try {
    const res = await fetch(`/api/actresses/${_actressId}`);
    if (!res.ok) throw new Error(`fetch actress failed (${res.status})`);
    const data = await res.json();
    const existing = (data.aliases || []).map(a => (typeof a === 'string' ? a : a.name)).filter(Boolean);
    const merged = [...new Set([...existing, ...toAdd])];

    await fetch(`/api/actresses/${_actressId}/aliases`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ aliases: merged }),
    });
  } catch (err) {
    console.warn('alias-capture: alias write failed', err);
  }

  _dismiss(via);
}

function _dismiss(via) {
  document.removeEventListener('keydown', _onKeydown);
  console.info(`alias-capture: dismissed via=${via}`);
  // Mirror dismiss to server log for §5.4 measurement.
  fetch('/api/curation/alias-capture-event', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type: 'dismissed', actressId: _actressId, via }),
  }).catch(() => {});
  _close();
}

function _close() {
  if (_mount) {
    _mount.remove();
    _mount = null;
  }
  _actressId = null;
  _kanji     = null;
  _romaji    = null;
}
