#!/usr/bin/env bash
# Prepares the APK signing key for CI and writes keystore.properties (read by smarttubetv/build.gradle).
#
# Usage: setup-signing.sh <testkey|shared>
# The certificate is signing/<name>.x509.pem (a public AOSP key). Its private half is taken from
# the PK8_B64 environment variable (base64 of <name>.pk8, from a secret) when set, otherwise it is
# downloaded from the AOSP mirror on GitHub. Either way it must match the committed certificate,
# or the build stops: an APK must never be signed with a key that is not the one in the repo.
set -euo pipefail

name="${1:?usage: setup-signing.sh <testkey|shared>}"
case "$name" in testkey|shared) ;; *) echo "unknown key: $name" >&2; exit 2;; esac

root="$(cd "$(dirname "$0")/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

cert="$root/signing/$name.x509.pem"
pk8="$work/$name.pk8"

if [ -n "${PK8_B64:-}" ]; then
  printf '%s' "$PK8_B64" | base64 -d > "$pk8"
else
  curl -fsSL --retry 3 -o "$pk8" \
    https://raw.githubusercontent.com/aosp-mirror/platform_build/master/target/product/security/$name.pk8
fi

openssl pkcs8 -inform DER -nocrypt -in "$pk8" -out "$work/key.pem"

want="$(openssl x509 -in "$cert" -noout -pubkey | openssl sha256)"
have="$(openssl pkey -in "$work/key.pem" -pubout | openssl sha256)"
if [ "$want" != "$have" ]; then
  echo "::error::The private key does not match signing/$name.x509.pem"
  exit 1
fi

store="$root/signing/release.p12"
openssl pkcs12 -export -inkey "$work/key.pem" -in "$cert" \
  -name "$name" -passout pass:android -out "$store"

cat > "$root/keystore.properties" <<PROPS
storeFile=$store
storePassword=android
keyAlias=$name
keyPassword=android
PROPS
echo "Signing key ready (SHA-256 of the certificate: $(openssl x509 -in "$cert" -noout -fingerprint -sha256 | cut -d= -f2))"
