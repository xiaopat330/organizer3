#!/usr/bin/env bash
# Nightly batch: research & commit actress profiles for goddess-tier queue.
# Picks the next BATCH_SIZE unprocessed entries, runs /actress-profile for each,
# commits all results on a branch, then merges to main.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
QUEUE_FILE="$PROJECT_DIR/reference/actress-research-queue.json"
BATCH_SIZE=5
DATE=$(date +%Y%m%d-%H%M)
BRANCH="actress-profiles/batch-$DATE"
LOG_DIR="$PROJECT_DIR/logs"
LOG_FILE="$LOG_DIR/actress-profile-batch-$DATE.log"

cd "$PROJECT_DIR"
mkdir -p "$LOG_DIR"

exec > >(tee -a "$LOG_FILE") 2>&1
echo "=== Actress profile batch $DATE ==="

# ── Guard: must be on main with nothing staged ────────────────────────────────
CURRENT=$(git rev-parse --abbrev-ref HEAD)
if [ "$CURRENT" != "main" ]; then
  echo "ERROR: not on main (on $CURRENT). Aborting."
  exit 1
fi
if ! git diff --cached --quiet; then
  echo "ERROR: staged changes present. Aborting."
  exit 1
fi

# ── Pick next batch ───────────────────────────────────────────────────────────
BATCH=$(python3 -c "
import json, sys
with open('$QUEUE_FILE') as f:
    q = json.load(f)
todo = [x for x in q if not x.get('processed')]
batch = todo[:$BATCH_SIZE]
for a in batch:
    stage = a.get('stage_name') or ''
    print(f\"{a['slug']}|{a['name']}|{stage}\")
")

if [ -z "$BATCH" ]; then
  echo "Queue empty — nothing to process. Exiting."
  exit 0
fi

COUNT=$(echo "$BATCH" | wc -l | tr -d ' ')
echo "Processing $COUNT actresses on branch $BRANCH"

git checkout -b "$BRANCH"

# ── Per-actress loop ──────────────────────────────────────────────────────────
PROCESSED_SLUGS=()

while IFS='|' read -r slug name stage_name; do
  echo ""
  echo "── $name ($slug) ──"

  if [ -n "$stage_name" ]; then
    PROMPT="/actress-profile $stage_name ($name)"
  else
    PROMPT="/actress-profile $name"
  fi

  # Snapshot files before run
  BEFORE=$(find src/main/resources/actresses reference/actresses -name "*.yaml" 2>/dev/null | sort || true)

  claude -p "$PROMPT" \
    --dangerously-skip-permissions \
    --model opus \
    2>&1 || {
      echo "WARN: claude exited non-zero for $name — skipping commit for this actress"
      continue
    }

  # Detect new/modified YAML files
  AFTER=$(find src/main/resources/actresses reference/actresses -name "*.yaml" 2>/dev/null | sort || true)
  NEW_FILES=$(comm -13 <(echo "$BEFORE") <(echo "$AFTER"))

  if [ -z "$NEW_FILES" ]; then
    echo "WARN: no YAML files created for $name — not marking processed"
    continue
  fi

  echo "New/updated files:"
  echo "$NEW_FILES" | sed 's/^/  /'

  # Stage and commit
  git add src/main/resources/actresses/ reference/actresses/ reference/actress-research-queue.json

  # Mark processed in queue before committing
  python3 -c "
import json
with open('$QUEUE_FILE') as f:
    q = json.load(f)
for entry in q:
    if entry['slug'] == '$slug':
        entry['processed'] = True
        break
with open('$QUEUE_FILE', 'w') as f:
    json.dump(q, f, ensure_ascii=False, indent=2)
"
  git add "$QUEUE_FILE"

  DISPLAY="${stage_name:-$name}"
  git commit -m "feat(profiles): add $DISPLAY actress profile"

  PROCESSED_SLUGS+=("$slug")
  echo "Committed $name"

done <<< "$BATCH"

# ── Merge to main ─────────────────────────────────────────────────────────────
if [ ${#PROCESSED_SLUGS[@]} -eq 0 ]; then
  echo ""
  echo "No actresses processed successfully. Cleaning up branch."
  git checkout main
  git branch -d "$BRANCH"
  exit 1
fi

echo ""
echo "Merging $BRANCH → main (${#PROCESSED_SLUGS[@]} profiles)"
git checkout main
git merge --no-ff "$BRANCH" -m "feat(profiles): batch actress profiles $DATE (${#PROCESSED_SLUGS[@]} profiles)"
git branch -d "$BRANCH"

echo ""
echo "=== Done. Processed: ${PROCESSED_SLUGS[*]} ==="
