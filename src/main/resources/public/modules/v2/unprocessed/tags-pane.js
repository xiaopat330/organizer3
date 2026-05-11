/* ─────────────────────────────────────────────────────────────────────
   unprocessed/tags-pane.js — tri-state tag panel.

   Reads tagsCatalog from state (already fetched by index.js via
   /api/tags). Three tag tiers:
     directTags (Set)            — toggleable, active style
     labelImpliedTags (Set)      — implied-red, disabled
     enrichmentImpliedTags (Set) — implied-red, disabled

   Per-category color palette preserved via data-category attribute so
   CSS rules can target `.un-tag-group[data-category="..."]`.

   Exported:
     renderTagPanel(containerEl, editorState, isDuplicate) → void
   ───────────────────────────────────────────────────────────────────── */

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

/**
 * Render the tag panel into containerEl.
 *
 * @param {HTMLElement}  containerEl
 * @param {object}       editorState  — from state.editorState
 * @param {Array}        tagsCatalog  — from state.tagsCatalog
 * @param {boolean}      isDuplicate
 * @param {Function}     onChange     — called with no args after any chip toggle
 */
export function renderTagPanel(containerEl, editorState, tagsCatalog, isDuplicate, onChange) {
  if (!editorState || !tagsCatalog) {
    containerEl.innerHTML = '';
    return;
  }

  const direct     = editorState.directTags;
  const implied    = editorState.labelImpliedTags;
  const enrImplied = editorState.enrichmentImpliedTags;

  containerEl.innerHTML = tagsCatalog.map(group => `
    <div class="un-tag-group" data-category="${esc(group.category)}">
      <div class="un-tag-group-label">${esc(group.label)}</div>
      <div class="un-tag-row">
        ${group.tags.map(t => {
          const isLabelImplied = implied.has(t.name);
          const isEnrImplied   = enrImplied.has(t.name);
          const isImplied      = isLabelImplied || isEnrImplied;
          const isActive       = direct.has(t.name) || isImplied;
          const isLocked       = isImplied || isDuplicate;
          const cls = 'chip'
                    + (isActive  ? ' chip-active'      : '')
                    + (isImplied ? ' un-chip-implied'   : '')
                    + (isLocked  ? ' un-chip-locked'    : '');
          const title = isLabelImplied
              ? `Implied by label — ${esc(t.description || '')}`
              : isEnrImplied
                ? `Implied by enrichment — ${esc(t.description || '')}`
                : esc(t.description || t.name);
          return `<button type="button" class="${cls}" data-tag="${esc(t.name)}"
                    ${isLocked ? 'disabled' : ''} title="${title}">${esc(t.name)}</button>`;
        }).join('')}
      </div>
    </div>
  `).join('');

  // Wire click handlers on non-locked chips
  containerEl.querySelectorAll('.chip:not(.un-chip-locked)').forEach(btn => {
    btn.addEventListener('click', () => {
      const tag = btn.getAttribute('data-tag');
      if (!tag) return;
      if (direct.has(tag)) {
        direct.delete(tag);
        btn.classList.remove('chip-active');
      } else {
        direct.add(tag);
        btn.classList.add('chip-active');
      }
      onChange?.();
    });
  });
}
