#!/usr/bin/env bash
set -euo pipefail
umask 077
if [[ $# -ne 1 || "$1" != /* ]]; then
  printf '%s\n' 'Usage: bash scripts/generate-new-keystore.sh /absolute/private/path/masalik-new.p12' >&2
  exit 2
fi
if [[ -e "$1" ]]; then
  printf '%s\n' 'Refusing to overwrite an existing file.' >&2
  exit 1
fi
command -v keytool || { printf '%s\n' 'Install a JDK and put keytool on PATH.' >&2; exit 1; }
printf '%s\n' 'WARNING: This generates a NEW signing identity. It cannot update the current Masalik installation.'
printf '%s' 'Type NEW to continue: '
read -r confirmation
[[ "$confirmation" == NEW ]] || exit 1
mkdir -p -- "$(dirname -- "$1")"
keytool -genkeypair -keystore "$1" -storetype PKCS12 -alias masalik -keyalg RSA -keysize 3072 -validity 10000
chmod 600 -- "$1"
printf '%s\n' 'New key created. Keep it outside the project. Back it up securely and save its password separately.'
printf '%s\n' 'Use MASALIK_KEY_ALIAS=masalik. For PKCS12, use the same store and key password.'
