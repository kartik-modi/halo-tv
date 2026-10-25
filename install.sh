#!/usr/bin/env bash
# Halo TV installer: builds Home (with Ambient) and Tonight, and installs them on an Android TV,
# Google TV or Fire TV over Wi-Fi ADB. Keys are asked for here (or read from the environment) and
# written only to git-ignored files on this computer.
#
#   ./install.sh                     interactive
#   TV_HOST=192.168.1.50 TMDB_TOKEN=... REGION=US ./install.sh --yes      no prompts (for agents / CI)
#
# Options:
#   --tv <ip>             the TV's IP address (or TV_HOST=)
#   --home-only           install Home + Ambient only
#   --tonight-only        install Tonight only
#   --keep-stock          never disable the stock launcher; the Home button is redirected instead
#   --input-key-service   also route the remote's Input button to Halo TV's input switcher
#   --no-video            skip the Waves backdrop video
#   --yes                 never prompt; missing optional keys are skipped
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

HOME_PKG=com.kartikmodi.halo.home
DO_HOME=1 DO_TONIGHT=1 KEEP_STOCK=0 INPUT_SVC=0 VIDEO=1 YES=0
while [ $# -gt 0 ]; do
  case "$1" in
    --tv) TV_HOST="$2"; shift ;;
    --home-only) DO_TONIGHT=0 ;;
    --tonight-only) DO_HOME=0 ;;
    --keep-stock) KEEP_STOCK=1 ;;
    --input-key-service) INPUT_SVC=1 ;;
    --no-video) VIDEO=0 ;;
    --yes|-y) YES=1 ;;
    -h|--help) sed -n 2,20p "$0"; exit 0 ;;
    *) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
  esac
  shift
done

bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }
die() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }
ask() {   # ask VAR "prompt" [secret]
  local var=$1 prompt=$2 secret=${3:-}
  [ -n "${!var:-}" ] && return 0
  [ "$YES" = 1 ] && return 0
  [ -t 0 ] || return 0
  # shellcheck disable=SC2229  # indirect: reads into the variable named by $var
  if [ -n "$secret" ]; then read -r -s -p "$prompt" "$var"; echo; else read -r -p "$prompt" "$var"; fi
}

# ------------------------------------------------------------------ 1. tools on this computer
bold "1/7  Checking tools on this computer"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || { [ -d /opt/homebrew/opt/openjdk@17 ] && echo /opt/homebrew/opt/openjdk@17; } || { [ -d /usr/lib/jvm/java-17-openjdk-amd64 ] && echo /usr/lib/jvm/java-17-openjdk-amd64; } || true)}"
[ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ] || die "JDK 17 not found.
  macOS:  brew install openjdk@17
  Ubuntu: sudo apt install openjdk-17-jdk
  then run ./install.sh again."
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-$(for d in ~/Library/Android/sdk ~/Android/Sdk /opt/homebrew/share/android-commandlinetools /usr/lib/android-sdk; do [ -d "$d" ] && echo "$d" && break; done)}"
[ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME" ] || die "Android SDK not found.
  macOS:  brew install --cask android-commandlinetools
  Ubuntu: sudo apt install android-sdk   (or install Android Studio)
  then accept the licences:  yes | sdkmanager --licenses   and run ./install.sh again.
  (Set ANDROID_HOME if your SDK lives somewhere else.)"
command -v python3 >/dev/null || die "python3 not found (needed for the ADB client in tools/)."
echo "JDK: $JAVA_HOME"
echo "Android SDK: $ANDROID_HOME"
if [ ! -x tools/venv/bin/python ]; then
  echo "Creating tools/venv for the ADB client..."
  python3 -m venv tools/venv
  tools/venv/bin/pip install -q -r tools/requirements.txt
fi
PY="$ROOT/tools/venv/bin/python"
tv() { "$PY" -I "$ROOT/tools/tv.py" "$@"; }
push() { "$PY" -I "$ROOT/tools/b64push.py" "$@"; }

# ------------------------------------------------------------------ 2. the TV
bold "2/7  Connecting to the TV"
cat <<'EOF'
On the TV, ADB debugging must be on (README, "Turn on ADB debugging"):
  Google TV / Android TV: Settings > System > About > click "Android TV OS build" 7 times,
                          then Settings > System > Developer options > USB debugging: On
  Fire TV:                Settings > My Fire TV (or Device & Software) > About > click your TV's name 7 times,
                          then Developer Options > ADB debugging: On
The TV and this computer must be on the same network.
EOF
ask TV_HOST "TV IP address (Settings > Network on the TV): "
[ -n "${TV_HOST:-}" ] || die "no TV IP address given (use --tv <ip> or TV_HOST=)."
export TV_HOST
echo "Connecting to $TV_HOST:5555. The first time, the TV asks \"Allow USB debugging?\": tick \"Always allow\" and choose OK."
PROBE=$(tv --probe) || die "could not connect to $TV_HOST:5555. Is ADB debugging on, and is the IP right?"
echo "$PROBE"
MANUFACTURER=$(printf '%s\n' "$PROBE" | awk -F' = ' '/ro.product.manufacturer/ {print $2}' | tr -d '\r')
SDK=$(printf '%s\n' "$PROBE" | awk -F' = ' '/ro.build.version.sdk/ {print $2}' | tr -d '\r')
[ "${SDK:-0}" -ge 26 ] 2>/dev/null || die "Halo TV needs Android 8.0 (API 26) or newer; this TV reports API ${SDK:-?}."
HOME_QUERY='cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
CURRENT_HOME=$(tv "$HOME_QUERY" | tail -n 1 | tr -d '\r' | cut -d/ -f1)
if [ "$MANUFACTURER" = Amazon ] || [ "$CURRENT_HOME" = com.amazon.tv.launcher ]; then PLATFORM=firetv
elif [ "$CURRENT_HOME" = com.google.android.apps.tv.launcherx ]; then PLATFORM=googletv
else PLATFORM=androidtv; fi
echo "Platform: $PLATFORM (Home currently: ${CURRENT_HOME:-unknown})"

# ------------------------------------------------------------------ 3. keys
bold "3/7  Keys (stored only in tonight/secrets.properties, which git ignores)"
if [ "$DO_TONIGHT" = 1 ]; then
  if [ -f tonight/secrets.properties ] && [ -z "${TMDB_TOKEN:-}" ]; then
    echo "Using the existing tonight/secrets.properties."
  else
    echo "Tonight needs a free TMDB API Read Access Token: https://www.themoviedb.org/settings/api"
    ask TMDB_TOKEN "TMDB API Read Access Token (input hidden; leave empty to skip Tonight): " secret
    if [ -z "${TMDB_TOKEN:-}" ]; then
      echo "No TMDB token: Tonight will be skipped."; DO_TONIGHT=0
    else
      ask REGION "Your country as a 2-letter code, for Tonight's services (US, GB, IN, DE...) [US]: "
      REGION=$(printf '%s' "${REGION:-US}" | tr '[:lower:]' '[:upper:]' | cut -c1-2)
      echo "Optional: an Anthropic API key turns on Tonight's AI picks and taste memory: https://console.anthropic.com/settings/keys"
      ask ANTHROPIC_KEY "Anthropic API key (input hidden; leave empty to skip): " secret
      ( umask 077
        { echo "# Written by install.sh. Git-ignored: never commit this file."
          echo "tmdb.token=${TMDB_TOKEN}"
          echo "region=${REGION}"
          echo "anthropic.key=${ANTHROPIC_KEY:-}"; } > tonight/secrets.properties )
      echo "Wrote tonight/secrets.properties (readable only by you)."
    fi
  fi
fi

# ------------------------------------------------------------------ 4. build
bold "4/7  Building"
build() {   # build <dir>
  ( cd "$ROOT/$1"
    [ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
    chmod +x gradlew
    ./gradlew --no-daemon -q assembleRelease --console=plain ) || die "the $1 build failed (see above)."
  echo "Built $1/app/build/outputs/apk/release/app-release.apk"
}
[ "$DO_HOME" = 1 ] && build home
[ "$DO_TONIGHT" = 1 ] && build tonight

install_apk() {   # install_apk <apk> <name>
  push "$1" "/data/local/tmp/$2.apk"
  local out; out=$(tv "pm install -r /data/local/tmp/$2.apk; rm -f /data/local/tmp/$2.apk")
  echo "$out"
  case "$out" in *Success*) ;; *) die "pm install of $2 failed (see above). On a full TV, free some space and run again." ;; esac
}
enable_service() {   # appends to enabled_accessibility_services without dropping other services
  local svc=$HOME_PKG/.$1
  tv "s=\$(settings get secure enabled_accessibility_services); case \"\$s\" in *$HOME_PKG/.$1*) ;; null|'') settings put secure enabled_accessibility_services $svc ;; *) settings put secure enabled_accessibility_services \"\$s:$svc\" ;; esac; settings put secure accessibility_enabled 1; echo $1 enabled"
}

# ------------------------------------------------------------------ 5. Home + Ambient
if [ "$DO_HOME" = 1 ]; then
  bold "5/7  Installing Home + Ambient"
  install_apk home/app/build/outputs/apk/release/app-release.apk halo-home
  START_OUT=$(tv "am start -W -n $HOME_PKG/.MainActivity")
  echo "$START_OUT"
  if ! printf '%s' "$START_OUT" | grep -qF 'Status: ok' || printf '%s' "$START_OUT" | grep -qi '^Error'; then
    die "Halo TV did not start (see above); the stock launcher was left untouched."
  fi
  printf '%s\n' "pm grant $HOME_PKG android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted" \
                "cmd package compile -m speed -f $HOME_PKG >/dev/null; echo compiled" | tv --batch

  if [ "$PLATFORM" = firetv ] || [ "$KEEP_STOCK" = 1 ]; then
    # Fire OS does not let another app own Home, and --keep-stock asks us not to touch the stock
    # launcher: HomeKeyService (an accessibility service) sends the Home button to Halo TV instead.
    enable_service HomeKeyService
    echo "The stock launcher stays installed; the Home button now opens Halo TV."
  else
    # Google TV / Android TV: Halo TV becomes the real Home. Which app owns Home differs by build, so
    # only a launcher we recognise is disabled, and it is put back if the handover does not stick.
    KNOWN_STOCK="com.google.android.tvlauncher com.google.android.apps.tv.launcherx com.google.android.leanbacklauncher com.mitv.tvhome"
    STOCK="${STOCK_LAUNCHER:-}"
    if [ -z "$STOCK" ]; then
      case " $KNOWN_STOCK " in
        *" $CURRENT_HOME "*) STOCK=$CURRENT_HOME ;;
        *) if [ "$CURRENT_HOME" = "$HOME_PKG" ]; then STOCK=none
           else
             echo "Home currently resolves to '${CURRENT_HOME:-nothing}', which is not a stock launcher this script knows."
             echo "Falling back to the Home-button redirect, which leaves it alone."
             STOCK=none; enable_service HomeKeyService
           fi ;;
      esac
    fi
    if [ "$STOCK" != none ]; then
      echo "Stock launcher: $STOCK"
      printf '%s\n' "pm disable-user --user 0 $STOCK" "cmd package set-home-activity --user 0 $HOME_PKG/.MainActivity" | tv --batch
      HOME_NOW=$(tv "$HOME_QUERY" 2>/dev/null | tail -n 1 | tr -d '\r' || true)
      case "$HOME_NOW" in
        "$HOME_PKG"/*) echo "Home now resolves to $HOME_NOW" ;;
        *)
          echo "Home resolves to '${HOME_NOW:-nothing}', not Halo TV: putting $STOCK back." >&2
          tv "pm enable --user 0 $STOCK" >/dev/null 2>&1 || true
          tv "cmd package set-home-activity --user 0 \$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $STOCK | tail -n 1)" >/dev/null 2>&1 || true
          REC="$ROOT/.halo/handover-$TV_HOST.txt"; mkdir -p "$ROOT/.halo"
          { echo "# If the TV has no working Home, run these from the repo root:"
            echo "TV_HOST=$TV_HOST tools/venv/bin/python tools/tv.py 'pm enable --user 0 $STOCK'"
            echo "TV_HOST=$TV_HOST tools/venv/bin/python tools/tv.py 'cmd package set-home-activity --user 0 \$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $STOCK | tail -n 1)'"; } > "$REC"
          echo "Recovery commands saved to $REC. Run again with --keep-stock to use the Home-button redirect instead." >&2
          exit 1 ;;
      esac
    fi
  fi
  [ "$INPUT_SVC" = 1 ] && enable_service InputKeyService

  # ---------------------------------------------------------------- 6. Waves backdrop
  if [ "$VIDEO" = 1 ]; then
    bold "6/7  Waves backdrop"
    push assets/waves.mp4 /data/local/tmp/halo-motion.mp4
    tv "content write --uri content://$HOME_PKG.theme/motion.mp4 < /data/local/tmp/halo-motion.mp4 && echo video-written; rm -f /data/local/tmp/halo-motion.mp4"
    tv "am force-stop $HOME_PKG; am start -n $HOME_PKG/.MainActivity >/dev/null; echo restarted"
  fi
fi

# ------------------------------------------------------------------ 7. Tonight
if [ "$DO_TONIGHT" = 1 ]; then
  bold "7/7  Installing Tonight"
  install_apk tonight/app/build/outputs/apk/release/app-release.apk halo-tonight
fi

bold "Done"
[ "$DO_HOME" = 1 ] && cat <<EOF
- Press Home on the remote: Halo TV opens. Ambient is the AMBIENT tile on the dock.
- Phone dashboard (music, Jarvis, settings): open http://$TV_HOST:8765 on a phone on the same Wi-Fi,
  or scan the QR code on the Ambient screen.
- Optional voice assistant (Jarvis + Alexa): see README, "Jarvis relay", or run jarvis/setup-relay.sh.
EOF
[ "$DO_TONIGHT" = 1 ] && echo "- Tonight: the TONIGHT. button at the top of Home. Its search index builds in the background over the first hour."
echo "- To remove everything: ./uninstall.sh"
