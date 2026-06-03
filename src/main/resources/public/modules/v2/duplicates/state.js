/* ─────────────────────────────────────────────────────────────────────
   duplicates/state.js — shared mutable state factory.
   Passed as a plain object into every sibling module.
   ───────────────────────────────────────────────────────────────────── */

export function createState() {
  return {
    allDuplicates:      [],         // flat TitleSummary[] from API
    actressGroups:      new Map(),  // actressKey → { name, key, titles }
    decisions:          new Map(),  // titleCode → Map<locIdx, decision>
    currentActressKey:  null,
    currentLetterFilter:'All',
    sortField:          'count',    // 'count' | 'name'
    sortDir:            'desc',     // 'asc'   | 'desc'
    volumeId:           '',         // volume filter (retained from v2 volume-picker)
    notesByCode:        new Map(),  // titleCode → NoteState | null  (post-it notes §5e)
    notesCachedKey:     null,       // actressKey whose notes are in notesByCode
    videosByCode:       new Map(),  // titleCode → Video[][] (per-location, cached after first fetch)
  };
}
