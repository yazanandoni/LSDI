#!/usr/bin/env bash
#
# Reproduce the Auto-Join §6.4 scalability timing (Figure 8) end-to-end:
#   1. download DBLP (dblp.xml.gz + dblp.dtd),
#   2. derive source/target tables at several sizes (scripts/dblp_to_csv.py),
#   3. time EVERY method (AJ + the SM / FJ-C / FJ-O baselines) at each size
#      (ScalabilityBenchmark).
#
# The baselines FJ-C, FJ-O (all-pairs O(Ns*Nt)) and SM (substring-index
# alignment search, O(candidates*Ns)) all grow super-linearly — this is exactly
# why the paper shows them timing out early. To avoid launching a multi-minute
# run that would lag the machine, a fuzzy method is SKIPPED once Ns*Nt exceeds
# FUZZY_MAXPAIRS and SM is skipped once Ns exceeds SM_MAXROWS; the skip itself is
# the "does not scale" result. Only AJ runs at every size. Restrict the set with
# METHODS if you only want some.
#
# Usage:
#   scripts/run_scalability.sh
#   SIZES="100 1000 10000 100000 1000000" HEAP=12g scripts/run_scalability.sh
#   METHODS="AJ" scripts/run_scalability.sh          # AJ only (original behavior)
#
# Env knobs:
#   SIZES          table sizes to test          (default "100 1000 10000 100000")
#   METHODS        comma-list of methods        (default "AJ,SM,FJ-C,FJ-O")
#   FUZZY_MAXPAIRS Ns*Nt cap above which FJ-*    (default 4000000)
#                  runs are skipped
#   SM_MAXROWS     Ns cap above which SM         (default 5000)
#                  runs are skipped
#   HEAP           forked test-JVM max heap      (default 8g)
#   REPEATS        timed runs per size (median)  (default 3)
#   PYTHON         python interpreter            (default python)
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
DATA="$ROOT/autojoin/data/raw/dblp-scalability"   # gitignored (see .gitignore)
SIZES="${SIZES:-100 1000 10000 100000}"           # add 1000000 once heap is confirmed
METHODS="${METHODS:-AJ,SM,FJ-C,FJ-O}"
FUZZY_MAXPAIRS="${FUZZY_MAXPAIRS:-4000000}"
SM_MAXROWS="${SM_MAXROWS:-5000}"
HEAP="${HEAP:-8g}"
REPEATS="${REPEATS:-3}"
PYTHON="${PYTHON:-python}"

mkdir -p "$DATA"

# --- 0. preflight -----------------------------------------------------------
command -v curl >/dev/null || { echo "curl not found" >&2; exit 1; }
command -v mvn  >/dev/null || { echo "mvn not found"  >&2; exit 1; }
"$PYTHON" -c "import lxml" 2>/dev/null || {
  echo "lxml not installed for '$PYTHON'. Run: $PYTHON -m pip install lxml" >&2; exit 1; }

# --- 1. download DBLP (skip if already present) -----------------------------
if [ ! -f "$DATA/dblp.xml.gz" ]; then
  echo ">> downloading dblp.xml.gz (~900 MB) ..."
  curl -L --fail -o "$DATA/dblp.xml.gz" https://dblp.org/xml/dblp.xml.gz
fi
if [ ! -f "$DATA/dblp.dtd" ]; then
  curl -L --fail -o "$DATA/dblp.dtd" https://dblp.org/xml/dblp.dtd
fi

# --- 2. derive source/target CSVs (skip if the largest size exists) ---------
LAST="$(printf '%s\n' $SIZES | sort -n | tail -1)"
if [ ! -f "$DATA/dblp_${LAST}/target.csv" ]; then
  echo ">> parsing DBLP into source/target CSVs for N = $SIZES ..."
  # Run from $DATA so the XML's <!DOCTYPE ... "dblp.dtd"> resolves next to it.
  ( cd "$DATA" && "$PYTHON" "$HERE/dblp_to_csv.py" dblp.xml.gz . --n $SIZES )
else
  echo ">> CSVs already present, skipping parse (delete $DATA/dblp_* to regenerate)"
fi

# --- 3. time each method at each size ---------------------------------------
NCSV="$(printf '%s\n' $SIZES | paste -sd, -)"
echo ">> timing  (methods=$METHODS, heap=$HEAP, repeats=$REPEATS, sizes=$NCSV, fuzzy_cap=$FUZZY_MAXPAIRS, sm_cap=$SM_MAXROWS)"
cd "$ROOT/autojoin"
# argLine sizes the FORKED test JVM (where the join runs); MAVEN_OPTS would only
# size Maven itself.
mvn -q -Dtest=ScalabilityBenchmark \
    "-DargLine=-Xmx${HEAP}" \
    -Ddblp.dir="$DATA" \
    "-Ddblp.n=$NCSV" \
    "-Ddblp.repeats=$REPEATS" \
    "-Dmethods=$METHODS" \
    "-Dfuzzy.maxpairs=$FUZZY_MAXPAIRS" \
    "-Dsm.maxrows=$SM_MAXROWS" \
    test
