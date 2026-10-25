# Installing Halo TV: instructions for AI agents

You are helping a person install **Halo TV** (Home + Ambient + Tonight) on their Android TV, Google TV or Fire TV from this repository. Work through the steps in order. The person is probably not a developer: explain each step in one or two plain sentences, and tell them exactly what to press on the TV.

## Rules

1. **Never commit, print, or log a key.** Keys go only into `tonight/secrets.properties` (git-ignored). Don't echo them back, put them in a command line that gets printed, or write them anywhere else. If you show a command, show `TMDB_TOKEN=***`.
2. **Ask before anything that changes the TV** beyond installing these apps. The installer disables the stock launcher only on Google TV / Android TV, and puts it back automatically if the handover fails. Tell the person this before running it, and offer `--keep-stock` if they'd rather not.
3. Don't run `pm uninstall`, `pm clear`, factory resets, or any `settings put` other than what `install.sh` does.
4. If something fails, read the error, check the Troubleshooting table in README.md, and explain what happened before retrying.

## Step 1: check the computer

Run these and install whatever is missing (ask first; use the person's package manager):

```bash
java -version          # needs 17. macOS: brew install openjdk@17 | Ubuntu: sudo apt install openjdk-17-jdk
echo $ANDROID_HOME     # Android SDK. macOS: brew install --cask android-commandlinetools
python3 --version      # any 3.9+
```

After installing the Android SDK command-line tools, accept the licences: `yes | sdkmanager --licenses` (on macOS with Homebrew: `yes | /opt/homebrew/share/android-commandlinetools/cmdline-tools/latest/bin/sdkmanager --licenses`).

Windows: use WSL (Ubuntu) and follow the Linux commands.

## Step 2: turn on ADB debugging on the TV (the person does this)

Ask which TV they have, then give them the matching steps from README.md, section "2. Turn on ADB debugging on the TV":

- **Google TV / Android TV:** Settings → System → About → click *Android TV OS build* 7 times → Settings → System → Developer options → **USB debugging** on (and **Network debugging** if present).
- **Fire TV:** Settings → My Fire TV (or Device & Software) → About → click the TV's name 7 times → back → Developer Options → **ADB debugging** on.

Then ask for the **TV's IP address** (Settings → Network on the TV). The TV and computer must be on the same Wi-Fi.

## Step 3: keys

Ask for these. Explain that they're stored only on this computer, in a git-ignored file:

| Key | Required? | Where |
|---|---|---|
| TMDB **API Read Access Token** | Required for Tonight (skip Tonight without it) | https://www.themoviedb.org/settings/api (free) |
| Anthropic API key | Optional: Tonight's AI picks and taste memory | https://console.anthropic.com/settings/keys |

Also ask (these aren't secret):
- **Their country**, as a 2-letter code (US, GB, IN, DE, CA, AU...). Tonight shows only what streams there.
- **Which streaming apps they pay for.** Any service TMDB lists in their country works, not just Netflix, Prime Video and Disney+. Once the token is in place, show them the list with `TMDB_TOKEN=*** python3 tools/services.py <CC>` (read the token from `tonight/secrets.properties` into the environment; don't put it on the command line you show), and let them pick.

The safest way is for the person to put the keys into the file themselves:

```bash
cp tonight/secrets.properties.example tonight/secrets.properties
# then ask the person to open tonight/secrets.properties and paste their keys after the = signs
```

If they paste a key into the chat instead, write it into `tonight/secrets.properties` yourself (same format as the example), set the file to mode 600, and don't repeat the key back.

## Step 4: install

From the repository root:

```bash
TV_HOST=<tv-ip> REGION=<CC> SERVICES="<their apps, comma-separated>" ./install.sh --yes
```

- `REGION` and `SERVICES` set Tonight's country and apps (names as `tools/services.py` lists them, e.g. `SERVICES="Netflix, JioHotstar, Zee5"`). Without `SERVICES`, `--yes` picks the services whose app is already on the TV. The result is the `services=` line in `tonight/secrets.properties`.
- `--yes` means no prompts. The installer uses the existing `tonight/secrets.properties`. If there's no TMDB token, it installs Home + Ambient and skips Tonight.
- **Tell the person to watch the TV** when the installer connects: the first time, a dialog asks **"Allow USB debugging?"**. They must tick **Always allow from this computer** and press **OK** within about two minutes. If it times out, run the command again.
- Add `--keep-stock` if they don't want the stock launcher disabled (on Fire TV it never is).
- The first build downloads Gradle and dependencies, which takes a few minutes.

The installer detects the platform itself:
- **Google TV / Android TV:** Halo TV becomes Home; the stock launcher is disabled (not removed), with automatic rollback.
- **Fire TV:** the Amazon launcher stays; an accessibility service (`HomeKeyService`) sends the Home button to Halo TV.

## Step 5: confirm with the person

Ask them to press **Home** on the remote. They should see the liquid-glass Home. Then point them to:
- **AMBIENT** on the dock (the always-on screen),
- **TONIGHT.** in the top bar,
- the **phone web app**: `http://<tv-ip>:8765`, or scan the QR code on Ambient. Its **Settings** tab is where they can paste an **OpenAI key** to give Jarvis a natural voice (optional; stored on the TV only).

## Optional: Jarvis relay and Alexa

Only if they want Alexa or to talk to Jarvis away from home. Needs Node.js 18+ and a free Cloudflare account:

```bash
jarvis/setup-relay.sh --tv <tv-ip>
```

A browser window opens for the Cloudflare login (the person does that). Alexa skills are a further optional step: follow README.md, "Alexa skills".

## Later: changing the country or the streaming apps

Follow README.md, "Country and streaming apps". In short: re-run `REGION=<CC> ./install.sh --tonight-only` for a new country, or `SERVICES="..." ./install.sh --tonight-only` for different apps, or edit the `services=` line by hand (`name:TMDB provider ids[:Android TV package]`, separated by `;`). For a service that isn't built into `Services.java`, find its package with `tools/tv.py "pm list packages"` and add it as the third part, or Tonight can list its titles but not open them.

## Undo

```bash
TV_HOST=<tv-ip> ./uninstall.sh
```

## Reference

- `tools/tv.py` is the ADB client: `TV_HOST=<ip> tools/venv/bin/python tools/tv.py '<shell command>'`; `--probe` prints the model and Android version.
- Package names: `com.kartikmodi.halo.home` (Home + Ambient), `com.kartikmodi.halo.tonight`.
- Tonight's built-in services (colours, packages, links): `tonight/app/src/main/java/com/kartikmodi/halo/tonight/Services.java`; the picker: `tools/services.py`.
- Home's settings: `home/app/src/main/res/values/config.xml`. Rebuild with `./install.sh --home-only`.
