// v2 Admin tab — Per-page staging state.
//
// Identical in contract to the legacy actress-detail-admin/state.js.
// One instance lives in this module; reset on actress change.

let currentActressId = null;
let cardStates = new Map();   // code -> { stages: Stage[], titleData: object }

export function reset(actressId) {
  currentActressId = actressId;
  cardStates = new Map();
}

export function setCardData(code, titleData) {
  cardStates.set(code, { stages: [], titleData });
}

export function getCardData(code) {
  const s = cardStates.get(code);
  return s ? s.titleData : null;
}

export function getStages(code) {
  const s = cardStates.get(code);
  return s ? s.stages : [];
}

export function getPendingCount(code) {
  return getStages(code).filter(x => x.status === 'pending').length;
}

export function getTotalPendingCount() {
  let n = 0;
  for (const s of cardStates.values()) {
    n += s.stages.filter(x => x.status === 'pending').length;
  }
  return n;
}

export function hasStagedChanges() {
  return getTotalPendingCount() > 0;
}

export function clearStages(code) {
  const s = cardStates.get(code);
  if (s) s.stages = [];
}

export function clearAllStages() {
  for (const s of cardStates.values()) s.stages = [];
}

// Normalize key: treat null and undefined as null so comparisons are stable.
function normalizeKey(key) {
  return key ?? null;
}

// Match predicate: same kind and same key (null == null).
function stageMatches(stage, kind, key) {
  return stage.kind === kind && stage.key === normalizeKey(key);
}

export function addStage(code, kind, payload, key = null) {
  key = normalizeKey(key);
  const s = cardStates.get(code);
  if (!s) return null;
  const existing = s.stages.findIndex(x => stageMatches(x, kind, key) && x.status === 'pending');
  const stage = { kind, key, payload, status: 'pending' };
  if (existing >= 0) s.stages[existing] = stage;
  else s.stages.push(stage);
  return stage;
}

export function removePendingStage(code, kind, key = null) {
  key = normalizeKey(key);
  const s = cardStates.get(code);
  if (!s) return;
  s.stages = s.stages.filter(x => !(stageMatches(x, kind, key) && x.status === 'pending'));
}

export function findPendingStage(code, kind, key = null) {
  key = normalizeKey(key);
  return getStages(code).find(x => stageMatches(x, kind, key) && x.status === 'pending') || null;
}

export function markStageCommitted(code, kind, key = null) {
  key = normalizeKey(key);
  const s = cardStates.get(code);
  if (!s) return;
  for (const stage of s.stages) {
    if (stageMatches(stage, kind, key) && stage.status === 'pending') stage.status = 'committed';
  }
}

export function markStageFailed(code, kind, error, key = null) {
  key = normalizeKey(key);
  const s = cardStates.get(code);
  if (!s) return;
  for (const stage of s.stages) {
    if (stageMatches(stage, kind, key) && stage.status === 'pending') {
      stage.status = 'failed';
      stage.error  = error;
    }
  }
}

export function actressId() {
  return currentActressId;
}
