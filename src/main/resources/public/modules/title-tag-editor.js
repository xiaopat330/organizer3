// Title-detail tag editor modal. Reuses the queue-tag-* CSS so the visual
// treatment (categories, colors, red implicit pills) matches the Queue tool.

import { esc } from './utils.js';

let tagsCatalog = null;

async function ensureCatalog() {
  if (tagsCatalog) return tagsCatalog;
  const res = await fetch('/api/tags');
  if (!res.ok) throw new Error(`tags catalog HTTP ${res.status}`);
  tagsCatalog = await res.json();
  return tagsCatalog;
}

/**
 * Opens the tag-editor modal for a title. Resolves with the refreshed
 * {directTags, labelImpliedTags, effectiveTags} on save, or null on cancel.
 */
export async function openTitleTagEditor(code) {
  const [catalog, stateRes] = await Promise.all([
    ensureCatalog(),
    fetch(`/api/titles/${encodeURIComponent(code)}/tag-state`)
  ]);
  if (!stateRes.ok) throw new Error(`tag-state HTTP ${stateRes.status}`);
  const state = await stateRes.json();
  const direct  = new Set(state.directTags || []);
  const implied = new Set(state.labelImpliedTags || []);
  const initial = new Set(direct);

  return new Promise(resolve => {
    const overlay = document.createElement('div');
    overlay.className = 'tag-modal-overlay';

    const modal = document.createElement('div');
    modal.className = 'tag-modal';

    const header = document.createElement('div');
    header.className = 'tag-modal-header';
    header.innerHTML = `
      <span class="tag-modal-title">Edit tags — <strong>${esc(code)}</strong></span>
      <span class="tag-modal-note">click to toggle · <span class="queue-tag-implicit-sample">red</span> = implied by label (not editable)</span>
    `;
    modal.appendChild(header);

    const body = document.createElement('div');
    body.className = 'tag-modal-body queue-tags-panel';
    body.innerHTML = catalog.map(group => `
      <div class="queue-tag-group tag-cat-${esc(group.category)}">
        <div class="queue-tag-group-label">${esc(group.label)}</div>
        <div class="queue-tag-row">
          ${group.tags.map(t => {
            const isImplied = implied.has(t.name);
            const isActive  = direct.has(t.name) || isImplied;
            const cls = 'queue-tag-toggle'
                      + (isActive  ? ' active'    : '')
                      + (isImplied ? ' implicit'  : '');
            const title = isImplied
                ? `Implied by label (${esc(t.description || '')})`
                : esc(t.description || '');
            return `<button type="button" class="${cls}" data-tag="${esc(t.name)}" ${isImplied ? 'disabled' : ''} title="${title}">${esc(t.name)}</button>`;
          }).join('')}
        </div>
      </div>
    `).join('');
    modal.appendChild(body);

    const footer = document.createElement('div');
    footer.className = 'tag-modal-footer';
    const statusEl = document.createElement('span');
    statusEl.className = 'tag-modal-status';
    const saveBtn = document.createElement('button');
    saveBtn.className = 'tag-modal-save-btn';
    saveBtn.textContent = 'Save';
    saveBtn.disabled = true;
    const cancelBtn = document.createElement('button');
    cancelBtn.className = 'tag-modal-cancel-btn';
    cancelBtn.textContent = 'Cancel';
    footer.appendChild(statusEl);
    footer.appendChild(cancelBtn);
    footer.appendChild(saveBtn);
    modal.appendChild(footer);

    overlay.appendChild(modal);
    document.body.appendChild(overlay);

    function isDirty() {
      if (direct.size !== initial.size) return true;
      for (const t of direct) if (!initial.has(t)) return true;
      return false;
    }
    function refreshSave() { saveBtn.disabled = !isDirty(); }

    body.querySelectorAll('.queue-tag-toggle:not(.implicit)').forEach(btn => {
      btn.addEventListener('click', () => {
        const name = btn.getAttribute('data-tag');
        if (direct.has(name)) { direct.delete(name); btn.classList.remove('active'); }
        else                  { direct.add(name);   btn.classList.add('active'); }
        refreshSave();
      });
    });

    function close(result) {
      document.removeEventListener('keydown', onKey);
      overlay.remove();
      resolve(result);
    }
    const onKey = e => { if (e.key === 'Escape') close(null); };
    document.addEventListener('keydown', onKey);
    cancelBtn.addEventListener('click', () => close(null));
    overlay.addEventListener('click', e => { if (e.target === overlay) close(null); });

    saveBtn.addEventListener('click', async () => {
      if (!isDirty()) return;
      saveBtn.disabled = true;
      statusEl.textContent = 'Saving…';
      try {
        const res = await fetch(`/api/titles/${encodeURIComponent(code)}/tags`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ tags: [...direct].sort() })
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json().catch(() => ({}));
        // Always hand back a locally-computed effective set so the caller
        // doesn't depend on the server's response shape.
        const effective = [...new Set([...direct, ...implied])].sort();
        close({
          directTags: [...direct].sort(),
          labelImpliedTags: [...implied].sort(),
          effectiveTags: effective,
          server: data
        });
      } catch (err) {
        console.error('save tags failed', err);
        statusEl.textContent = 'Save failed: ' + (err.message || err);
        saveBtn.disabled = false;
      }
    });
  });
}
