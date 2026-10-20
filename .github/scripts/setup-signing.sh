#!/usr/bin/env bash
# Prepares the APK signing key for CI and writes keystore.properties (read by smarttubetv/build.gradle).
#
# The certificate is signing/testkey.x509.pem (the public AOSP "testkey"). Its private half is
# taken from the TESTKEY_PK8_B64 secret (base64 of testkey.pk8) when set, otherwise it is
# downloaded from the AOSP mirror on GitHub. Either way it must match the committed certificate,
# or the build stops: an APK must never be signed with a key that is not the one in the repo.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

cert="$root/signing/testkey.x509.pem"
pk8="$work/testkey.pk8"

if [ -n "${TESTKEY_PK8_B64:-}" ]; then
  printf '%s' "$TESTKEY_PK8_B64" | base64 -d > "$pk8"
else
  curl -fsSL --retry 3 -o "$pk8" \
    https://raw.githubusercontent.com/aosp-mirror/platform_build/master/target/product/security/testkey.pk8
fi

openssl pkcs8 -inform DER -nocrypt -in "$pk8" -out "$work/key.pem"

want="$(openssl x509 -in "$cert" -noout -pubkey | openssl sha256)"
have="$(openssl pkey -in "$work/key.pem" -pubout | openssl sha256)"
if [ "$want" != "$have" ]; then
  echo "::error::The private key does not match signing/testkey.x509.pem"
  exit 1
fi

store="$root/signing/release.p12"
openssl pkcs12 -export -inkey "$work/key.pem" -in "$cert" \
  -name testkey -passout pass:android -out "$store"

cat > "$root/keystore.properties" <<PROPS
storeFile=$store
storePassword=android
keyAlias=testkey
keyPassword=android
PROPS
echo "Signing key ready (SHA-256 of the certificate: $(openssl x509 -in "$cert" -noout -fingerprint -sha256 | cut -d= -f2))"
