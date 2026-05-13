# Web Benchmark Fixtures

This directory documents how web-benchmark tables are wired into AutoJoin.

## Raw data

Raw CSVs live under:

`data/raw/web-benchmark/<pair-id>/`

Each pair directory includes:

- `source.csv`
- `target.csv`
- `ground truth.csv`

## Fixtures

Fixtures live under:

`data/fixtures/web-benchmark/<pair-id>/fixture.json`

The fixture defines join key columns and how to interpret ground truth.

See `data/fixtures/web-benchmark/_schema.json` for the schema.

## Example

`data/fixtures/web-benchmark/beatles songs/fixture.json`

Key columns:

- source: `Title`
- target: `Title`
- ground truth: `source-Title` -> `target-Title`
