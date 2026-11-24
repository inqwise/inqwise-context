#!/usr/bin/env bash
set -euo pipefail

directory=$(mktemp -d "${TMPDIR:-/tmp}/release-credentials-test.XXXXXX")
trap 'rm -rf "$directory"' EXIT
guard="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/verify-release-credentials.sh"
count=0

# Use synthetic credentials only, regardless of the caller's environment.
export MAVEN_USERNAME='fixture-user-private'
export MAVEN_PASSWORD='fixture-password-private'
export GPG_PRIVATE_KEY=$'fixture-signing-key-private\nsecond-line-private'
unset MAVEN_GPG_PASSPHRASE

check_output() {
  if [[ "$(< "$directory/output")" == *fixture-* || "$(< "$directory/output")" == *second-line-private* ]]; then
    printf '%s\n' 'Credential contents leaked to output' >&2
    exit 1
  fi
  [[ ! -e "$directory/marker" ]] || { printf '%s\n' 'Credential executed shell code' >&2; exit 1; }
  count=$((count + 1))
}

bash "$guard" > "$directory/output" 2>&1
[[ ! -s "$directory/output" ]]
check_output
bash -x "$guard" > "$directory/output" 2>&1
check_output

for name in MAVEN_USERNAME MAVEN_PASSWORD GPG_PRIVATE_KEY; do
  if (unset "$name"; bash "$guard") > "$directory/output" 2>&1; then
    printf '%s\n' 'Absent credential was accepted' >&2; exit 1
  fi
  [[ "$(< "$directory/output")" == "::error::Missing or blank release credential: $name" ]]
  check_output
  for value in '' ' ' $'\t\r\n' $' \t \n '; do
    if env "$name=$value" bash -x "$guard" > "$directory/output" 2>&1; then
      printf '%s\n' 'Blank credential was accepted' >&2; exit 1
    fi
    [[ "$(< "$directory/output")" == *"::error::Missing or blank release credential: $name"* ]]
    check_output
  done
done

if (unset MAVEN_USERNAME MAVEN_PASSWORD GPG_PRIVATE_KEY; bash "$guard") > "$directory/output" 2>&1; then
  printf '%s\n' 'Missing credentials were accepted' >&2; exit 1
fi
[[ "$(wc -l < "$directory/output" | tr -d ' ')" == 3 ]]
check_output

for value in '  fixture-password-private  ' $'fixture-password-private\n' "\$(touch '$directory/marker')fixture-password-private"; do
  env "MAVEN_PASSWORD=$value" bash -x "$guard" > "$directory/output" 2>&1
  check_output
done
printf 'Passed %s release-credential checks using synthetic secrets only\n' "$count"
