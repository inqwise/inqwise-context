#!/usr/bin/env bash
set -euo pipefail

# Short paths avoid GnuPG agent socket path limits on macOS.
directory=$(mktemp -d /tmp/release-signing-test.XXXXXX)
cleanup() {
  for keyring in "$directory/"*-keyring; do
    gpgconf --homedir "$keyring" --kill gpg-agent > /dev/null 2>&1 || true
  done
  rm -rf "$directory"
}
trap cleanup EXIT
guard="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/verify-release-signing.sh"
mkdir "$directory/temp"
export RUNNER_TEMP="$directory/temp"
passphrase='synthetic-signing-passphrase-private'
count=0
for kind in protected unprotected empty public; do
  mkdir -m 700 "$directory/$kind-keyring"
done
for kind in protected unprotected; do
  secret=''
  if [[ "$kind" == protected ]]; then secret=$passphrase; fi
  if ! printf '%s\n' "$secret" | gpg --homedir "$directory/$kind-keyring" --batch \
      --pinentry-mode loopback --passphrase-fd 0 --quick-generate-key \
      'Signing Preflight Fixture <signing-preflight@example.invalid>' ed25519 sign 0 > "$directory/setup.log" 2>&1; then
    cat "$directory/setup.log" >&2; exit 1
  fi
done
gpg --homedir "$directory/unprotected-keyring" --batch --export > "$directory/public.gpg"
gpg --homedir "$directory/public-keyring" --batch --import "$directory/public.gpg" > "$directory/setup.log" 2>&1

check_output() {
  [[ "$(< "$directory/output")" != *synthetic-* && "$(< "$directory/output")" != *signing-preflight@example.invalid* ]] || {
    printf '%s\n' 'Signing preflight leaked sensitive details' >&2; exit 1;
  }
  [[ -z "$(find "$RUNNER_TEMP" -maxdepth 1 -name 'release-signing-check.*' -print)" ]] || {
    printf '%s\n' 'Signing preflight left probe files behind' >&2; exit 1;
  }
  count=$((count + 1))
}

run_guard() {
  # Cold-agent cases ensure incorrect passphrases cannot use cached unlock state.
  gpgconf --homedir "$GNUPGHOME" --kill gpg-agent > /dev/null 2>&1 || true
  bash -x "$guard" > "$directory/output" 2>&1
}

export GNUPGHOME="$directory/protected-keyring"
if ! MAVEN_GPG_PASSPHRASE="$passphrase" run_guard; then cat "$directory/output" >&2; exit 1; fi
check_output
for secret in '' synthetic-wrong-passphrase; do
  if MAVEN_GPG_PASSPHRASE="$secret" run_guard; then
    printf '%s\n' 'Cold protected key signed with an invalid passphrase' >&2; exit 1
  fi
  check_output
done
export GNUPGHOME="$directory/unprotected-keyring"
if ! (unset MAVEN_GPG_PASSPHRASE; run_guard); then cat "$directory/output" >&2; exit 1; fi
check_output
for kind in empty public; do
  export GNUPGHOME="$directory/$kind-keyring"
  if MAVEN_GPG_PASSPHRASE='' run_guard; then
    printf '%s\n' 'Signing succeeded without a secret key' >&2; exit 1
  fi
  check_output
done

real_gpg=$(command -v gpg)
mkdir "$directory/bin"
cat > "$directory/bin/gpg" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
for argument in "$@"; do
  if [[ "$argument" == --verify && "${SIGNING_TEST_MODE:-}" == verify ]]; then
    printf '%s\n' 'synthetic-verification-error-private' >&2
    exit 1
  fi
  if [[ "$argument" == --detach-sign && "${SIGNING_TEST_MODE:-}" == omit ]]; then
    cat > /dev/null
    exit 0
  fi
done
exec "$SIGNING_TEST_GPG" "$@"
STUB
chmod +x "$directory/bin/gpg"
export SIGNING_TEST_GPG="$real_gpg" PATH="$directory/bin:$PATH"
export GNUPGHOME="$directory/unprotected-keyring"
for mode in verify omit; do
  if SIGNING_TEST_MODE="$mode" MAVEN_GPG_PASSPHRASE='' run_guard; then
    printf '%s\n' 'Invalid preflight signature was accepted' >&2; exit 1
  fi
  [[ "$(< "$directory/output")" == *'::error::Release signing preflight signature could not be verified'* ]]
  check_output
done
printf 'Passed %s release-signing checks with isolated temporary keys\n' "$count"
