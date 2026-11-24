#!/usr/bin/env bash
set +x
set -euo pipefail
export LC_ALL=C

# Check configuration before release preparation can push commits or tags.
# Authentication and key validity are still checked by Maven and setup-java.
missing=0
for name in MAVEN_USERNAME MAVEN_PASSWORD GPG_PRIVATE_KEY; do
  value=${!name:-}
  if [[ ! "$value" =~ [^[:space:]] ]]; then
    printf '::error::Missing or blank release credential: %s\n' "$name" >&2
    missing=1
  fi
done
exit "$missing"
