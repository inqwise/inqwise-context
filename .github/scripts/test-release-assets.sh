#!/usr/bin/env bash
set -euo pipefail

directory=$(mktemp -d /tmp/release-assets-test.XXXXXX)
keyring="$directory/keyring"
cleanup() {
  gpgconf --homedir "$keyring" --kill gpg-agent > /dev/null 2>&1 || true
  rm -rf "$directory"
}
trap cleanup EXIT
mkdir -m 700 "$keyring"
export GNUPGHOME="$keyring"
collector="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/prepare-release-assets.sh"
version=1.2.0
prefix="inqwise-context-$version"
fixtures="$directory/fixtures"
build="$directory/build files"
destination="$directory/release assets"
errors="$directory/errors"
count=0
mkdir "$fixtures" "$directory/payload" "$directory/empty-keyring"
chmod 700 "$directory/empty-keyring"
printf '%s\n' 'Synthetic release artifact' > "$directory/payload/fixture.txt"
if ! gpg --batch --pinentry-mode loopback --passphrase '' --quick-generate-key \
    'Release Asset Regression <release-assets@example.invalid>' ed25519 sign 0 > "$errors" 2>&1; then
  cat "$errors" >&2
  exit 1
fi

jars=("$prefix.jar" "$prefix-sources.jar" "$prefix-javadoc.jar")
files=()
for name in "${jars[@]}"; do
  printf 'Synthetic release artifact: %s\n' "$name" > "$directory/payload/fixture.txt"
  jar cf "$fixtures/$name" -C "$directory/payload" .
  gpg --batch --yes --pinentry-mode loopback --passphrase '' --armor --detach-sign \
    --output "$fixtures/$name.asc" "$fixtures/$name" 2> "$errors"
  files+=("$name" "$name.asc")
done

reset_case() {
  rm -rf "$build" "$destination"
  mkdir "$build"
  cp "$fixtures/"* "$build/"
}

collect() {
  env RELEASE_VERSION="$version" RELEASE_BUILD_DIR="$build" RELEASE_ASSET_DIR="$destination" \
    bash "$collector" > "$directory/output" 2> "$errors"
}

reject() {
  if collect; then
    printf '%s\n' 'Invalid release assets were accepted' >&2
    exit 1
  fi
  [[ ! -e "$destination" ]] || { printf '%s\n' 'Failed validation published partial assets' >&2; exit 1; }
  [[ -z "$(find "$directory" -maxdepth 1 -name '.release-assets.*' -print)" ]] || {
    printf '%s\n' 'Failed validation leaked staging files' >&2; exit 1;
  }
  count=$((count + 1))
}

reset_case
printf '%s\n' stale > "$build/inqwise-context-0.0.1.jar"
printf '%s\n' unrelated > "$build/unrelated.jar"
if ! collect; then cat "$errors" >&2; exit 1; fi
[[ "$(find "$destination" -type f | wc -l | tr -d ' ')" == 6 ]]
for file in "${files[@]}"; do cmp "$fixtures/$file" "$destination/$file"; done
count=$((count + 1))

for file in "${files[@]}"; do
  reset_case
  rm "$build/$file"
  reject
  reset_case
  : > "$build/$file"
  reject
  reset_case
  rm "$build/$file"
  ln -s "$fixtures/$file" "$build/$file"
  reject
done

for name in "${jars[@]}"; do
  reset_case
  printf '%s\n' invalid-signature > "$build/$name.asc"
  reject
  reset_case
  printf '%s\n' modified >> "$build/$name"
  reject
done

reset_case
cp "$build/${jars[0]}.asc" "$build/${jars[1]}.asc"
reject

reset_case
printf '%s\n' 'Signed but not a JAR' > "$build/${jars[0]}"
gpg --batch --yes --pinentry-mode loopback --passphrase '' --armor --detach-sign \
  --output "$build/${jars[0]}.asc" "$build/${jars[0]}" 2> "$errors"
reject

reset_case
if GNUPGHOME="$directory/empty-keyring" collect; then
  printf '%s\n' 'Unknown signing key was accepted' >&2; exit 1
fi
[[ ! -e "$destination" ]]
count=$((count + 1))

for kind in directory file link; do
  reset_case
  case "$kind" in
    directory) mkdir "$destination"; printf '%s\n' stale > "$destination/marker" ;;
    file) printf '%s\n' stale > "$destination" ;;
    link) ln -s "$fixtures" "$destination" ;;
  esac
  if collect; then printf '%s\n' 'Existing asset destination was accepted' >&2; exit 1; fi
  case "$kind" in
    directory) [[ "$(< "$destination/marker")" == stale ]] ;;
    file) [[ "$(< "$destination")" == stale ]] ;;
    link) [[ -L "$destination" ]] ;;
  esac
  count=$((count + 1))
done

for invalid in '' '1.2.0-SNAPSHOT' '../1.2.0' '1.2.0;exit 0'; do
  reset_case
  saved=$version
  version=$invalid
  reject
  version=$saved
done

reset_case
rm -rf "$build"
reject
ln -s "$fixtures" "$build"
reject
rm "$build"
mkdir "$build"
for empty in source destination; do
  reset_case
  if [[ "$empty" == source ]]; then
    if RELEASE_BUILD_DIR='' RELEASE_VERSION="$version" RELEASE_ASSET_DIR="$destination" bash "$collector" 2> "$errors"; then exit 1; fi
  else
    if RELEASE_ASSET_DIR='' RELEASE_VERSION="$version" RELEASE_BUILD_DIR="$build" bash "$collector" 2> "$errors"; then exit 1; fi
  fi
  count=$((count + 1))
done
printf 'Passed %s release-asset regression checks with an isolated test signing key\n' "$count"
