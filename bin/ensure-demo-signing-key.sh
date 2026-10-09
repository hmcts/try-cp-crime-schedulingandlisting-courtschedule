#!/usr/bin/env bash
#
# Creates the demo token signing key in a Key Vault, if it is not already there.
#
# DemoSigningKey reads this as demo.signing-key-jwk. Blank makes it mint an ephemeral
# key per replica, so tokens stop verifying across pods and die on redeploy - the
# "intermittent 401 after a deploy" symptom. Terraform creates the POSTGRES-* secrets;
# this one had no owner, so the pipeline takes it.
#
# Deliberately create-only. Rotating invalidates every token already issued to a
# consumer, and the demo client credentials are published anyway, so there is nothing
# to gain from it. If you ever do need to rotate, delete the secret by hand first.
set -euo pipefail

VAULT="${1:?usage: $0 <key-vault-name>}"
SECRET=try-slc-DEMO-SIGNING-KEY-JWK

if az keyvault secret show --vault-name "$VAULT" --name "$SECRET" --query id -o tsv >/dev/null 2>&1; then
  echo "$SECRET already in $VAULT - leaving it alone"
  exit 0
fi

command -v openssl >/dev/null || { echo "openssl not found" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 not found" >&2; exit 1; }

echo "$SECRET not found in $VAULT - generating"
TMP="$(mktemp -d)"; chmod 700 "$TMP"; trap 'rm -rf "$TMP"' EXIT
umask 077

openssl genrsa -out "$TMP/key.pem" 2048 2>/dev/null
openssl rsa -in "$TMP/key.pem" -noout -text > "$TMP/parts.txt" 2>/dev/null

python3 - "$TMP/parts.txt" "$TMP/jwk.json" <<'PY'
import base64, json, re, sys
text = open(sys.argv[1]).read()
def hexblock(label):
    m = re.search(rf"^{label}:\n((?:\s+[0-9a-f:]+\n)+)", text, re.M)
    if not m: raise SystemExit(f"could not find {label} in openssl output")
    return int(re.sub(r"[^0-9a-f]", "", m.group(1)), 16)
def b64u(i):
    return base64.urlsafe_b64encode(
        i.to_bytes((i.bit_length() + 7) // 8 or 1, "big")).rstrip(b"=").decode()
e = int(re.search(r"^publicExponent:\s*(\d+)", text, re.M).group(1))
json.dump({"kty":"RSA","kid":"try-it-now-demo-key-1","use":"sig","alg":"RS256",
           "n":b64u(hexblock("modulus")), "e":b64u(e),
           "d":b64u(hexblock("privateExponent")),
           "p":b64u(hexblock("prime1")), "q":b64u(hexblock("prime2")),
           "dp":b64u(hexblock("exponent1")), "dq":b64u(hexblock("exponent2")),
           "qi":b64u(hexblock("coefficient"))},
          open(sys.argv[2], "w"), separators=(",", ":"))
PY

az keyvault secret set --vault-name "$VAULT" --name "$SECRET" \
    --file "$TMP/jwk.json" --encoding utf-8 --output none
echo "created $SECRET in $VAULT"
