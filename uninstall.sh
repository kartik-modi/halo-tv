#!/usr/bin/env bash
# Remove Halo TV from the TV and give Home back to the stock launcher.
#   ./uninstall.sh [--tv <ip>]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
[ "${1:-}" = --tv ] && TV_HOST="$2"
[ -n "${TV_HOST:-}" ] || read -r -p "TV IP address: " TV_HOST
export TV_HOST
PY="$ROOT/tools/venv/bin/python"
[ -x "$PY" ] || { python3 -m venv "$ROOT/tools/venv" && "$ROOT/tools/venv/bin/pip" install -q -r "$ROOT/tools/requirements.txt"; }
tv() { "$PY" -I "$ROOT/tools/tv.py" "$@"; }

HOME_PKG=com.kartikmodi.halo.home
# Re-enable every stock launcher this project may have disabled (harmless when it was never disabled),
# drop Halo TV's accessibility services, then uninstall both apps.
printf '%s\n' \
  "for p in com.google.android.tvlauncher com.google.android.apps.tv.launcherx com.google.android.leanbacklauncher com.mitv.tvhome; do pm enable --user 0 \$p >/dev/null 2>&1 && echo enabled \$p; done; true" \
  "s=\$(settings get secure enabled_accessibility_services); n=\$(echo \"\$s\" | tr ':' '\n' | grep -v '^$HOME_PKG/' | paste -sd: -); settings put secure enabled_accessibility_services \"\${n:-null}\"; echo accessibility-cleaned" \
  "pm uninstall $HOME_PKG; true" \
  "pm uninstall com.kartikmodi.halo.tonight; true" \
  "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1" \
  | tv --batch
echo "Done. Press Home on the remote: the TV's own launcher is back."
