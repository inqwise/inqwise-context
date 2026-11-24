#!/usr/bin/env bash
set -euo pipefail

directory=$(mktemp -d "${TMPDIR:-/tmp}/release-version-test.XXXXXX")
trap 'rm -rf "$directory"' EXIT
resolver="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/resolve-release-version.sh"
output="$directory/output"
errors="$directory/errors"
count=0

accept() {
  local event=$1 input=$2 tag=$3 expected_version=$4 expected_tag=$5 expected_development=$6
  : > "$output"
  (cd "$directory" && env RELEASE_EVENT="$event" RELEASE_INPUT_VERSION="$input" RELEASE_TAG_NAME="$tag" GITHUB_OUTPUT="$output" \
    bash "$resolver")
  local expected
  expected=$(printf 'release_version=%s\nrelease_tag=%s\ndevelopment_version=%s\n' \
    "$expected_version" "$expected_tag" "$expected_development")
  if [[ "$(< "$output")" != "$expected" ]]; then
    printf '%s\n' 'Unexpected release outputs' >&2
    exit 1
  fi
  count=$((count + 1))
}

reject() {
  local event=$1 input=$2 tag=$3
  : > "$output"
  if (cd "$directory" && env RELEASE_EVENT="$event" RELEASE_INPUT_VERSION="$input" RELEASE_TAG_NAME="$tag" GITHUB_OUTPUT="$output" \
      bash "$resolver") 2> "$errors"; then
    printf '%s\n' 'Invalid release input was accepted' >&2
    exit 1
  fi
  [[ ! -s "$output" ]] || { printf '%s\n' 'Invalid input wrote workflow outputs' >&2; exit 1; }
  [[ ! -e "$directory/marker" ]] || { printf '%s\n' 'Input executed a shell substitution' >&2; exit 1; }
  count=$((count + 1))
}

accept workflow_dispatch 1.2.0 ignored 1.2.0 v1.2.0 1.2.1-SNAPSHOT
accept workflow_dispatch 1.1.11 ignored 1.1.11 v1.1.11 1.1.12-SNAPSHOT
accept workflow_dispatch 1.2.0-RC1 ignored 1.2.0-RC1 v1.2.0-RC1 1.2.1-SNAPSHOT
accept release ignored v1.2.0 1.2.0 v1.2.0 1.2.1-SNAPSHOT
accept release ignored 1.0.0 1.0.0 1.0.0 1.0.1-SNAPSHOT
accept release ignored v1.2.0-RC1 1.2.0-RC1 v1.2.0-RC1 1.2.1-SNAPSHOT
accept release ignored v1.2.0+build 1.2.0+build v1.2.0+build 1.2.1-SNAPSHOT

for value in '' 'v1.2.0' '-1.2.0' '1..2' '1.2.0-' ' 1.2.0' '1.2.0 ' '1.2.0/SNAPSHOT' \
    '1.2.0-SNAPSHOT' '1.2.0-sNaPsHoT' '1.2.0;exit 0' '1.2.0";exit 0;#' "1.2.0';touch marker;#" \
    '1.2.0$(touch marker)' '1.2.0`touch marker`' $'1.2.0\nrelease_tag=injected' $'1.2.0\r'; do
  reject workflow_dispatch "$value" ''
done
for value in '' v v1.2.0-SNAPSHOT 'v1.2.0;exit 0' 'v1.2.0$(touch marker)' $'v1.2.0\nrelease_tag=injected'; do
  reject release '' "$value"
done
reject pull_request 1.2.0 v1.2.0
reject '' 1.2.0 v1.2.0

: > "$output"
if env -u GITHUB_OUTPUT RELEASE_EVENT=workflow_dispatch RELEASE_INPUT_VERSION=1.2.0 \
    bash "$resolver" > "$output" 2> "$errors"; then
  printf '%s\n' 'Missing workflow output path was accepted' >&2
  exit 1
fi
[[ ! -s "$output" ]] || { printf '%s\n' 'Missing output path leaked workflow outputs' >&2; exit 1; }
count=$((count + 1))

: > "$output"
env RELEASE_EVENT=workflow_dispatch RELEASE_INPUT_VERSION=1.2.0 RELEASE_TAG_NAME='' GITHUB_OUTPUT="$output" bash "$resolver"
env RELEASE_EVENT=workflow_dispatch RELEASE_INPUT_VERSION=1.2.1 RELEASE_TAG_NAME='' GITHUB_OUTPUT="$output" bash "$resolver"
[[ "$(wc -l < "$output" | tr -d ' ')" == 6 ]] || { printf '%s\n' 'Resolver did not append workflow outputs' >&2; exit 1; }
count=$((count + 1))
printf 'Passed %s release-version regression checks\n' "$count"
