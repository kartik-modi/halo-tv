"""Pick the streaming services Tonight shows, for one country, from TMDB's own list.

    TMDB_TOKEN=... python tools/services.py IN [--packages pkgs.txt] [--pick "Netflix, JioHotstar"] [--yes]

Lists every subscription service TMDB knows in that country (most popular first), marks the ones whose app is
installed on the TV (pkgs.txt: `pm list packages` output), lets you pick by number or name, and prints one line
for tonight/secrets.properties:

    services=Netflix:8,1796; Amazon Prime Video:119,2100; JioHotstar:2336

The token is read from the environment so it never appears in a process list. Messages go to stderr.
"""
import argparse
import json
import re
import subprocess
import sys
import urllib.error
import urllib.request

# Android TV / Fire TV packages of well-known services (also in Services.java, which opens them).
KNOWN = {
    "netflix": ["com.netflix.ninja", "com.netflix.mediaclient"],
    "prime video": ["com.amazon.firebat", "com.amazon.amazonvideo.livingroom"],
    "jiohotstar": ["in.startv.hotstar", "in.startv.hotstaronly"],
    "hotstar": ["in.startv.hotstar", "in.startv.hotstaronly"],
    "disney": ["com.disney.disneyplus"],
    "apple tv": ["com.apple.atve.androidtv.appletv", "com.apple.atve.amazon.appletv"],
    "max": ["com.wbd.stream", "com.hbo.hbonow"],
    "hulu": ["com.hulu.livingroomplus", "com.hulu.plus"],
    "paramount": ["com.cbs.ott", "com.cbs.app"],
    "peacock": ["com.peacocktv.peacockandroid"],
    "zee5": ["com.graymatrix.did"],
    "sony liv": ["com.sonyliv"],
    "sonyliv": ["com.sonyliv"],
    "crunchyroll": ["com.crunchyroll.crunchyroid"],
    "mubi": ["com.mubi"],
}
# Variants TMDB lists separately but which are the same app: merged into the plain name.
VARIANT = re.compile(r"\s+(standard with ads|basic with ads|with ads|premium|premium plus|essential|"
                     r"ad-free|kids|free)$", re.I)
# Add-on channels sold inside another app, and stores that rent or sell rather than stream: left out.
CHANNEL = re.compile(r"(channel|store)$|^(amazon video|google play movies|microsoft store|rakuten tv|vudu|"
                     r"fandango at home.*|justwatch tv.*|spectrum on demand|directv|verizon fios|sky store)$", re.I)


def say(*a):
    print(*a, file=sys.stderr)


def tmdb(path, token):
    url = "https://api.themoviedb.org/3" + path
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + token, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.load(r)
    except urllib.error.URLError as e:
        if "CERTIFICATE_VERIFY_FAILED" not in str(e):
            raise
        # python.org's Python on macOS ships without root certificates: use curl, header on stdin (not argv)
        out = subprocess.run(["curl", "-fsS", "-H", "@-", "-H", "Accept: application/json", url],
                             input="Authorization: Bearer " + token + "\n", capture_output=True, text=True, timeout=30)
        if out.returncode:
            raise SystemExit("TMDB request failed: " + out.stderr.strip())
        return json.loads(out.stdout)


def base_name(name):
    n = name.strip()
    while True:
        m = VARIANT.sub("", n)
        if m == n:
            return n
        n = m


def fold(s):
    return s.lower().replace("+", " plus").strip()


SKIP = {"com", "in", "tv", "org", "net", "app", "apps", "android", "androidtv", "atv", "mobile", "co", "uk", "us"}


def packages_for(name, installed):
    """Installed packages for a service: its known ones first, else a package named after it."""
    f = fold(name)
    for key, pkgs in KNOWN.items():
        if re.search(r"(^|[^a-z0-9])" + re.escape(key) + r"($|[^a-z0-9])", f):
            return [p for p in pkgs if p in installed], True
    words = [w for w in re.split(r"[^a-z0-9]+", f) if len(w) >= 3 and w not in ("plus", "the", "video", "with", "ads", "and")]
    claimed = {p for pkgs in KNOWN.values() for p in pkgs}   # another service's app is never this one's
    hits = [p for p in sorted(installed) if p not in claimed
            and any(seg.startswith(w) for seg in p.lower().split(".") if seg not in SKIP for w in words)]
    return hits[:1], False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("region")
    ap.add_argument("--packages", help="file with `pm list packages` output from the TV")
    ap.add_argument("--pick", help="comma-separated names or numbers; skips the question")
    ap.add_argument("--yes", action="store_true", help="no question: the installed ones (else the top three)")
    ap.add_argument("--token-env", default="TMDB_TOKEN")
    a = ap.parse_args()
    import os
    token = os.environ.get(a.token_env, "")
    if not token:
        sys.exit("no TMDB token in $" + a.token_env)
    region = a.region.upper()

    installed = set()
    if a.packages:
        with open(a.packages) as f:
            installed = {l.strip().split(":", 1)[-1] for l in f if l.strip()}

    services = {}   # base name -> {"ids": [...], "prio": n}
    for kind in ("movie", "tv"):
        for p in tmdb(f"/watch/providers/{kind}?watch_region={region}", token).get("results", []):
            name = p["provider_name"]
            if CHANNEL.search(name):
                continue
            b = base_name(name)
            s = services.setdefault(b, {"ids": [], "prio": 999})
            if p["provider_id"] not in s["ids"]:
                s["ids"].append(p["provider_id"])
            s["prio"] = min(s["prio"], p.get("display_priorities", {}).get(region, p.get("display_priority", 999)))
    if not services:
        sys.exit(f"TMDB lists no streaming services for '{region}'. Is it a 2-letter country code (US, GB, IN...)?")

    ranked = sorted(services.items(), key=lambda kv: kv[1]["prio"])[:30]
    rows = []
    for name, s in ranked:
        pkgs, known = packages_for(name, installed)
        rows.append((name, s["ids"], pkgs, known))

    say(f"\nStreaming services in {region}, most popular first (* = app found on the TV):")
    for i, (name, _, pkgs, _) in enumerate(rows, 1):
        say(f"  {i:2}. {'*' if pkgs else ' '} {name}")

    default = [i for i, r in enumerate(rows) if r[2]] or list(range(min(3, len(rows))))
    pick = a.pick
    if pick is None and not a.yes and sys.stdin.isatty():
        say("\nWhich ones do you subscribe to? Numbers or names, separated by commas.")
        say("Press Enter for: " + ", ".join(rows[i][0] for i in default))
        sys.stderr.write("> ")
        sys.stderr.flush()
        pick = sys.stdin.readline().strip()
    chosen = []
    if pick:
        for tok in [t.strip() for t in pick.split(",") if t.strip()]:
            if tok.isdigit() and 1 <= int(tok) <= len(rows):
                idx = int(tok) - 1
            else:
                idx = next((i for i, r in enumerate(rows) if fold(r[0]) == fold(tok)), None)
                if idx is None:
                    idx = next((i for i, r in enumerate(rows) if fold(tok) in fold(r[0])), None)
            if idx is None:
                say(f"  '{tok}' isn't in the list for {region}: skipped.")
            elif idx not in chosen:
                chosen.append(idx)
    if not chosen:
        chosen = default

    parts = []
    for i in chosen:
        name, ids, pkgs, known = rows[i]
        entry = f"{name.replace(':', ' ').replace(';', ' ')}:{','.join(map(str, ids))}"
        if pkgs and not known:
            entry += ":" + pkgs[0]
        elif not pkgs and not known:
            say(f"  Note: no app for {name} found on the TV. Tonight will list it, but can only open it once its "
                f"package is added (services= in tonight/secrets.properties, see README).")
        parts.append(entry)
    say("Tonight will show: " + ", ".join(rows[i][0] for i in chosen))
    print("services=" + "; ".join(parts))


if __name__ == "__main__":
    main()
