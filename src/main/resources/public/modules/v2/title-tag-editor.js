// v2 Title Tag Editor modal.
// Ports legacy title-tag-editor.js into the v2 modal primitive
// (.modal-backdrop + .modal + .modal-header/body/footer + .btn).
// Tag group panel uses .tged-* classes (library.css) so the v2
// page doesn't need to load the legacy title-editor.css.
// API contracts are identical to the legacy surface:
//   GET  /api/tags
//   GET  /api/titles/{code}/tag-state
//   PUT  /api/titles/{code}/tags   body: { tags: string[] }

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

let tagsCatalog = null;

async function ensureCatalog() {
  if (tagsCatalog) return tagsCatalog;
  const res = await fetch('/api/tags');
  if (!res.ok) throw new Error(`tags catalog HTTP ${res.status}`);
  tagsCatalog = await res.json();
  return tagsCatalog;
}

/**
 * Opens the v2 tag-editor modal for a title.
 * Resolves with the refreshed tag sets on save, or null on cancel.
 *
 * Return shape on save:
 *   { directTags, labelImpliedTags, enrichmentImpliedTags, effectiveTags, server }
 */
export async function openTitleTagEditor(code) {
  const [catalog, stateRes] = await Promise.all([
    ensureCatalog(),
    fetch(`/api/titles/${encodeURIComponent(code)}/tag-state`),
  ]);
  if (!stateRes.ok) throw new Error(`tag-state HTTP ${stateRes.status}`);
  const state = await stateRes.json();

  const direct     = new Set(state.directTags || []);
  const implied    = new Set(state.labelImpliedTags || []);
  const enrImplied = new Set(state.enrichmentImpliedTags || []);
  const initial    = new Set(direct);

  return new Promise(resolve => {
    /* ── Overlay / modal shell ─────────────────────────────────────── */
    const backdrop = document.createElement('div');
    backdrop.className = 'modal-backdrop';

    const modal = document.createElement('div');
    modal.className = 'modal lg';

    /* ── Header ────────────────────────────────────────────────────── */
    const header = document.createElement('div');
    header.className = 'modal-header';
    header.innerHTML = `
      <span class="modal-title">
        Edit tags — <strong style="color:var(--accent)">${esc(code)}</strong>
      </span>
      <span class="tged-note">
        click to toggle ·
        <span class="tged-implicit-sample">red</span> = implied by label or enrichment (not editable)
      </span>
    `;
    modal.appendChild(header);

    /* ── Body — tag group panel ────────────────────────────────────── */
    const body = document.createElement('div');
    body.className = 'modal-body tged-panel';
    body.innerHTML = catalog.map(group => `
      <div class="tged-group tged-cat-${esc(group.category)}">
        <div class="tged-group-label">${esc(group.label)}</div>
        <div class="tged-tag-row">
          ${group.tags.map(t => {
            const isLabelImplied = implied.has(t.name);
            const isEnrImplied   = enrImplied.has(t.name);
            const isImplied      = isLabelImplied || isEnrImplied;
            const isActive       = direct.has(t.name) || isImplied;
            const cls = 'tged-tag'
                      + (isActive  ? ' active'   : '')
                      + (isImplied ? ' implicit' : '');
            const titleAttr = isLabelImplied
                ? `Implied by label${t.description ? ' (' + esc(t.description) + ')' : ''}`
                : isEnrImplied
                  ? `Implied by enrichment${t.description ? ' (' + esc(t.description) + ')' : ''}`
                  : (t.description ? esc(t.description) : '');
            return `<button type="button" class="${cls}" data-tag="${esc(t.name)}"${isImplied ? ' disabled' : ''}${titleAttr ? ` title="${titleAttr}"` : ''}>${esc(t.name)}</button>`;
          }).join('')}
        </div>
      </div>
    `).join('');
    modal.appendChild(body);

    /* ── Footer ────────────────────────────────────────────────────── */
    const footer = document.createElement('div');
    footer.className = 'modal-footer';

    const statusEl = document.createElement('span');
    statusEl.className = 'tged-status';

    const cancelBtn = document.createElement('button');
    cancelBtn.className = 'btn';
    cancelBtn.textContent = 'Cancel';

    const saveBtn = document.createElement('button');
    saveBtn.className = 'btn primary';
    saveBtn.textContent = 'Save';
    saveBtn.disabled = true;

    // Status left-aligned; buttons right-aligned (modal-footer is flex-end by default)
    // Wrap status in a filler span so buttons stay right.
    const statusWrap = document.createElement('span');
    statusWrap.style.flex = '1';
    statusWrap.appendChild(statusEl);

    footer.appendChild(statusWrap);
    footer.appendChild(cancelBtn);
    footer.appendChild(saveBtn);
    modal.appendChild(footer);

    backdrop.appendChild(modal);
    document.body.appendChild(backdrop);

    /* ── Dirty tracking ────────────────────────────────────────────── */
    function isDirty() {
      if (direct.size !== initial.size) return true;
      for (const t of direct) if (!initial.has(t)) return true;
      return false;
    }
    function refreshSave() { saveBtn.disabled = !isDirty(); }

    /* ── Tag toggles ───────────────────────────────────────────────── */
    body.querySelectorAll('.tged-tag:not(.implicit)').forEach(btn => {
      btn.addEventListener('click', () => {
        const name = btn.getAttribute('data-tag');
        if (direct.has(name)) {
          direct.delete(name);
          btn.classList.remove('active');
        } else {
          direct.add(name);
          btn.classList.add('active');
        }
        refreshSave();
      });
    });

    /* ── Close helper ──────────────────────────────────────────────── */
    function close(result) {
      document.removeEventListener('keydown', onKey);
      backdrop.remove();
      resolve(result);
    }
    const onKey = e => { if (e.key === 'Escape') close(null); };
    document.addEventListener('keydown', onKey);
    cancelBtn.addEventListener('click', () => close(null));
    backdrop.addEventListener('click', e => { if (e.target === backdrop) close(null); });

    /* ── Save ──────────────────────────────────────────────────────── */
    saveBtn.addEventListener('click', async () => {
      if (!isDirty()) return;
      saveBtn.disabled = true;
      cancelBtn.disabled = true;
      statusEl.textContent = 'Saving…';
      try {
        const res = await fetch(`/api/titles/${encodeURIComponent(code)}/tags`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ tags: [...direct].sort() }),
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json().catch(() => ({}));
        // Compute effective set locally so caller doesn't depend on server response shape.
        const effective = [...new Set([...direct, ...implied, ...enrImplied])].sort();
        close({
          directTags: [...direct].sort(),
          labelImpliedTags: [...implied].sort(),
          enrichmentImpliedTags: [...enrImplied].sort(),
          effectiveTags: effective,
          server: data,
        });
      } catch (err) {
        console.error('save tags failed', err);
        statusEl.textContent = 'Save failed: ' + (err.message || err);
        saveBtn.disabled = false;
        cancelBtn.disabled = false;
      }
    });
  });
}
