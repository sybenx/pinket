#!/bin/sh
# Publish the newest GitHub release to Zapstore, from zapstore.yaml, signed by Keykin on
# this Mac (keykin sign: Touch ID for each signature, four in all). Run after the
# release workflow has attached the APK.
#
#   ./zapstore.sh            publish
#   DRY=1 ./zapstore.sh      build the events and show them; signs and sends nothing
#
# Needs: keykin, nak, and the old publisher as zsp-0.3.4 (zsp 2.x can only sign with an
# nsec or a bunker link, and can't hand its events to another signer).
#
# Zapstore shows a new app only once a proof links the APK's signing key to the account
# that publishes it (kind 30509). Pinket's was sent 2026-11-22 and lasts a year. To renew:
# message "Verifying at <created_at> until <expiry> that I control the following Nostr
# public key: <hex>", signed SHA-256 with the release key, base64 in a "signature" tag,
# "d" = SHA-256 of the certificate, "expiry" tag; then keykin sign and send.
set -e
cd "$(dirname "$0")"
RELAY=${RELAY:-wss://relay.zapstore.dev}
CDN=${CDN:-https://cdn.zapstore.dev}
ZSP=${ZSP:-zsp-0.3.4}
for t in keykin nak python3 curl "$ZSP"; do command -v "$t" >/dev/null || { echo "$t isn't installed"; exit 1; }; done
NPUB=$(keykin status | sed -n 's/^\(npub1[a-z0-9]*\).*/\1/p' | head -1)
[ -n "$NPUB" ] || { echo "keykin has no account on this Mac: run keykin join"; exit 1; }

W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
SIGN_WITH=$NPUB "$ZSP" publish zapstore.yaml --offline --quiet --skip-preview --no-color > "$W/unsigned.jsonl" 2> "$W/zsp.err" \
  || { tr '\r' '\n' < "$W/zsp.err" | tail -5; exit 1; }

# One file per event, in sending order (the APK's event, the release, the app), plus the
# APK's path and hash. The release gets the platform tag the relay now asks for.
python3 - "$W" <<'EOF'
import json, re, sys
w = sys.argv[1]
evs = {}
for line in open(f"{w}/unsigned.jsonl"):
    if line.strip():
        e = json.loads(line); evs[e["kind"]] = e
asset, rel, app = evs[3063], evs[30063], evs[32267]
plats = [t for t in asset["tags"] if t[0] == "f"]
if not any(t[0] == "f" for t in rel["tags"]):
    at = next(i for i, t in enumerate(rel["tags"]) if t[0] == "e")
    rel["tags"][at:at] = plats
for n, e in enumerate((asset, rel, app)):
    json.dump({k: e[k] for k in ("kind", "created_at", "content", "tags")}, open(f"{w}/u{n}.json", "w"))
err = open(f"{w}/zsp.err").read()
m = re.search(r"APK:\s*\n\s*Path:\s*(\S+)\s*\n\s*SHA256:\s*([0-9a-f]{64})", err)
open(f"{w}/apk", "w").write(f"{m.group(1)}\n{m.group(2)}\n" if m else "")
tag = lambda e, k: next((t[1] for t in e["tags"] if t[0] == k), "")
print(f"{tag(app, 'name')} {tag(rel, 'version')} ({tag(app, 'd')})")
EOF
APK=$(sed -n 1p "$W/apk"); HASH=$(sed -n 2p "$W/apk")

if [ -n "$DRY" ]; then cat "$W"/u0.json "$W"/u1.json "$W"/u2.json; echo; echo "APK: ${APK:-already on $CDN}"; exit 0; fi

# The APK goes up first, with a signed permit. The server keeps it unserved until the
# listing that names it is accepted.
if [ -n "$APK" ]; then
  NOW=$(date +%s)
  keykin sign "{\"kind\":24242,\"created_at\":$NOW,\"content\":\"Upload APK\",\"tags\":[[\"t\",\"upload\"],[\"x\",\"$HASH\"],[\"expiration\",\"$((NOW + 900))\"]]}" > "$W/auth.json"
  AUTH=$(python3 -c "import json,base64,sys;print(base64.b64encode(json.dumps(json.load(open(sys.argv[1])),separators=(',',':')).encode()).decode())" "$W/auth.json")
  CODE=$(curl -s -o "$W/up.out" -D "$W/up.hdr" -w '%{http_code}' -X PUT "$CDN/upload" -H "Authorization: Nostr $AUTH" \
    -H "Content-Digest: $HASH" -H "Content-Type: application/vnd.android.package-archive" --data-binary @"$APK")
  [ "$CODE" = 200 ] || { echo "APK upload failed ($CODE)"; grep -i x-reason "$W/up.hdr" || true; exit 1; }
  echo "APK uploaded"
fi

for n in 0 1 2; do
  keykin sign - < "$W/u$n.json" > "$W/s$n.json"
  nak event "$RELAY" < "$W/s$n.json" 2>&1 | grep -v '^{' | tail -1
done
echo "Sent. A new version can take a few minutes to show."
