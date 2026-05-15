// api-client.js — Thin fetch wrapper for the /api/notes/* endpoints (§4)
//
// NoteState shape: { body: string, createdAt: number, updatedAt: number }
//
// getNote(type, id)          → Promise<NoteState | null>  (404 → null)
// putNote(type, id, body)    → Promise<NoteState | null>  (empty body deletes; 204 → null)
// deleteNote(type, id)       → Promise<void>
// batchNotes(type, ids)      → Promise<{ [id: string]: NoteState }>

const BASE = '/api/notes';

/**
 * Fetches a single note.
 * @param {'actress'|'title'} type
 * @param {string} id
 * @returns {Promise<NoteState | null>}
 */
export async function getNote(type, id) {
  const resp = await fetch(`${BASE}/${encodeURIComponent(type)}/${encodeURIComponent(id)}`);
  if (resp.status === 404) return null;
  if (!resp.ok) throw new Error(`getNote failed: ${resp.status} ${resp.statusText}`);
  return resp.json();
}

/**
 * Creates or updates a note. If body is empty the server deletes the row
 * (per §4 "PUT with empty body deletes"). Server returns 204 in that case → null.
 * @param {'actress'|'title'} type
 * @param {string} id
 * @param {string} body
 * @returns {Promise<NoteState | null>}
 */
export async function putNote(type, id, body) {
  const resp = await fetch(`${BASE}/${encodeURIComponent(type)}/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ body }),
  });
  if (resp.status === 204) return null;
  if (!resp.ok) throw new Error(`putNote failed: ${resp.status} ${resp.statusText}`);
  return resp.json();
}

/**
 * Deletes a note. Resolves normally on 204; throws on other non-2xx.
 * @param {'actress'|'title'} type
 * @param {string} id
 * @returns {Promise<void>}
 */
export async function deleteNote(type, id) {
  const resp = await fetch(`${BASE}/${encodeURIComponent(type)}/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
  if (!resp.ok) throw new Error(`deleteNote failed: ${resp.status} ${resp.statusText}`);
}

/**
 * Batch-fetches notes for a list of entity IDs (one API round-trip for a grid page).
 * @param {'actress'|'title'} type
 * @param {string[]} ids
 * @returns {Promise<{ [id: string]: NoteState }>}
 */
export async function batchNotes(type, ids) {
  if (!ids || ids.length === 0) return {};
  const resp = await fetch(`${BASE}/batch`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type, ids }),
  });
  if (!resp.ok) throw new Error(`batchNotes failed: ${resp.status} ${resp.statusText}`);
  return resp.json();
}
