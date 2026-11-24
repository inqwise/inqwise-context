#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

case "${RELEASE_EVENT:-}" in
  workflow_dispatch)
    version="${RELEASE_INPUT_VERSION:-}"
    tag="v${version}"
    ;;
  release)
    tag="${RELEASE_TAG_NAME:-}"
    version="${tag#v}"
    ;;
  *)
    printf '%s\n' '::error::Unsupported release event' >&2
    exit 1
    ;;
esac

# Allow numeric Maven versions with optional qualifiers, never shell/ref syntax.
if [[ ! "$version" =~ ^[0-9]+(\.[0-9]+)*([._+-][A-Za-z0-9]+)*$ ]] ||
   [[ "$version" =~ -[Ss][Nn][Aa][Pp][Ss][Hh][Oo][Tt]$ ]]; then
  printf '%s\n' '::error::Expected a non-snapshot release version, without whitespace or shell syntax' >&2
  exit 1
fi

# Preserve the workflow's existing next-development-version calculation.
next_version=$(printf '%s\n' "$version" | awk -F. '{$NF = $NF + 1; print}' OFS=.)
printf 'release_version=%s\nrelease_tag=%s\ndevelopment_version=%s-SNAPSHOT\n' \
  "$version" "$tag" "$next_version" >> "${GITHUB_OUTPUT:?GITHUB_OUTPUT is required}"
