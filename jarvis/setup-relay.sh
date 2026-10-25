#!/usr/bin/env bash
# Optional: deploy the Jarvis relay (a tiny Cloudflare Worker) and pair it with the TV.
# The relay lets Alexa and your phone talk to Jarvis on the TV from anywhere.
# Needs Node.js 18+ and a free Cloudflare account. Keys are generated here and stored only as
# Cloudflare Worker secrets and on the TV; nothing is written into the repo.
#   jarvis/setup-relay.sh [--tv <ip>]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ "${1:-}" = --tv ] && TV_HOST="$2"
[ -n "${TV_HOST:-}" ] || read -r -p "TV IP address: " TV_HOST
command -v npx >/dev/null || { echo "Node.js 18+ is needed (https://nodejs.org)." >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl is needed to generate keys." >&2; exit 1; }
cd "$ROOT/jarvis/relay"
W="npx --yes wrangler@4"

echo "Logging in to Cloudflare (a browser window opens the first time)..."
$W whoami >/dev/null 2>&1 || $W login
echo "Deploying the relay..."
DEPLOY=$($W deploy 2>&1) || { echo "$DEPLOY" >&2; exit 1; }
echo "$DEPLOY" | tail -n 5
URL=$(printf '%s\n' "$DEPLOY" | grep -o -E 'https://[a-z0-9.-]+\.workers\.dev' | head -n 1)
[ -n "$URL" ] || { echo "Could not find the relay URL in wrangler's output." >&2; exit 1; }

TV_KEY=$(openssl rand -hex 24)
PHONE_KEY=$(openssl rand -hex 24)
printf '%s' "$TV_KEY" | $W secret put TV_KEY >/dev/null
printf '%s' "$PHONE_KEY" | $W secret put PHONE_KEY >/dev/null
echo "Relay secrets set (TV_KEY, PHONE_KEY)."

echo "Pairing the TV at $TV_HOST..."
curl -fsS -X POST "http://$TV_HOST:8765/api/jarvis/setup" -d "{\"url\":\"$URL\",\"key\":\"$TV_KEY\"}" >/dev/null
curl -fsS -X POST "http://$TV_HOST:8765/api/jarvis/talk" -d "{\"key\":\"$PHONE_KEY\"}" >/dev/null
echo "Paired. Open http://$TV_HOST:8765 on your phone and tap \"Talk to Jarvis\"."

# Point the Alexa skill manifests at this relay (local, git-ignored copies).
mkdir -p "$ROOT/.halo/alexa"
for s in jarvis tonight; do
  sed "s#https://jarvis-relay.YOUR-SUBDOMAIN.workers.dev#$URL#" "$ROOT/jarvis/alexa/$s/skill.json" > "$ROOT/.halo/alexa/$s-skill.json"
done
echo "Alexa skill manifests for this relay: .halo/alexa/ (see README, \"Alexa skills\", to create the skills)."
