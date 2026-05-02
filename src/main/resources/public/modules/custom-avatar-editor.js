import { esc } from './utils.js';

// ── Minimum crop size in natural image pixels ─────────────────────────────
const MIN_NAT_PX = 150;

/**
 * Opens the custom-avatar editor modal for an actress.
 *
 * @param {number}   actressId
 * @param {boolean}  hasCustomAvatar  True when a user-curated avatar already exists.
 * @param {function} onDone           Called (with no args) after a successful save or delete.
 */
export async function openCustomAvatarEditor(actressId, hasCustomAvatar, onDone) {
  const overlay = document.createElement('div');
  overlay.className = 'cae-overlay';
  document.body.appendChild(overlay);

  const modal = document.createElement('div');
  modal.className = 'cae-modal';
  overlay.appendChild(modal);

  // Close on backdrop click or Escape.
  function close() {
    document.removeEventListener('keydown', onKey);
    overlay.remove();
  }
  const onKey = e => { if (e.key === 'Escape') close(); };
  document.addEventListener('keydown', onKey);
  overlay.addEventListener('click', e => { if (e.target === overlay) close(); });

  // ── Shared header ─────────────────────────────────────────────────────────
  const header = document.createElement('div');
  header.className = 'cae-header';
  header.innerHTML = '<span class="cae-title">Set profile image</span>';
  const closeBtn = document.createElement('button');
  closeBtn.className = 'cae-close-btn';
  closeBtn.textContent = '✕';
  closeBtn.addEventListener('click', close);
  header.appendChild(closeBtn);
  modal.appendChild(header);

  const body = document.createElement('div');
  body.className = 'cae-body';
  modal.appendChild(body);

  showPickerStep(body, actressId, hasCustomAvatar, close, onDone);
}

// ── Step 1: Picker ────────────────────────────────────────────────────────

async function showPickerStep(body, actressId, hasCustomAvatar, close, onDone) {
  body.innerHTML = '<div class="cae-loading">Loading covers…</div>';

  let covers;
  try {
    const res = await fetch(`/api/actresses/${actressId}/title-covers`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    covers = await res.json();
  } catch (err) {
    body.innerHTML = `<div class="cae-error">Could not load covers: ${esc(err.message)}</div>`;
    return;
  }

  body.innerHTML = '';

  const grid = document.createElement('div');
  grid.className = 'cae-cover-grid';
  body.appendChild(grid);

  // "Remove" tile lives inside the grid so it participates in grid sizing.
  if (hasCustomAvatar) {
    const blankTile = document.createElement('div');
    blankTile.className = 'cae-cover-tile cae-blank-tile';
    blankTile.title = 'Remove custom avatar';
    blankTile.innerHTML = `<div class="cae-blank-inner">
      <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.2" stroke="currentColor" aria-hidden="true">
        <path stroke-linecap="round" stroke-linejoin="round" d="M6 18 18 6M6 6l12 12" />
      </svg>
      <span>Remove</span>
    </div>`;
    blankTile.addEventListener('click', async () => {
      blankTile.classList.add('cae-tile-loading');
      try {
        const res = await fetch(`/api/actresses/${actressId}/custom-avatar`, { method: 'DELETE' });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        close();
        onDone();
      } catch (err) {
        blankTile.classList.remove('cae-tile-loading');
        grid.insertAdjacentHTML('beforebegin', `<div class="cae-error">Could not remove avatar: ${esc(err.message)}</div>`);
      }
    });
    grid.appendChild(blankTile);
  }

  if (covers.length === 0) {
    grid.insertAdjacentHTML('afterbegin', '<div class="cae-empty">No covers available for this actress.</div>');
    return;
  }

  for (const c of covers) {
    const tile = document.createElement('div');
    tile.className = 'cae-cover-tile';
    tile.title = `${c.label} ${c.code}`;
    const img = document.createElement('img');
    img.src = esc(c.coverUrl);
    img.alt = `${c.label} ${c.code}`;
    img.loading = 'lazy';
    img.className = 'cae-cover-img';
    tile.appendChild(img);
    tile.addEventListener('click', () => {
      showCropperStep(body, actressId, c.coverUrl, close, onDone);
    });
    grid.appendChild(tile);
  }
}

// ── Step 2: Cropper ───────────────────────────────────────────────────────

function showCropperStep(body, actressId, coverUrl, close, onDone) {
  body.innerHTML = '<div class="cae-loading">Loading image…</div>';

  const img = new Image();
  img.crossOrigin = 'anonymous';
  img.onload = () => initCropper(body, actressId, img, coverUrl, close, onDone);
  img.onerror = () => {
    body.innerHTML = '<div class="cae-error">Could not load image.</div>';
  };
  img.src = coverUrl;
}

function initCropper(body, actressId, img, coverUrl, close, onDone) {
  body.innerHTML = '';

  // Canvas fills the body area.
  const canvas = document.createElement('canvas');
  canvas.className = 'cae-canvas';
  body.appendChild(canvas);

  const footer = document.createElement('div');
  footer.className = 'cae-footer';
  const backBtn = document.createElement('button');
  backBtn.className = 'cae-btn cae-btn-secondary';
  backBtn.textContent = 'Back';
  const statusEl = document.createElement('span');
  statusEl.className = 'cae-status';
  const okBtn = document.createElement('button');
  okBtn.className = 'cae-btn cae-btn-primary';
  okBtn.textContent = 'OK';
  okBtn.disabled = true;
  footer.appendChild(backBtn);
  footer.appendChild(statusEl);
  footer.appendChild(okBtn);
  body.appendChild(footer);

  // ── Layout: fit image into available canvas area ──────────────────────
  // Disable body scroll and padding in cropper mode; restored when leaving.
  body.style.overflow = 'hidden';
  body.style.padding = '0';
  body.style.gap = '0';

  // We defer size computation until after the canvas is in the DOM and
  // the body has layout.
  requestAnimationFrame(() => {
    const bodyRect  = body.getBoundingClientRect();
    const footerH   = footer.getBoundingClientRect().height;
    const canvasW   = Math.floor(bodyRect.width);
    const canvasH   = Math.max(200, Math.floor(bodyRect.height - footerH));

    canvas.width  = canvasW;
    canvas.height = canvasH;
    canvas.style.height = canvasH + 'px';

    const natW = img.naturalWidth;
    const natH = img.naturalHeight;
    const scale = Math.min(canvasW / natW, canvasH / natH);
    const dispW = natW * scale;
    const dispH = natH * scale;
    const offX  = (canvasW - dispW) / 2;
    const offY  = (canvasH - dispH) / 2;

    const ctx = canvas.getContext('2d');

    // Rect stored in natural pixel space (source of truth).
    // { x, y, size } or null while not yet drawn.
    let natRect = null;

    // Mouse state.
    let dragging = false;
    let dragMode = null;       // 'draw' | 'move'
    let natDragStart = null;   // { x, y } in natural pixels
    let moveOffset  = null;    // { dx, dy } from rect.{x,y} to mousedown point, for 'move' mode

    function clampNat(v, max) { return Math.max(0, Math.min(v, max)); }

    function toNat(clientX, clientY) {
      const r = canvas.getBoundingClientRect();
      const dx = clientX - r.left - offX;
      const dy = clientY - r.top  - offY;
      return {
        x: clampNat(dx / scale, natW),
        y: clampNat(dy / scale, natH),
      };
    }

    function pointInRect(p, rect) {
      return rect != null
          && p.x >= rect.x && p.x <= rect.x + rect.size
          && p.y >= rect.y && p.y <= rect.y + rect.size;
    }

    // Free-sized square rect anchored at startNat, with the opposite corner
    // tracking endNat. Min size enforcement is deferred to OK button — this
    // keeps the drag feel free.
    function computeRect(startNat, endNat) {
      const dxNat = endNat.x - startNat.x;
      const dyNat = endNat.y - startNat.y;
      let size = Math.max(Math.abs(dxNat), Math.abs(dyNat));

      const signX = dxNat >= 0 ? 1 : -1;
      const signY = dyNat >= 0 ? 1 : -1;

      // Clamp to image boundaries by shrinking size if needed.
      if (signX > 0) size = Math.min(size, natW - startNat.x);
      else           size = Math.min(size, startNat.x);
      if (signY > 0) size = Math.min(size, natH - startNat.y);
      else           size = Math.min(size, startNat.y);

      size = Math.max(size, 0);

      return {
        x: signX >= 0 ? startNat.x : startNat.x - size,
        y: signY >= 0 ? startNat.y : startNat.y - size,
        size,
      };
    }

    // Translate the existing rect so the mousedown-relative point follows the cursor.
    function moveRect(curNat) {
      if (!natRect || !moveOffset) return natRect;
      const size = natRect.size;
      let nx = curNat.x - moveOffset.dx;
      let ny = curNat.y - moveOffset.dy;
      nx = Math.max(0, Math.min(nx, natW - size));
      ny = Math.max(0, Math.min(ny, natH - size));
      return { x: nx, y: ny, size };
    }

    function redraw() {
      ctx.clearRect(0, 0, canvasW, canvasH);

      // Dark letterbox outside image area.
      ctx.fillStyle = '#0a0a0a';
      ctx.fillRect(0, 0, canvasW, canvasH);

      // Draw the image.
      ctx.drawImage(img, offX, offY, dispW, dispH);

      if (!natRect) return;

      // Convert rect to display coordinates.
      const dx = natRect.x * scale + offX;
      const dy = natRect.y * scale + offY;
      const ds = natRect.size * scale;

      // Semi-transparent overlay outside selection.
      ctx.fillStyle = 'rgba(0, 0, 0, 0.55)';
      ctx.fillRect(offX, offY, dispW, dispH);
      ctx.clearRect(dx, dy, ds, ds);
      // Re-draw the image inside the selection so it's fully visible.
      ctx.drawImage(img, natRect.x, natRect.y, natRect.size, natRect.size, dx, dy, ds, ds);

      // Selection border.
      ctx.strokeStyle = '#ffffff';
      ctx.lineWidth = 1.5;
      ctx.strokeRect(dx + 0.75, dy + 0.75, ds - 1.5, ds - 1.5);
    }

    function updateStatus() {
      if (!natRect || natRect.size < MIN_NAT_PX) {
        statusEl.textContent = `Select a region (min ${MIN_NAT_PX}×${MIN_NAT_PX} px)`;
        okBtn.disabled = true;
      } else {
        statusEl.textContent = `${natRect.size}×${natRect.size} px`;
        okBtn.disabled = false;
      }
    }

    redraw();
    updateStatus();

    function startGesture(natPt) {
      dragging = true;
      if (pointInRect(natPt, natRect)) {
        dragMode = 'move';
        moveOffset = { dx: natPt.x - natRect.x, dy: natPt.y - natRect.y };
      } else {
        dragMode = 'draw';
        natDragStart = natPt;
        natRect = { x: natPt.x, y: natPt.y, size: 0 };
      }
    }

    function continueGesture(natPt) {
      if (!dragging) return;
      if (dragMode === 'move') natRect = moveRect(natPt);
      else                     natRect = computeRect(natDragStart, natPt);
      redraw();
      updateStatus();
    }

    canvas.addEventListener('mousedown', e => {
      if (e.button !== 0) return;
      startGesture(toNat(e.clientX, e.clientY));
      e.preventDefault();
    });

    canvas.addEventListener('mousemove', e => {
      // Update cursor to hint at move-mode when hovering inside the rect.
      if (!dragging) {
        canvas.style.cursor = pointInRect(toNat(e.clientX, e.clientY), natRect)
            ? 'move' : 'crosshair';
        return;
      }
      continueGesture(toNat(e.clientX, e.clientY));
    });

    function endDrag() {
      if (!dragging) return;
      dragging = false;
      dragMode = null;
      updateStatus();
    }
    canvas.addEventListener('mouseup', endDrag);
    canvas.addEventListener('mouseleave', endDrag);

    // Touch support (single-finger drag).
    canvas.addEventListener('touchstart', e => {
      if (e.touches.length !== 1) return;
      startGesture(toNat(e.touches[0].clientX, e.touches[0].clientY));
      e.preventDefault();
    }, { passive: false });

    canvas.addEventListener('touchmove', e => {
      if (!dragging || e.touches.length !== 1) return;
      continueGesture(toNat(e.touches[0].clientX, e.touches[0].clientY));
      e.preventDefault();
    }, { passive: false });

    canvas.addEventListener('touchend', endDrag);

    backBtn.addEventListener('click', () => {
      body.style.overflow = '';
      body.style.padding = '';
      body.style.gap = '';
      reloadPickerStep(body, actressId, close, onDone);
    });

    okBtn.addEventListener('click', async () => {
      if (!natRect || natRect.size < MIN_NAT_PX || okBtn.disabled) return;
      okBtn.disabled = true;
      okBtn.textContent = 'Saving…';
      statusEl.textContent = '';
      try {
        const blob = await cropToBlob(img, natRect);
        const res = await fetch(`/api/actresses/${actressId}/custom-avatar`, {
          method: 'POST',
          headers: { 'Content-Type': blob.type },
          body: blob,
        });
        if (!res.ok) {
          let msg;
          if (res.status === 400) {
            const body2 = await res.json().catch(() => null);
            msg = (body2 && (body2.error || body2.message)) ? (body2.error || body2.message) : `Validation error (HTTP ${res.status})`;
          } else {
            msg = `Could not save (HTTP ${res.status})`;
          }
          statusEl.textContent = msg;
          statusEl.classList.add('cae-status--error');
          okBtn.disabled = false;
          okBtn.textContent = 'OK';
          return;
        }
        body.style.overflow = '';
        body.style.padding = '';
        body.style.gap = '';
        close();
        onDone();
      } catch (err) {
        statusEl.textContent = 'Could not save: ' + err.message;
        statusEl.classList.add('cae-status--error');
        okBtn.disabled = false;
        okBtn.textContent = 'OK';
      }
    });
  });
}

async function reloadPickerStep(body, actressId, close, onDone) {
  // Re-fetch actress summary to get current hasCustomAvatar state.
  let hasCustomAvatar = false;
  try {
    const res = await fetch(`/api/actresses/${actressId}`);
    if (res.ok) {
      const data = await res.json();
      hasCustomAvatar = !!data.hasCustomAvatar;
    }
  } catch (_) { /* ignore; picker will just omit blank tile */ }
  showPickerStep(body, actressId, hasCustomAvatar, close, onDone);
}

function cropToBlob(img, natRect) {
  return new Promise((resolve, reject) => {
    const size = natRect.size;
    const offscreen = document.createElement('canvas');
    offscreen.width  = size;
    offscreen.height = size;
    const ctx = offscreen.getContext('2d');
    ctx.drawImage(img, natRect.x, natRect.y, size, size, 0, 0, size, size);
    offscreen.toBlob(blob => {
      if (blob) resolve(blob);
      else reject(new Error('canvas.toBlob returned null'));
    }, 'image/jpeg', 0.88);
  });
}
