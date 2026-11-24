#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

version="${RELEASE_VERSION:-}"
tag="${RELEASE_TAG:-}"
if [[ ! "$version" =~ ^[0-9]+(\.[0-9]+)*([._+-][A-Za-z0-9]+)*$ ]] ||
   [[ "$version" =~ -[Ss][Nn][Aa][Pp][Ss][Hh][Oo][Tt]$ ]] ||
   [[ "$tag" != "$version" && "$tag" != "v$version" ]]; then
  printf '%s\n' '::error::Release version and tag do not match' >&2
  exit 1
fi
prefix=$(git rev-parse --show-prefix)
[[ -z "$prefix" ]] || {
  printf '%s\n' '::error::Release validation must run from the repository root' >&2; exit 1;
}
commit=$(git rev-parse --verify "refs/tags/$tag^{commit}")
if [[ -n "${RELEASE_COMMIT:-}" && "$commit" != "$RELEASE_COMMIT" ]]; then
  printf '%s\n' '::error::Release tag changed after source verification' >&2
  exit 1
fi

check_source() {
  local head current_tag status
  head=$(git rev-parse --verify HEAD)
  current_tag=$(git rev-parse --verify "refs/tags/$tag^{commit}")
  status=$(git status --porcelain --untracked-files=all)
  if [[ "$head" != "$commit" || "$current_tag" != "$commit" || -n "$status" ]]; then
    printf '%s\n' '::error::Release source must be the unchanged, clean tagged commit' >&2
    exit 1
  fi
}
check_source

scratch=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/release-source-check.XXXXXX")
trap 'rm -rf "$scratch"' EXIT
mvn -B -ntp org.apache.maven.plugins:maven-help-plugin:3.5.2:evaluate \
  -Dexpression=project.id "-Doutput=$scratch/project.id"
if [[ ! -s "$scratch/project.id" ]] ||
   [[ "$(< "$scratch/project.id")" != "com.inqwise:inqwise-context:jar:$version" ]]; then
  printf '%s\n' '::error::Tagged Maven coordinates do not match the requested release' >&2
  exit 1
fi
check_source
printf 'release_commit=%s\n' "$commit" >> "${GITHUB_OUTPUT:?GITHUB_OUTPUT is required}"
printf 'Verified tagged release source at %s\n' "$commit"
