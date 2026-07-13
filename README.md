# LSDI — AutoJoin Benchmark Suite

This project is a reimplementation of **AutoJoin** ("Auto-Join: Joining Tables by Leveraging Transformations", VLDB 2017) with an interactive web interface for running and visualizing the paper's experiments.

**What does AutoJoin do?** Given two tables that belong together but have no common key column — for example one table with `Barack Obama` and another with `Obama, Barack (1961-)` — it automatically *learns* a string transformation that converts one format into the other, and then uses it to join the tables. No manual rules, no configuration.

**What can you do with this project?**

- Run the paper's **Web benchmark** (31 real table pairs) with AutoJoin and 7 baseline methods, and compare precision/recall like the paper's Figure 5.
- Run the **DBLP scalability benchmark** (100 → 1,000,000 rows) and see running times like the paper's Figure 8.
- Inspect any run step by step: which q-grams matched, which transformation was learned, which rows were fuzzy-recovered.

## Requirements

You only need **Docker** and a Bash shell. Any Docker works:

- **Docker Desktop** ([docker.com](https://www.docker.com/products/docker-desktop/)) — easiest on Windows and macOS, or
- **Docker Engine from the terminal** — e.g. `docker.io`/`docker-ce` on Linux, or Colima/OrbStack on macOS. The only requirement is that `docker` is on your PATH and Compose is available (the `docker compose` plugin or the legacy `docker-compose` binary — the script accepts both).

Everything else (Python, libraries) the setup script installs by itself if missing.

## Getting started

One command bootstraps everything:

```bash
scripts/setup.sh
```

On **Windows**, run it from Git Bash (or from PowerShell via):

```powershell
& "C:\Program Files\Git\bin\bash.exe" ./scripts/setup.sh
```

The script:

1. **Checks your tools** (Python, curl, Docker) and installs missing ones where it can.
2. **Generates the DBLP data** if it isn't there yet. The first run downloads `dblp.xml.gz` (~900 MB) from dblp.org and parses it into CSV tables — this takes a while, grab a coffee. Later runs skip this step entirely.
3. **Builds and starts** the Docker stack (backend + frontend). The first build downloads Maven and npm dependencies, so it is also slow once — after that, starts take seconds.

When it prints `done.`, open:

- **Web app**: <http://localhost:4200>
- **Backend API** (JSON): <http://localhost:8081>

> Tip: `SKIP_DOCKER=1 scripts/setup.sh` prepares the data and dependencies but doesn't start Docker — useful if you want to run things yourself.

## Using the app

The navigation bar has these pages:

| Page | What it's for |
|---|---|
| **Dashboard** | Overview and entry point. |
| **Benchmarks** | The 31 Web-benchmark table pairs. Pick a pair and a method (or "All") and run it — or run *everything* with one button to fill the Figure-5 chart. |
| **DBLP** | The scalability tables (100 to 1M rows). Only the methods the paper times here (AJ, SM, FJ-C, FJ-O). Big sizes can take minutes; slow baselines are cut off by a timeout, just like in the paper. |
| **Synthetic** | The paper's 4 synthetic cases (UserID, Time, NameConcat, Citeseer — §6.3.3), reconstructed by `scripts/synthetic_benchmark.py`. All 8 methods runnable; results feed a Figure-9d-style chart on the Results page. |
| **Scalability** | Running-time chart in the style of the paper's Figure 8 (AutoJoin's stages stacked, baselines as lines, timeouts marked). Downloadable as PNG. |
| **Results** | Every run you've done: precision, recall, mismatch samples, CSV download, and the Figure-5-style averages chart. From here you can open the **Trace** view of a run to see the algorithm work step by step. |

**The methods, in one line each:** `AJ` is the full AutoJoin algorithm; `AJ-E` is AutoJoin without the fuzzy-join step; `SM` joins on the longest shared substring; `FJ-C`/`FJ-FR`/`FJ-O` are fuzzy-join baselines (single column / full row / best-configuration oracle); `DQ-P`/`DQ-R` join directly on q-gram matches (precision- / recall-oriented).

⚠️ Results are kept **in memory** — restarting the backend clears them.

## Everyday commands

```bash
docker compose logs -f backend   # watch the backend logs
docker compose down              # stop everything
docker compose up -d             # start again (no rebuild)
docker compose up -d --build     # rebuild after code changes
```

## Project structure

```
/autojoin        The AutoJoin algorithm + baselines (Java library, all the paper logic)
/backend         Spring Boot REST API that runs benchmarks and stores results
/frontend        Angular web app (the UI)
/scripts         setup.sh bootstrap + DBLP data generator
docker-compose.yml
```

The paper itself is included as `autojoin-fullversion.pdf` if you want the full details behind any of this.

## Troubleshooting

- **"Docker daemon not running"** — start Docker however you run it, then re-run the script: Docker Desktop on Windows/macOS, `sudo systemctl start docker` on Linux, `colima start` for Colima. (The script can auto-start Docker Desktop (Windows/macOS) and the Docker system service (Linux). Anything else like Colima, OrbStack, Rancher Desktop, you need to start yourself before running it.)
- **Docker only inside WSL2 (Windows without Docker Desktop)** — run the script *inside* WSL (`bash scripts/setup.sh` from your WSL shell), not from Git Bash on the Windows side; the Windows shell can't see WSL's Docker daemon.
- **A tool was installed but still "not found"** — open a *new* terminal (PATH changes don't reach the old one) and re-run.
- **Backend errors about missing CSV files** — the DBLP data wasn't generated; run `scripts/setup.sh` again and let step 2 finish.
- **First benchmark list load is slow** — the backend scans the CSVs once at startup and caches the counts; give it a moment after a fresh start.
