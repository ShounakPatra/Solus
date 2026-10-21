#!/usr/bin/env bash
set -euo pipefail

sdkmanager="$1"
shift
for attempt in 1 2 3; do
  # Process substitution avoids treating yes's SIGPIPE as an installation failure.
  if "$sdkmanager" --install "$@" < <(yes); then
    exit 0
  fi
  if [ "$attempt" -eq 3 ]; then
    echo "Android SDK installation failed after three attempts." >&2
    exit 1
  fi
  echo "Android SDK install failed (attempt $attempt/3); retrying..." >&2
  sleep 10
done
