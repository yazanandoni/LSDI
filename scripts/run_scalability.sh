#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
DATA="$ROOT/autojoin/data/raw/dblp-scalability"
SIZES="${SIZES:-100 1000 10000 100000}"
METHODS="${METHODS:-AJ,SM,FJ-C,FJ-O}"
FUZZY_MAXPAIRS="${FUZZY_MAXPAIRS:-4000000}"
SM_MAXROWS="${SM_MAXROWS:-5000}"
HEAP="${HEAP:-8g}"
REPEATS="${REPEATS:-3}"
PYTHON="${PYTHON:-python}"

mkdir -p "$DATA"

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

FIXDIR="$ROOT/autojoin/data/fixtures/dblp-scalability"

# --- 2. derive source/target CSVs (skip if the largest size exists) ---------
LAST="$(printf '%s\n' $SIZES | sort -n | tail -1)"
if [ ! -f "$DATA/${LAST}/target.csv" ]; then
  echo ">> parsing DBLP into source/target CSVs for N = $SIZES ..."
  # Run from $DATA so the XML's <!DOCTYPE ... "dblp.dtd"> resolves next to it.
  ( cd "$DATA" && "$PYTHON" "$HERE/dblp_to_csv.py" dblp.xml.gz . --n $SIZES \
      --fixtures-dir "$FIXDIR" )
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
