#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

version="${RELEASE_VERSION:-}"
build="${RELEASE_BUILD_DIR-target}"
destination="${RELEASE_ASSET_DIR-release-assets}"
if [[ ! "$version" =~ ^[0-9]+(\.[0-9]+)*([._+-][A-Za-z0-9]+)*$ ]] ||
   [[ "$version" =~ -[Ss][Nn][Aa][Pp][Ss][Hh][Oo][Tt]$ ]]; then
  printf '%s\n' '::error::Invalid release artifact version' >&2
  exit 1
fi
if [[ -z "$build" || -z "$destination" || ! -d "$build" || -L "$build" ]]; then
  printf '%s\n' '::error::Expected a build directory and a new asset directory' >&2
  exit 1
fi
if [[ -e "$destination" || -L "$destination" ]]; then
  printf '%s\n' '::error::Release asset destination already exists; refusing stale assets' >&2
  exit 1
fi

jars=("inqwise-context-$version.jar" "inqwise-context-$version-sources.jar" "inqwise-context-$version-javadoc.jar")
for name in "${jars[@]}"; do
  for file in "$name" "$name.asc"; do
    if [[ ! -f "$build/$file" || ! -s "$build/$file" || -L "$build/$file" ]]; then
      printf '::error::Missing, empty, or linked release artifact: %s\n' "$file" >&2
      exit 1
    fi
  done
done

parent=$(dirname "$destination")
mkdir -p "$parent"
stage=$(mktemp -d "$parent/.release-assets.XXXXXX")
trap 'if [[ -n "$stage" ]]; then rm -rf "$stage"; fi' EXIT
for name in "${jars[@]}"; do
  cp "$build/$name" "$build/$name.asc" "$stage/"
  jar tf "$stage/$name" > /dev/null
  gpg --batch --no-auto-key-retrieve --verify "$stage/$name.asc" "$stage/$name"
done

# Publish only the validated copies, never partial output or cached Maven artifacts.
if [[ -e "$destination" || -L "$destination" ]]; then
  printf '%s\n' '::error::Release asset destination appeared during validation' >&2
  exit 1
fi
mv "$stage" "$destination"
stage=''
printf 'Prepared 3 validated JARs and 3 detached signatures in %s\n' "$destination"
