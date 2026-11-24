#!/usr/bin/env bash
set +x
set -euo pipefail

scratch=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/release-signing-check.XXXXXX")
trap 'rm -rf "$scratch"' EXIT
printf '%s\n' 'inqwise-context release signing preflight' > "$scratch/probe"

# Match Maven's default key selection without placing the passphrase in argv.
if ! printf '%s\n' "${MAVEN_GPG_PASSPHRASE:-}" |
    gpg --batch --no-tty --pinentry-mode loopback --passphrase-fd 0 \
      --armor --detach-sign --output "$scratch/probe.asc" "$scratch/probe" > "$scratch/log" 2>&1; then
  printf '%s\n' '::error::Release signing preflight failed; check the imported key and passphrase' >&2
  exit 1
fi
if ! gpg --batch --no-tty --no-auto-key-retrieve --verify "$scratch/probe.asc" "$scratch/probe" > "$scratch/log" 2>&1; then
  printf '%s\n' '::error::Release signing preflight signature could not be verified' >&2
  exit 1
fi
printf '%s\n' 'Verified release signing availability'
