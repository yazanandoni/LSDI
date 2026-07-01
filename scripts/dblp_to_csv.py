#!/usr/bin/env python3
"""Build DBLP-derived source/target tables for the Auto-Join §6.4 scalability test.

The paper (Section 6.4, Figure 8) takes the DBLP bibliography — where each record
has authors, title and year — and builds:

  * a SOURCE table with three columns  (authors, title, year), and
  * a TARGET table with one column that CONCATENATES those three fields.

Auto-Join then has to learn the concatenation transform to join them. This script
reproduces that setup, emitting both tables from the SAME N records so the join is
a clean 1:1 (high join-participation), for several N.

Streaming (lxml.iterparse) keeps memory bounded on the ~4 GB uncompressed XML.

Usage:
    python dblp_to_csv.py dblp.xml.gz OUTDIR [--n 100 1000 ...]
                                           [--sep " | "]
                                           [--fixtures-dir FIXDIR]

    OUTDIR receives <N>/source.csv, <N>/target.csv, <N>/ground_truth.csv
    FIXDIR receives <N>/fixture.json  (web-benchmark fixture metadata)

    Run from the directory that holds dblp.dtd so the DTD entities resolve.

Requires: pip install lxml
"""
import argparse
import csv
import gzip
import json
import os
import sys

try:
    from lxml import etree
except ImportError:
    sys.exit("lxml is required: pip install lxml")

# DBLP publication record types that carry author/title/year.
PUB_TAGS = {"article", "inproceedings", "incollection", "book", "proceedings"}


def _text(el):
    """Full text content of an element (joins nested markup like <i>/<sub>)."""
    if el is None:
        return None
    return " ".join("".join(el.itertext()).split())


def records(path):
    """Yield (authors, title, year) for each complete publication record."""
    stream = gzip.open(path, "rb") if path.endswith(".gz") else open(path, "rb")
    # load_dtd + resolve_entities expands DBLP's custom entities (&auml; etc.);
    # recover tolerates the odd malformed record; huge_tree lifts libxml2 limits.
    ctx = etree.iterparse(stream, events=("end",), load_dtd=True,
                          resolve_entities=True, recover=True, huge_tree=True)
    try:
        for _, el in ctx:
            if el.tag in PUB_TAGS:
                authors = [_text(a) for a in el.findall("author")]
                authors = [a for a in authors if a]
                title = _text(el.find("title"))
                year = _text(el.find("year"))
                if authors and title and year:
                    yield "; ".join(authors), title, year
                # Free the element and its already-processed siblings.
                el.clear()
                while el.getprevious() is not None:
                    del el.getparent()[0]
    finally:
        stream.close()


def write_fixture(fixtures_dir, size, outdir_name, sep):
    """Write fixture.json for a given size, matching the web-benchmark schema."""
    pair_id = f"dblp-{size}"
    fixture = {
        "pair_id": pair_id,
        "source": {
            "file": f"data/raw/{outdir_name}/{size}/source.csv",
            "key_columns": ["authors", "title", "year"]
        },
        "target": {
            "file": f"data/raw/{outdir_name}/{size}/target.csv",
            "key_columns": ["record"]
        },
        "ground_truth": {
            "file": f"data/raw/{outdir_name}/{size}/ground_truth.csv",
            "format": "row_values",
            "source_key_columns": ["authors", "title", "year"],
            "target_key_columns": ["record"]
        }
    }
    d = os.path.join(fixtures_dir, pair_id)
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "fixture.json"), "w", encoding="utf-8") as f:
        json.dump(fixture, f, indent=2)
        f.write("\n")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dblp", help="path to dblp.xml or dblp.xml.gz")
    ap.add_argument("outdir", help="directory to write <N>/{source,target,ground_truth}.csv into")
    ap.add_argument("--n", type=int, nargs="+",
                    default=[100, 1000, 10000, 100000, 1000000],
                    help="table sizes to emit")
    ap.add_argument("--sep", default=" | ",
                    help="separator used to concatenate the target column")
    ap.add_argument("--fixtures-dir",
                    help="if set, write fixture.json (web-benchmark format) into this directory")
    args = ap.parse_args()

os.makedirs(args.outdir, exist_ok=True)
    largest = max(args.n)

    outdir_name = os.path.basename(os.path.normpath(os.path.abspath(args.outdir)))

    for n in sorted(args.n):
        if n > len(rows):
            print(f"skipping N={n:,} (only {len(rows):,} records available)")
            continue
        sub = rows[:n]
        d = os.path.join(args.outdir, str(n))
        os.makedirs(d, exist_ok=True)

        # source.csv — three columns
        src_path = os.path.join(d, "source.csv")
        with open(src_path, "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["authors", "title", "year"])
            w.writerows(sub)

        # target.csv — single concatenated column
        tgt_path = os.path.join(d, "target.csv")
        with open(tgt_path, "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["record"])
            for authors, title, year in sub:
                w.writerow([f"{authors}{args.sep}{title}{args.sep}{year}"])

        # ground_truth.csv — source key columns followed by target key column
        gt_path = os.path.join(d, "ground_truth.csv")
        with open(gt_path, "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["authors", "title", "year", "record"])
            for authors, title, year in sub:
                w.writerow([authors, title, year,
                           f"{authors}{args.sep}{title}{args.sep}{year}"])

        if args.fixtures_dir:
            write_fixture(args.fixtures_dir, n, outdir_name, args.sep)

        print(f"wrote {n:,} rows -> {d}")


if __name__ == "__main__":
    main()
