#!/usr/bin/env python3
import argparse
import csv
import json
import random
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / "autojoin" / "data" / "raw" / "synthetic"
FIXTURES = ROOT / "autojoin" / "data" / "fixtures" / "synthetic"
DBLP_SOURCES = [
    ROOT / "autojoin" / "data" / "raw" / "dblp-scalability" / "10000" / "source.csv",
    ROOT / "autojoin" / "data" / "raw" / "dblp-scalability" / "1000" / "source.csv",
]

FIRST = ("robert kyle norma amy josh john mary linda james david sarah peter susan karl anna paul laura mark julia frank "
         "emma lucas olivia liam sophia noah mia ethan chloe aiden ella owen grace henry lily jack ruby leo nora max "
         "ivy adam rose eric jane carl dora hans faye ivan gwen omar tess rene cora seth vera troy myra glen edna "
         "kurt lena ross iris todd june dale hope neil dawn kent joan drew lynn brad gail chad beth cole sara dean tara "
         "gary rita hugo nina jake wren kirk anne loyd cleo saul enid walt fern boyd zola clay opal").split()

LAST = ("kerry norman wiseman case alder galt smith jones miller davis garcia wilson moore taylor thomas white harris "
        "martin lewis clark walker young allen king wright scott green baker adams nelson carter mitchell perez roberts "
        "turner phillips campbell parker evans edwards collins stewart morris rogers reed cook morgan bell murphy bailey "
        "rivera cooper richardson cox howard ward torres peterson gray ramirez james watson brooks kelly sanders price "
        "bennett wood barnes ross henderson coleman jenkins perry powell long patterson hughes flores washington butler "
        "simmons foster gonzales bryant alexander russell griffin diaz hayes myers ford hamilton graham sullivan wallace "
        "woods cole west jordan owens reynolds fisher ellis harrison gibson mcdonald cruz marshall ortiz gomez murray "
        "freeman wells webb simpson stevens tucker porter hunter hicks crawford henry boyd mason morales kennedy warren "
        "dixon ramos reyes burns gordon shaw holmes rice robertson hunt black daniels palmer mills nichols grant knight "
        "ferguson rose stone hawkins dunn perkins hudson spencer gardner stephens payne pierce berry matthews arnold").split()

STREETS = ("main oak pine maple cedar elm park lake hill river church spring high mill walnut".split())
STREET_KINDS = "street avenue road lane drive".split()
MONTHS = "Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec".split()
DAYS = "Mon Tue Wed Thu Fri Sat Sun".split()
ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789"


def rfc2822(rng, hms=None):
    """A full-length RFC-2822 timestamp; hour/min/sec can be pinned."""
    h, m, s = hms if hms else (rng.randrange(24), rng.randrange(60), rng.randrange(60))
    return (f"{rng.choice(DAYS)}, {rng.randrange(1, 29):02d} {rng.choice(MONTHS)} "
            f"{rng.randrange(1998, 2027)} {h:02d}:{m:02d}:{s:02d} +0000")


def noise(rng, hms=None):
    return {
        "noise_num": str(rng.randrange(10_000, 99_999_999)),
        "noise_code": "".join(rng.choice(ALNUM) for _ in range(rng.randrange(6, 13))),
        "noise_addr": f"{rng.randrange(1, 9999)} {rng.choice(STREETS)} {rng.choice(STREET_KINDS)}",
        "noise_ts": rfc2822(rng, hms),
    }


def write_case(name, src_cols, src_rows, tgt_col, gt_pairs, src_keys, rng):
    raw_dir = RAW / name
    raw_dir.mkdir(parents=True, exist_ok=True)
    with open(raw_dir / "source.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(src_cols)
        w.writerows(src_rows)
    tgt_rows = [gt[-1] for gt in gt_pairs]
    rng.shuffle(tgt_rows)  # W&T store the target in shuffled order
    with open(raw_dir / "target.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow([tgt_col])
        w.writerows([[v] for v in tgt_rows])
    with open(raw_dir / "ground_truth.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(src_keys + [tgt_col])
        w.writerows(gt_pairs)

    pair_id = f"synthetic-{name}"
    fx_dir = FIXTURES / pair_id
    fx_dir.mkdir(parents=True, exist_ok=True)
    fixture = {
        "pair_id": pair_id,
        "source": {"file": f"data/raw/synthetic/{name}/source.csv", "key_columns": src_keys},
        "target": {"file": f"data/raw/synthetic/{name}/target.csv", "key_columns": [tgt_col]},
        "ground_truth": {
            "file": f"data/raw/synthetic/{name}/ground_truth.csv",
            "format": "row_values",
            "source_key_columns": src_keys,
            "target_key_columns": [tgt_col],
        },
    }
    (fx_dir / "fixture.json").write_text(json.dumps(fixture, indent=2) + "\n", encoding="utf-8")
    print(f"  {pair_id}: {len(src_rows)} rows -> {raw_dir}")


def gen_time(n, rng):
    triples = rng.sample([(h, m, s) for h in range(24) for m in range(60) for s in range(60)], n)
    rows, gts = [], []
    for h, m, s in triples:
        nz = noise(rng, hms=(h, m, s))  # RFC-2822 shares the row's time (see module doc)
        rows.append([f"{s:02d}", f"{m:02d}", f"{h:02d}",
                     nz["noise_num"], nz["noise_code"], nz["noise_addr"], nz["noise_ts"]])
        gts.append([f"{s:02d}", f"{m:02d}", f"{h:02d}", f"{h:02d}:{m:02d}:{s:02d}"])
    cols = ["second", "minute", "hour", "noise_num", "noise_code", "noise_addr", "noise_ts"]
    write_case("time", cols, rows, "time", gts, ["second", "minute", "hour"], rng)


def gen_userid(n, rng):
    lasts = rng.sample(LAST, min(n, len(LAST)))
    rows, gts = [], []
    for l in lasts:
        f = rng.choice(FIRST)
        login = f[0] + l  # W&T's dominant formula: login = first[1-1] + last[1-n]
        nz = noise(rng)
        rows.append([f, rng.choice(FIRST), l,
                     nz["noise_num"], nz["noise_code"], nz["noise_addr"], nz["noise_ts"]])
        gts.append([f, l, login])
    cols = ["first", "middle", "last", "noise_num", "noise_code", "noise_addr", "noise_ts"]
    write_case("userid", cols, rows, "login", gts, ["first", "last"], rng)


def gen_nameconcat(n, rng):
    lasts = rng.sample(LAST, min(n, len(LAST)))
    rows, gts = [], []
    for l in lasts:
        f = rng.choice(FIRST)
        full = f + l  # W&T Table 9: full = first[1-n] + last[1-n], no separator
        nz = noise(rng)
        rows.append([f, l, nz["noise_num"], nz["noise_code"], nz["noise_addr"], nz["noise_ts"]])
        gts.append([f, l, full])
    cols = ["first", "last", "noise_num", "noise_code", "noise_addr", "noise_ts"]
    write_case("nameconcat", cols, rows, "full", gts, ["first", "last"], rng)


def gen_citeseer(n, rng):
    src_csv = next((p for p in DBLP_SOURCES if p.exists()), None)
    if src_csv is None:
        raise SystemExit("citeseer needs the DBLP extract — run scripts/setup.sh (or "
                         "scripts/run_scalability.sh) first to generate "
                         + str(DBLP_SOURCES[0].parent.parent))
    n_authors = 15
    rows, gts, citations = [], [], set()
    with open(src_csv, newline="", encoding="utf-8") as f:
        for rec in csv.DictReader(f):
            authors = [a.strip() for a in rec["authors"].split(";") if a.strip()][:n_authors]
            if not authors or not rec["title"] or not rec["year"]:
                continue
            citation = rec["year"] + rec["title"] + authors[0]  # W&T: year[1-n] + title[1-n] + author1[1-n]
            if citation in citations:
                continue
            citations.add(citation)
            rows.append([rec["year"], rec["title"]] + authors + [""] * (n_authors - len(authors)))
            gts.append([rec["year"], rec["title"], authors[0], citation])
            if len(rows) == n:
                break
    if len(rows) < n:
        print(f"  (citeseer: only {len(rows)} distinct records available in {src_csv})")
    cols = ["year", "title"] + [f"author{i}" for i in range(1, n_authors + 1)]
    write_case("citeseer", cols, rows, "citation", gts, ["year", "title", "author1"], rng)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--n", type=int, default=1000, help="rows per case (default 1000)")
    args = ap.parse_args()
    rng = random.Random(42)
    print(f"Generating Synthetic benchmark ({args.n} rows per case)")
    gen_userid(args.n, rng)
    gen_time(args.n, rng)
    gen_nameconcat(args.n, rng)
    gen_citeseer(args.n, rng)
    print("done — fixtures in", FIXTURES)


if __name__ == "__main__":
    main()
