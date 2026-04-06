#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
./gradlew installDist -q
exec build/install/organizer3/bin/organizer3
