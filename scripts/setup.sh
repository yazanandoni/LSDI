#!/usr/bin/env bash
#
# One-shot bootstrap for a fresh clone.
#
# The dblp-scalability fixtures are committed, but the raw CSVs they point to
# are gitignored (see .gitignore) — so on a new computer the backend fails with
# FileNotFoundException until the data is generated. This script closes that
# gap end-to-end:
#
#   1. checks the required tools and installs the missing ones where it can
#      (lxml via pip; Python / Docker via winget, brew or apt),
#   2. checks that every committed dblp-scalability fixture has its raw CSVs
#      (source/target/ground_truth), downloading dblp.xml.gz (~900 MB) and
#      parsing it (scripts/dblp_to_csv.py) if any are missing,
#   3. builds and starts the docker compose stack:
#        backend  -> http://localhost:8081
#        frontend -> http://localhost:4200
#
# Usage:
#   scripts/setup.sh                 # full bootstrap
#   SKIP_DOCKER=1 scripts/setup.sh   # deps + data only, don't start docker
#
# Windows (Git Bash):
#   & "C:\Program Files\Git\bin\bash.exe" ./scripts/setup.sh
#
set -u -o pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
DATA="$ROOT/autojoin/data/raw/dblp-scalability"    # gitignored
FIXDIR="$ROOT/autojoin/data/fixtures/dblp-scalability"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) OS=windows ;;
  Darwin)               OS=mac ;;
  *)                    OS=linux ;;
esac

have() { command -v "$1" >/dev/null 2>&1; }
say()  { printf '\n>> %s\n' "$*"; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# Install a package with whatever package manager this machine has.
# $1 = winget id, $2 = brew formula (prefix "cask:" for casks), $3 = apt package
pkg_install() {
  case "$OS" in
    windows)
      have winget || return 1
      winget install -e --id "$1" --accept-source-agreements --accept-package-agreements
      ;;
    mac)
      have brew || return 1
      case "$2" in cask:*) brew install --cask "${2#cask:}" ;; *) brew install "$2" ;; esac
      ;;
    linux)
      have apt-get || return 1
      sudo apt-get update -qq && sudo apt-get install -y "$3"
      ;;
  esac
}

# --- 1. which DBLP sizes does the committed fixture set require? -------------
SIZES=()
for f in "$FIXDIR"/dblp-*/fixture.json; do
  [ -f "$f" ] || continue
  n="${f%/fixture.json}"; n="${n##*/dblp-}"
  SIZES+=("$n")
done
[ "${#SIZES[@]}" -gt 0 ] || die "no fixtures found under $FIXDIR — is this a full checkout?"

MISSING=()
for n in "${SIZES[@]}"; do
  for csv in source target ground_truth; do
    if [ ! -s "$DATA/$n/$csv.csv" ]; then MISSING+=("$n"); break; fi
  done
done

if [ "${#MISSING[@]}" -eq 0 ]; then
  say "DBLP data: all sizes present (${SIZES[*]}) — nothing to generate"
else
  say "DBLP data: missing sizes: ${MISSING[*]} (of ${SIZES[*]})"
fi

# --- 2. dependency checks (python/lxml/curl only needed when generating) -----
if [ "${#MISSING[@]}" -gt 0 ]; then

  # curl — needed to download dblp.xml.gz (Git Bash ships it on Windows)
  if ! have curl; then
    say "curl not found — trying to install"
    pkg_install cURL.cURL curl curl || die "curl is required; install it and re-run"
    have curl || die "curl still not on PATH — open a new terminal and re-run"
  fi

  # python — try the common launchers, skipping the Windows Store stub
  PYTHON=""
  find_python() {
    PYTHON=""
    for c in python3 python py; do
      p="$(command -v "$c" 2>/dev/null)" || continue
      case "$p" in
        */WindowsApps/*) "$c" --version >/dev/null 2>&1 || continue ;;
      esac
      if "$c" -c "import sys" >/dev/null 2>&1; then PYTHON="$c"; return 0; fi
    done
    return 1
  }
  if ! find_python; then
    say "Python 3 not found — trying to install"
    pkg_install Python.Python.3.12 python "python3 python3-pip" \
      || die "Python 3 is required; install it from https://python.org and re-run"
    find_python || die "Python installed but not on PATH yet — open a NEW terminal and re-run"
  fi
  say "Python: using '$PYTHON' ($($PYTHON --version 2>&1))"

  # pip + lxml — the DBLP parser needs lxml.iterparse
  if ! $PYTHON -m pip --version >/dev/null 2>&1; then
    $PYTHON -m ensurepip --upgrade >/dev/null 2>&1 \
      || die "pip is missing for '$PYTHON' — install pip and re-run"
  fi
  if ! $PYTHON -c "import lxml" >/dev/null 2>&1; then
    say "installing lxml"
    $PYTHON -m pip install lxml \
      || $PYTHON -m pip install --user lxml \
      || $PYTHON -m pip install --break-system-packages lxml \
      || die "could not install lxml — run: $PYTHON -m pip install lxml"
  fi
fi

# docker — always required (unless SKIP_DOCKER)
if [ -z "${SKIP_DOCKER:-}" ]; then
  if ! have docker; then
    # Docker Desktop may be installed but not on this shell's PATH (Windows)
    DOCKER_BIN="/c/Program Files/Docker/Docker/resources/bin"
    if [ "$OS" = windows ] && [ -x "$DOCKER_BIN/docker.exe" ]; then
      export PATH="$PATH:$DOCKER_BIN"
    fi
  fi
  if ! have docker; then
    say "Docker not found — trying to install"
    pkg_install Docker.DockerDesktop cask:docker "docker.io docker-compose-v2" \
      || die "Docker is required; install Docker Desktop from https://docker.com and re-run"
    [ "$OS" = windows ] && [ -x "/c/Program Files/Docker/Docker/resources/bin/docker.exe" ] \
      && export PATH="$PATH:/c/Program Files/Docker/Docker/resources/bin"
    have docker || die "Docker installed but not on PATH yet — open a NEW terminal and re-run"
  fi
fi

# --- 3. download + parse DBLP if any size is missing --------------------------
if [ "${#MISSING[@]}" -gt 0 ]; then
  mkdir -p "$DATA"
  if [ ! -s "$DATA/dblp.xml.gz" ]; then
    say "downloading dblp.xml.gz (~900 MB — this can take a while)"
    curl -L --fail -o "$DATA/dblp.xml.gz" https://dblp.org/xml/dblp.xml.gz \
      || die "download failed — check your connection and re-run"
  fi
  if [ ! -s "$DATA/dblp.dtd" ]; then
    curl -L --fail -o "$DATA/dblp.dtd" https://dblp.org/xml/dblp.dtd \
      || die "download of dblp.dtd failed"
  fi

  say "parsing DBLP into CSVs for N = ${MISSING[*]} (the 1M size takes several minutes)"
  # Run from $DATA so the XML's <!DOCTYPE ... "dblp.dtd"> resolves next to it.
  ( cd "$DATA" && $PYTHON "$HERE/dblp_to_csv.py" dblp.xml.gz . \
      --n "${MISSING[@]}" --fixtures-dir "$FIXDIR" ) \
    || die "dblp_to_csv.py failed"

  # verify the gap is actually closed before starting the backend
  for n in "${MISSING[@]}"; do
    for csv in source target ground_truth; do
      [ -s "$DATA/$n/$csv.csv" ] || die "still missing $DATA/$n/$csv.csv after generation"
    done
  done
  say "DBLP data complete for all fixture sizes (${SIZES[*]})"
fi

# --- 4. start the docker compose stack ----------------------------------------
[ -n "${SKIP_DOCKER:-}" ] && { say "SKIP_DOCKER set — done."; exit 0; }

if ! docker info >/dev/null 2>&1; then
  say "Docker daemon not running — trying to start it"
  case "$OS" in
    windows)
      for exe in "/c/Program Files/Docker/Docker/Docker Desktop.exe" \
                 "$LOCALAPPDATA/Docker/Docker Desktop.exe"; do
        [ -f "$exe" ] && { ( "$exe" >/dev/null 2>&1 & ) ; break; }
      done
      ;;
    mac)   open -a Docker || true ;;
    linux) sudo systemctl start docker || true ;;
  esac
  printf '   waiting for the daemon'
  deadline=$((SECONDS + 180))
  until docker info >/dev/null 2>&1; do
    [ $SECONDS -ge $deadline ] && die "Docker daemon did not come up in 3 min — start Docker Desktop manually and re-run"
    printf '.'; sleep 5
  done
  printf '\n'
fi

if docker compose version >/dev/null 2>&1; then DC="docker compose"
elif have docker-compose;                  then DC="docker-compose"
else die "docker compose not available — install the compose plugin"; fi

say "building and starting the stack (first build downloads Maven/npm deps — be patient)"
( cd "$ROOT" && $DC up -d --build ) || die "docker compose up failed"

say "done."
echo "   frontend:  http://localhost:4200"
echo "   backend:   http://localhost:8081"
echo "   logs:      $DC logs -f backend"
echo "   stop:      $DC down"
