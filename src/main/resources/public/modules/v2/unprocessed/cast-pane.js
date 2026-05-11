/* ─────────────────────────────────────────────────────────────────────
   unprocessed/cast-pane.js — RENDER-ONLY cast slot list (Wave 3).

   Wave 3 scope:
     - Slot header: stage name, javdb slug, resolution badge
     - Resolved slot: linked-actress avatar + canonical name (pick) or
       summary line (create_new / skip / sentinel) + Unlink button
     - Unresolved slot: placeholder ("Picker — Wave 4") instead of picker

   Wave 4 will replace the placeholder with buildPicker() (search,
   create-new form, sentinel dropdown), and Wave 4 will also wire the
   stage-name translation polling loop + near-miss `?` badge.

   Exported:
     mountCastPane(containerEl, state, { onUnlink })
       → { renderCast, destroy }

   onUnlink(javdbSlug, idx) — called when user clicks "Unlink and pick
   different"; draft.js owns the PATCH and re-render.
   ───────────────────────────────────────────────────────────────────── */

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function resolutionLabel(res) {
  if (!res || res === 'unresolved') return 'unresolved';
  if (res === 'pick')       return 'linked';
  if (res === 'create_new') return 'create new';
  if (res === 'skip')       return 'skip';
  if (res.startsWith?.('sentinel:')) return 'sentinel';
  return res;
}

function resolutionCls(res) {
  if (!res || res === 'unresolved') return 'un-cast-res-unresolved';
  if (res === 'pick')       return 'un-cast-res-pick';
  if (res === 'create_new') return 'un-cast-res-create';
  if (res === 'skip')       return 'un-cast-res-skip';
  if (res.startsWith?.('sentinel:')) return 'un-cast-res-sentinel';
  return 'un-cast-res-unresolved';
}

function resolvedSummary(slot) {
  const res = slot.resolution;
  if (res === 'pick') {
    const link = slot.linkToExistingId ? `id:${slot.linkToExistingId}` : '';
    return `Linked to existing actress${link ? ' (' + link + ')' : ''}`;
  }
  if (res === 'create_new') {
    const last  = slot.englishLastName  || '';
    const first = slot.englishFirstName || '';
    const full  = first ? `${first} ${last}` : last;
    return `Create new: ${full || '(name pending)'}`;
  }
  if (res === 'skip') return 'Skipped — will not be linked';
  if (res && res.startsWith('sentinel:')) return 'Replaced with sentinel actress';
  return res || '';
}

/**
 * @param {HTMLElement} containerEl
 * @param {object}      state         — shared state; reads state.draft.cast
 * @param {object}      callbacks
 * @param {Function}    callbacks.onUnlink — (javdbSlug, idx) → void
 * @returns {{ renderCast:Function, destroy:Function }}
 */
export function mountCastPane(containerEl, state, { onUnlink }) {

  function renderCast() {
    const cast = state.draft?.cast || [];
    if (cast.length === 0) {
      containerEl.innerHTML = `
        <div class="un-cast-empty">No cast slots — javdb returned 0 stage names.</div>
      `;
      return;
    }

    containerEl.innerHTML = `<ul class="un-cast-list" id="un-cast-list"></ul>`;
    const listEl = containerEl.querySelector('#un-cast-list');

    cast.forEach((slot, idx) => {
      const li = document.createElement('li');
      li.className = 'un-cast-slot';
      li.dataset.idx = String(idx);
      li.dataset.slug = slot.javdbSlug || '';
      li.appendChild(_buildSlotContent(slot, idx));
      listEl.appendChild(li);
    });
  }

  function _buildSlotContent(slot, idx) {
    const frag = document.createDocumentFragment();

    // ── Header ────────────────────────────────────────────────────
    const header = document.createElement('div');
    header.className = 'un-cast-slot-header';

    const stage = document.createElement('span');
    stage.className = 'un-cast-slot-stage';
    stage.textContent = slot.stageName || '(no stage name)';
    header.appendChild(stage);

    if (slot.javdbSlug) {
      const slug = document.createElement('span');
      slug.className = 'un-cast-slot-slug';
      slug.textContent = slot.javdbSlug;
      header.appendChild(slug);
    }

    const resBadge = document.createElement('span');
    resBadge.className = 'badge un-cast-res ' + resolutionCls(slot.resolution);
    resBadge.textContent = resolutionLabel(slot.resolution);
    header.appendChild(resBadge);

    frag.appendChild(header);

    const isResolved = slot.resolution && slot.resolution !== 'unresolved';

    if (isResolved) {
      // ── Resolved body ──────────────────────────────────────────
      if (slot.resolution === 'pick' && (slot.linkedActressName || slot.linkedActressAvatarUrl)) {
        const linked = document.createElement('div');
        linked.className = 'un-cast-linked';
        if (slot.linkedActressAvatarUrl) {
          const img = document.createElement('img');
          img.className = 'un-cast-linked-avatar';
          img.src = slot.linkedActressAvatarUrl;
          img.alt = slot.linkedActressName || '';
          linked.appendChild(img);
        }
        if (slot.linkedActressName) {
          const name = document.createElement('span');
          name.className = 'un-cast-linked-name';
          name.textContent = slot.linkedActressName;
          linked.appendChild(name);
        }
        frag.appendChild(linked);
      } else {
        const summary = document.createElement('div');
        summary.className = 'un-cast-slot-summary';
        summary.textContent = resolvedSummary(slot);
        frag.appendChild(summary);
      }

      const unlinkBtn = document.createElement('button');
      unlinkBtn.type = 'button';
      unlinkBtn.className = 'btn btn-secondary btn-sm';
      unlinkBtn.textContent = 'Unlink and pick different';
      unlinkBtn.addEventListener('click', () => onUnlink?.(slot.javdbSlug, idx));
      frag.appendChild(unlinkBtn);

    } else {
      // ── Unresolved placeholder (Wave 4 wires real picker) ──────
      const placeholder = document.createElement('div');
      placeholder.className = 'un-cast-picker-placeholder';
      placeholder.textContent = 'Picker — wired in Wave 4';
      frag.appendChild(placeholder);
    }

    return frag;
  }

  function destroy() {
    // No timers / listeners to tear down in Wave 3.
    // (Wave 4 will own polling timer cleanup here.)
  }

  return { renderCast, destroy };
}
