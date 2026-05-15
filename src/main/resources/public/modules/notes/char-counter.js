// char-counter.js — Live character counter for textarea inputs
//
// attachCharCounter(textareaEl, counterEl, { max }) → detach function
//
// Updates counterEl.textContent to "N / 280" on each input event.
// Toggles .over-limit on counterEl when N > max.
// Returns a no-arg function that removes the listener.

/**
 * Attaches a live character counter to a textarea.
 *
 * @param {HTMLTextAreaElement} textareaEl  - the textarea to watch
 * @param {HTMLElement}         counterEl   - element whose textContent is updated
 * @param {object}              [opts]
 * @param {number}              [opts.max=280] - character limit
 * @returns {() => void} detach function — call to remove the listener
 */
export function attachCharCounter(textareaEl, counterEl, { max = 280 } = {}) {
  function update() {
    const n = textareaEl.value.length;
    counterEl.textContent = `${n} / ${max}`;
    counterEl.classList.toggle('over-limit', n > max);
  }

  // Set initial state
  update();

  textareaEl.addEventListener('input', update);

  return function detach() {
    textareaEl.removeEventListener('input', update);
  };
}
