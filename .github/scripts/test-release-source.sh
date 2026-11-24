#!/usr/bin/env bash
set -euo pipefail

directory=$(mktemp -d "${TMPDIR:-/tmp}/release-source-test.XXXXXX")
trap 'rm -rf "$directory"' EXIT
guard="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/verify-release-source.sh"
real_maven=$(command -v mvn || true)
real_git=$(command -v git)
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_COMMON_DIR
export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null
repository="$directory/repository"
mkdir "$directory/template" "$directory/bin" "$directory/temp" "$directory/outside"
git init -q -b main --template="$directory/template" "$repository"
git -C "$repository" config user.name 'Release Source Regression'
git -C "$repository" config user.email release-source@example.invalid
git -C "$repository" config commit.gpgsign false
git -C "$repository" config tag.gpgsign false
printf '%s\n' target > "$repository/.gitignore"
printf '%s\n' original > "$repository/fixture.txt"
printf '%s\n' '<project xmlns="http://maven.apache.org/POM/4.0.0">' \
  '<modelVersion>4.0.0</modelVersion><groupId>com.inqwise</groupId><artifactId>inqwise-context</artifactId>' \
  '<version>${revision}</version><properties><revision>1.2.0</revision></properties></project>' > "$repository/pom.xml"
git -C "$repository" add .gitignore fixture.txt pom.xml
git -C "$repository" commit -qm fixture
commit=$(git -C "$repository" rev-parse HEAD)
git -C "$repository" tag 1.2.0
git -C "$repository" tag -a v1.2.0 -m 'Annotated release fixture'
tag_object=$(git -C "$repository" rev-parse refs/tags/v1.2.0)
printf '%s\n' another > "$repository/fixture.txt"
git -C "$repository" commit -qam another
other=$(git -C "$repository" rev-parse HEAD)
git -C "$repository" branch v1.2.0
git -C "$repository" branch v3.0.0
blob=$(git -C "$repository" rev-parse HEAD:fixture.txt)
git -C "$repository" update-ref refs/tags/v4.0.0 "$blob"

cat > "$directory/bin/git" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == status && ( "${SOURCE_TEST_FAIL_STATUS:-0}" == 1 || -e "$SOURCE_TEST_ROOT/fail-status" ) ]]; then
  exit 1
fi
exec "$SOURCE_TEST_GIT" "$@"
STUB

# The default regression suite permits only model evaluation, never release/deploy goals.
cat > "$directory/bin/mvn" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
[[ $# == 5 && "$1" == -B && "$2" == -ntp &&
   "$3" == org.apache.maven.plugins:maven-help-plugin:3.5.2:evaluate &&
   "$4" == -Dexpression=project.id && "$5" == -Doutput=* ]] || exit 90
output=${5#-Doutput=}
[[ "$output" == "$SOURCE_TEST_ROOT/"* ]] || exit 91
printf '%s\n' evaluated >> "$SOURCE_TEST_LOG"
if [[ "${SOURCE_TEST_REAL:-0}" == 1 ]]; then
  exec "$SOURCE_TEST_MAVEN" --offline "$@"
fi
case "${SOURCE_TEST_MODE:-normal}" in
  fail) exit 1 ;;
  omit) exit 0 ;;
  dirty) printf '%s\n' changed > fixture.txt ;;
  untracked) printf '%s\n' changed > injected.java ;;
  head) git checkout --quiet --detach "$SOURCE_TEST_OTHER" ;;
  tag) git update-ref "refs/tags/$RELEASE_TAG" "$SOURCE_TEST_OTHER" ;;
  status) touch "$SOURCE_TEST_ROOT/fail-status" ;;
esac
printf '%s' "${SOURCE_TEST_COORDINATES-com.inqwise:inqwise-context:jar:1.2.0}" > "$output"
STUB
chmod +x "$directory/bin/mvn" "$directory/bin/git"
export PATH="$directory/bin:$PATH"
export SOURCE_TEST_ROOT="$directory" SOURCE_TEST_LOG="$directory/maven.log"
export SOURCE_TEST_OTHER="$other" SOURCE_TEST_MAVEN="$real_maven"
export SOURCE_TEST_GIT="$real_git"
export RUNNER_TEMP="$directory/temp" GITHUB_OUTPUT="$directory/output"
export RELEASE_VERSION=1.2.0 RELEASE_TAG=v1.2.0
count=0
index=0

new_case() {
  index=$((index + 1))
  worktree="$directory/source $index"
  git -C "$repository" worktree add --quiet --detach "$worktree" "$commit"
  : > "$GITHUB_OUTPUT"
  : > "$SOURCE_TEST_LOG"
  rm -f "$directory/fail-status"
}

accept() {
  if ! (cd "$worktree" && bash "$guard") > "$directory/stdout" 2> "$directory/errors"; then
    cat "$directory/errors" >&2; exit 1
  fi
  [[ "$(< "$GITHUB_OUTPUT")" == "release_commit=$commit" ]] || exit 1
  count=$((count + 1))
}

reject() {
  local evaluated=$1
  if (cd "$worktree" && bash "$guard") > "$directory/stdout" 2> "$directory/errors"; then
    printf '%s\n' 'Invalid release source was accepted' >&2; exit 1
  fi
  [[ ! -s "$GITHUB_OUTPUT" ]] || { printf '%s\n' 'Invalid source wrote provenance output' >&2; exit 1; }
  if [[ "$evaluated" == no ]]; then [[ ! -s "$SOURCE_TEST_LOG" ]]; else [[ -s "$SOURCE_TEST_LOG" ]]; fi
  [[ -z "$(find "$RUNNER_TEMP" -maxdepth 1 -name 'release-source-check.*' -print)" ]] || exit 1
  count=$((count + 1))
}

new_case; accept
new_case; RELEASE_TAG=1.2.0 accept
new_case; RELEASE_COMMIT="$commit" accept
new_case; RELEASE_COMMIT="$other" reject no
new_case
git -C "$repository" update-ref refs/tags/v1.2.0 "$other"
git -C "$worktree" checkout --quiet --detach "$other"
RELEASE_COMMIT="$commit" reject no
git -C "$repository" update-ref refs/tags/v1.2.0 "$tag_object"
new_case; mkdir "$worktree/target"; printf '%s\n' build > "$worktree/target/ignored.log"; accept
new_case; git -C "$worktree" checkout --quiet --detach "$other"; reject no
new_case; RELEASE_VERSION=9.9.9 RELEASE_TAG=v9.9.9 reject no
new_case; RELEASE_VERSION=3.0.0 RELEASE_TAG=v3.0.0 reject no
new_case; RELEASE_VERSION=4.0.0 RELEASE_TAG=v4.0.0 reject no
new_case; RELEASE_TAG=v1.2.1 reject no
new_case; printf '%s\n' dirty > "$worktree/fixture.txt"; reject no
new_case; printf '%s\n' dirty > "$worktree/fixture.txt"; git -C "$worktree" add fixture.txt; reject no
new_case; printf '%s\n' untracked > "$worktree/injected.java"; reject no
new_case; mkdir "$worktree/submodule"; worktree="$worktree/submodule"; reject no
new_case; worktree="$directory/outside"; reject no
new_case; SOURCE_TEST_FAIL_STATUS=1 reject no
for value in '' 1.2.0-SNAPSHOT '../1.2.0' '1.2.0;exit 0' $'1.2.0\nrelease_commit=injected'; do
  new_case; RELEASE_VERSION="$value" reject no
done
for coordinates in '' com.other:inqwise-context:jar:1.2.0 com.inqwise:other:jar:1.2.0 \
    com.inqwise:inqwise-context:pom:1.2.0 com.inqwise:inqwise-context:jar:1.2.1 \
    com.inqwise:inqwise-context:jar:1.2.0-SNAPSHOT; do
  new_case; SOURCE_TEST_COORDINATES="$coordinates" reject yes
done
for mode in fail omit dirty untracked head tag status; do
  new_case; SOURCE_TEST_MODE="$mode" reject yes
  git -C "$repository" update-ref refs/tags/v1.2.0 "$tag_object"
done
if [[ "${RUN_REAL_MAVEN:-0}" == 1 ]]; then
  [[ -n "$real_maven" ]] || { printf '%s\n' 'Maven is required for the real model check' >&2; exit 1; }
  new_case; SOURCE_TEST_REAL=1 accept
fi
printf 'Passed %s release-source regression checks\n' "$count"
