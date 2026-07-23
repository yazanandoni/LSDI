# LSDI: AutoJoin

This project is a reconstruction of **AutoJoin** ("Auto-Join: Joining Tables by Leveraging Transformations", VLDB 2017) along with the web interface to run and visualize the experiments from the paper.

**How does AutoJoin work?** It learns the transformation between strings in two tables which should be joined, but don't share a column, for example, one table has `Barack Obama` and another one `Obama, Barack (1961-)`, and then uses this transformation to join the tables. 

**What you can do with this project?**

- Run the paper's **Web benchmark** (31 pairs of real tables) using AutoJoin and 7 baseline algorithms and compare the precision/recall score of them, like in Figure 5 from the paper.
- Run the paper's **Synthetic benchmark** (4 reconstructed cases) and compare the precision/recall of all 7 algorithms, just like in Figure 5 from the paper.
- Run the **DBLP scalability benchmark** (from 100 rows to 1M rows) and check the running time as in the paper's Figure 8.
- Step-by-step analysis of the algorithm's process: which q-grams matched, what transformation was learned, what rows were fuzzy-recovered.

## Requirements

This project requires **Docker** and Bash only to run. Any Docker would do:

- **Docker Desktop** ([docker.com](https://www.docker.com/products/docker-desktop/)), comfortable on Windows and macOS, or
- **Docker Engine from the terminal**, such as `docker.io` or `docker-ce` on Linux, or Colima/OrbStack on macOS. The only thing to check is that the `docker` should be available in your PATH and the Compose extension should be enabled (either `docker compose` plugin or the legacy `docker-compose` command, the script works with both).

Everything else (Python, libraries) will be installed by the setup script automatically, if they aren't there yet.

## Getting started

Just one command is enough to start everything:

```bash
scripts/setup.sh
```

On **Windows**, use Git Bash (or PowerShell with):

```powershell
& "C:\Program Files\Git\bin\bash.exe" ./scripts/setup.sh
```

The script will:

1. **Verify your tools** (Python, curl, Docker) and install missing ones, if possible.
2. **Generate DBLP data**, if it hasn't done yet. On the first run, it will download `dblp.xml.gz` (~900MB) from dblp.org and parse it into CSV tables. On the further runs, the script will skip this step.
3. **Run the Docker stack** (backend + frontend). On the first run it will download Maven and npm dependencies. In further runs, it will take just several seconds.

When it says `done.`, open:

- **Web application**: <http://localhost:4200>
- **Backend API** (JSON): <http://localhost:8081>

> Tip: `SKIP_DOCKER=1 scripts/setup.sh` will prepare everything and won't launch Docker. Useful if you want to run things yourself.

## Web app usage

Pages in the navigation menu:

| Page               | Description                                                                                                                                              |
|--------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Dashboard**      | Starting page.                                                                                                                                           |
| **Web Benchmarks** | The 31 pairs of Web-benchmark tables. Choose the pair and the algorithm, or "All", run it, or even "run everything" and get the Figure-5 plot.           |
| **DBLP**           | Scalability tables (from 100 to 1M rows). Only the algorithms measured in the paper (AJ, SM, FJ-C, FJ-O). Large tables can take minutes; slow algorithms are stopped with a timeout. |
| **Synthetic**      | The paper's 4 synthetic cases (UserID, Time, NameConcat, Citeseer — §6.3.3), generated using `scripts/synthetic_benchmark.py`. 7 algorithms are runnable; the results go to the Figure-5-like plot on the Results page. |
| **DBLP Timing**    | Running-time plot in the style of the paper's Figure 8; downloadable as PNG.                                                                             |
| **Results**        | Everything you've run: precision, recall, mismatch samples, CSV download, and the Figure-5 plot with the averages. From there, you can get to the **Trace** view of any run and see how the algorithm worked. |

**Algorithms used in this project, in one sentence each**: `AJ` is the whole AutoJoin algorithm; `AJ-E` is AutoJoin without the fuzzy-join stage; `SM` joins on the longest common substring; `FJ-C`/`FJ-FR`/`FJ-O` are fuzzy-join baselines (column-based / full-row based / best-config oracle); `DQ-P`/`DQ-R` are direct join on q-gram matches (precision-oriented / recall-oriented).

⚠️ **Results are stored **in memory**: shutdown of the backend deletes them all.

## Common commands

```bash
docker compose logs -f backend   # monitor backend logs
docker compose down              # shutdown everything
docker compose up -d             # start (no rebuild)
docker compose up -d --build     # start after code change
```

## Project structure

```
/autojoin        AutoJoin algorithm + baselines (Java library, all the paper logic)
/backend         Spring Boot REST API to run benchmarks and keep the results
/frontend        Angular web application (UI)
/scripts         setup.sh bootstrap + DBLP data generator
docker-compose.yml
```

The paper itself is included as `autojoin-fullversion.pdf` for additional information.

## Troubleshooting

- **"Docker daemon not running"**: start Docker any way you usually start it and run the script: Docker Desktop (Windows/macOS), `sudo systemctl start docker` (Linux), `colima start` (Colima). (The script can start Docker Desktop (Windows/macOS) and Docker system service (Linux) automatically. Anything else like Colima, OrbStack, Rancher Desktop, you have to start manually before running it.)
- **Docker only inside WSL2 (Windows without Docker Desktop)**: run the script *inside* WSL (`bash scripts/setup.sh` from your WSL shell), not from Git Bash on the Windows side — the Windows shell can't access WSL's Docker daemon.
- **Some tool is installed, but still "not found"**: open a new terminal (changes to PATH won't affect the current one) and run the script.
- **Error messages in the backend about missing CSV files**: the DBLP data wasn't generated yet; run `scripts/setup.sh` and let the step 2 finish.
- **Loading the first benchmark takes a long time**: backend scans the CSV files at startup and caches the counts; just wait after the fresh start.