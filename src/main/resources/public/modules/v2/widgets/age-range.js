/* ─────────────────────────────────────────────────────────────────────
   Shared age-range dual-handle slider widget.
   Reuses the existing `tit-lib-age*` CSS classes (defined in
   css/library.css) so styling applies on any page that loads it.
   Element ids are derived from an `idPrefix` so the widget can be
   instantiated more than once per page.
   ───────────────────────────────────────────────────────────────────── */

export const AGE_MIN = 18, AGE_MAX = 50;

const pct = v => ((v - AGE_MIN) / (AGE_MAX - AGE_MIN)) * 100;

export function ageRangeHtml(lo, hi, { idPrefix = 'age' } = {}) {
  const active = (lo > AGE_MIN || hi < AGE_MAX);
  const isAny  = (lo <= AGE_MIN && hi >= AGE_MAX);
  return `<span class="tit-lib-age${active ? ' active' : ''}" id="${idPrefix}">
    <span class="tit-lib-age-label${isAny ? '' : ' active'}" id="${idPrefix}-label">${isAny ? `Age: Any <span class="tit-lib-age-nums">${lo}–${hi}</span>` : `Age ${lo}–${hi}`}</span>
    <span class="tit-lib-age-track">
      <span class="tit-lib-age-fill" id="${idPrefix}-fill"
            style="left:${pct(lo)}%;right:${100 - pct(hi)}%"></span>
      <input type="range" class="tit-lib-age-input" id="${idPrefix}-min"
             min="${AGE_MIN}" max="${AGE_MAX}" step="1" value="${lo}" aria-label="Minimum age">
      <input type="range" class="tit-lib-age-input" id="${idPrefix}-max"
             min="${AGE_MIN}" max="${AGE_MAX}" step="1" value="${hi}" aria-label="Maximum age">
    </span>
  </span>`;
}

export function wireAgeRange(rootEl, { idPrefix = 'age', getLo, getHi, setLo, setHi, onChange }) {
  const minEl   = rootEl.querySelector(`#${idPrefix}-min`);
  const maxEl   = rootEl.querySelector(`#${idPrefix}-max`);
  const wrapEl  = rootEl.querySelector(`#${idPrefix}`);
  const labelEl = rootEl.querySelector(`#${idPrefix}-label`);
  const fillEl  = rootEl.querySelector(`#${idPrefix}-fill`);
  if (!minEl || !maxEl || !wrapEl || !labelEl || !fillEl) return;

  const update = () => {
    const lo = getLo();
    const hi = getHi();
    fillEl.style.left  = `${pct(lo)}%`;
    fillEl.style.right = `${100 - pct(hi)}%`;
    const isAny  = (lo <= AGE_MIN && hi >= AGE_MAX);
    const active = (lo > AGE_MIN || hi < AGE_MAX);
    labelEl.innerHTML = isAny
      ? `Age: Any <span class="tit-lib-age-nums">${lo}–${hi}</span>`
      : `Age ${lo}–${hi}`;
    labelEl.classList.toggle('active', !isAny);
    wrapEl.classList.toggle('active', active);
  };

  minEl.addEventListener('input', () => {
    let lo = Math.min(parseInt(minEl.value, 10), getHi());
    if (String(lo) !== minEl.value) minEl.value = String(lo);
    setLo(lo);
    update();
    onChange();
  });
  maxEl.addEventListener('input', () => {
    let hi = Math.max(parseInt(maxEl.value, 10), getLo());
    if (String(hi) !== maxEl.value) maxEl.value = String(hi);
    setHi(hi);
    update();
    onChange();
  });

  update();
}
