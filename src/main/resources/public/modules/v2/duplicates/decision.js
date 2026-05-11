/* ─────────────────────────────────────────────────────────────────────
   duplicates/decision.js — set/persist/guard/auto-keep logic.
   Pure decision state; no DOM. Mirrors legacy utilities-duplicate-triage.js.
   ───────────────────────────────────────────────────────────────────── */

// ── Persistence helpers ───────────────────────────────────────────────

export async function persistDecision(titleCode, loc, decision) {
  try {
    if (decision === null) {
      await fetch(
        `/api/tools/duplicates/decisions/${encodeURIComponent(titleCode)}/${encodeURIComponent(loc.volumeId)}?nasPath=${encodeURIComponent(loc.nasPath)}`,
        { method: 'DELETE' }
      );
    } else {
      await fetch('/api/tools/duplicates/decisions', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ titleCode, volumeId: loc.volumeId, nasPath: loc.nasPath, decision }),
      });
    }
  } catch (err) {
    console.error('[duplicates] Failed to persist decision for', titleCode, loc.nasPath, err);
  }
}

export async function loadAllDecisions(allDuplicates) {
  const decisions = new Map();
  try {
    const res = await fetch('/api/tools/duplicates/decisions');
    if (!res.ok) return decisions;
    const rows = await res.json();
    for (const row of rows) {
      const title = allDuplicates.find(t => t.code === row.titleCode);
      if (!title) continue;
      const locs = title.locationEntries || [];
      const locIdx = locs.findIndex(l => l.volumeId === row.volumeId && l.nasPath === row.nasPath);
      if (locIdx === -1) continue;
      if (!decisions.has(row.titleCode)) decisions.set(row.titleCode, new Map());
      decisions.get(row.titleCode).set(locIdx, row.decision);
    }
  } catch (err) {
    console.warn('[duplicates] Failed to load saved decisions', err);
  }
  return decisions;
}

// ── Guard: cannot trash the last surviving copy ───────────────────────

export function isLastNonTrashed(title, locs, candidateIdx, decisions) {
  const dec = decisions.get(title.code) || new Map();
  const nonTrashedOthers = locs.filter((_, i) => {
    if (i === candidateIdx) return false;
    return dec.get(i) !== 'TRASH';
  });
  return nonTrashedOthers.length === 0;
}

// ── Apply a decision with auto-keep promotion ─────────────────────────
// Returns the updated decisions Map (mutates in-place for simplicity).

export function applyDecision(state, title, locs, locIdx, decision) {
  const { decisions } = state;
  if (!decisions.has(title.code)) decisions.set(title.code, new Map());
  const dec = decisions.get(title.code);

  if (decision === null) {
    dec.delete(locIdx);
  } else {
    dec.set(locIdx, decision);
  }

  persistDecision(title.code, locs[locIdx], decision);

  // Auto-keep: if TRASH leaves exactly one undecided survivor, promote it
  if (decision === 'TRASH') {
    const survivors = locs.reduce((acc, _, i) => {
      if (dec.get(i) !== 'TRASH') acc.push(i);
      return acc;
    }, []);
    if (survivors.length === 1 && !dec.has(survivors[0])) {
      const autoIdx = survivors[0];
      dec.set(autoIdx, 'KEEP');
      persistDecision(title.code, locs[autoIdx], 'KEEP');
    }
  }
}

// ── Group-level stat helpers ──────────────────────────────────────────

export function countCleaned(allDuplicates, decisions) {
  let n = 0;
  for (const title of allDuplicates) {
    const locs = title.locationEntries || [];
    const dec  = decisions.get(title.code);
    if (!dec) continue;
    if (locs.every((_, i) => dec.has(i))) n++;
  }
  return n;
}

export function pendingTrashCount(decisions) {
  let n = 0;
  for (const [, dec] of decisions) {
    for (const [, d] of dec) { if (d === 'TRASH') n++; }
  }
  return n;
}

export function actressTrashCount(group, decisions) {
  let n = 0;
  for (const title of group.titles) {
    const dec = decisions.get(title.code);
    if (!dec) continue;
    for (const [, d] of dec) { if (d === 'TRASH') n++; }
  }
  return n;
}

export function groupResolutionState(group, decisions) {
  let resolved = 0;
  for (const t of group.titles) {
    const locs = t.locationEntries || [];
    const dec  = decisions.get(t.code);
    if (dec && locs.every((_, i) => dec.has(i))) resolved++;
  }
  const total = group.titles.length;
  const pct   = total > 0 ? resolved / total : 0;
  if (pct >= 1) return { state: 'complete', pct: 1 };
  if (pct > 0)  return { state: 'partial',  pct };
  return { state: 'none', pct: 0 };
}

// ── Actress-group builder ─────────────────────────────────────────────

export function buildActressGroups(allDuplicates) {
  const actressGroups = new Map();
  for (const title of allDuplicates) {
    const key  = title.actressId ? `id:${title.actressId}` : `name:${title.actressName || '(no actress)'}`;
    const name = title.actressName || '(no actress)';
    if (!actressGroups.has(key)) {
      actressGroups.set(key, { name, key, titles: [] });
    }
    actressGroups.get(key).titles.push(title);
  }
  return actressGroups;
}

// ── Filtered + sorted actress groups ─────────────────────────────────

export function filteredGroups(state) {
  const { actressGroups, currentLetterFilter, sortField, sortDir } = state;
  let entries = [...actressGroups.entries()];
  if (currentLetterFilter !== 'All') {
    entries = entries.filter(([, g]) => g.name.charAt(0).toUpperCase() === currentLetterFilter);
  }
  entries.sort(([, a], [, b]) => {
    let cmp;
    if (sortField === 'name') {
      cmp = a.name.localeCompare(b.name);
    } else {
      cmp = a.titles.length - b.titles.length;
    }
    return sortDir === 'asc' ? cmp : -cmp;
  });
  return entries;
}

export function occupiedLetters(actressGroups) {
  const seen = new Set();
  for (const [, group] of actressGroups) {
    const ch = group.name.charAt(0).toUpperCase();
    if (ch >= 'A' && ch <= 'Z') seen.add(ch);
  }
  return [...seen].sort();
}
