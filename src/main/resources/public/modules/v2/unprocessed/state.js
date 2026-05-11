/* ─────────────────────────────────────────────────────────────────────
   unprocessed/state.js — createState() factory per §4.2 spec.

   All Wave modules share a single state object created in index.js.
   Mutate directly; no proxy or pub/sub — caller re-renders.
   ───────────────────────────────────────────────────────────────────── */

/**
 * @returns {UnprocessedState}
 */
export function createState() {
  return {
    // ── Queue ─────────────────────────────────────────────────────────
    /** @type {Array<{titleId:number, code:string, folderName:string, actressCount:number, hasCover:boolean, complete:boolean}>} */
    queueRows:       [],
    /** @type {Set<number>} */
    draftedTitleIds: new Set(),
    showComplete:    false,
    /** @type {number|null} */
    currentId:       null,

    // ── Editor ────────────────────────────────────────────────────────
    /** Full GET /api/unsorted/titles/:id response */
    detail:       null,
    /** Full GET /api/drafts/:id response, or null when no-draft */
    draft:        null,
    isDraftMode:  false,

    // ── No-draft editor sub-state ─────────────────────────────────────
    /**
     * Populated by buildEditorState(detail).
     * {
     *   actresses: [{id, canonicalName, stageName, primary, isNew, newName?}],
     *   descriptor: string,
     *   directTags: Set<string>,
     *   labelImpliedTags: Set<string>,
     *   enrichmentImpliedTags: Set<string>,
     *   coverStaged: null | {kind:'bytes', file:File, previewUrl:string}
     *                      | {kind:'url', url:string, previewUrl:string},
     *   coverDirty: boolean,
     *   hasExistingCover: boolean,
     *   initialActresses: string,   // JSON snapshot for dirty check
     *   initialDescriptor: string,
     *   initialTags: string,        // JSON-sorted snapshot for dirty check
     * }
     */
    editorState: null,

    // ── Tags catalog (shared, lazily fetched) ─────────────────────────
    /** @type {Array<{category:string, label:string, tags:Array<{name:string, description:string}>}>|null} */
    tagsCatalog: null,

    // ── Stage-name polling (Wave 4) ───────────────────────────────────
    /** @type {Map<string, number>} javdbSlug → timeoutId */
    pollTimers:    new Map(),
    /** @type {Set<string>} javdbSlug of slots with user-touched picker input */
    dirtySlots:    new Set(),
    /** @type {Set<string>} javdbSlug of slots suppressing auto-fill */
    suppressInput: new Set(),

    // ── Sentinels cache (Wave 4) ──────────────────────────────────────
    sentinelsCache: null,

    // ── Bulk enrich (Wave 5) ──────────────────────────────────────────
    bulkPlan: null,
  };
}

/**
 * Build the no-draft editorState sub-object from a detail response.
 * @param {object} detail — GET /api/unsorted/titles/:id response
 * @returns {object}
 */
export function buildEditorState(detail) {
  const actresses = (detail.detail?.actresses || []).map(a => ({
    id:            a.actressId,
    canonicalName: a.canonicalName,
    stageName:     a.stageName,
    primary:       a.primary,
    isNew:         false,
  }));
  const descriptor = detail.descriptor || '';
  const directTags = (detail.directTags || []).slice().sort();
  return {
    actresses,
    descriptor,
    directTags:            new Set(directTags),
    labelImpliedTags:      new Set(detail.labelImpliedTags || []),
    enrichmentImpliedTags: new Set(detail.enrichmentImpliedTags || []),
    coverStaged:           null,
    coverDirty:            false,
    hasExistingCover:      !!detail.hasCover,
    initialActresses:      JSON.stringify(actresses),
    initialDescriptor:     descriptor,
    initialTags:           JSON.stringify(directTags),
  };
}
