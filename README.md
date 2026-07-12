# LSDI - AutoJoin Benchmark Suite

Interactive web UI for running AutoJoin benchmarks (paper's Web + DBLP-scalability sets, all 8 §6.2 methods) and visualizing precision/recall and running-time figures.

## Quick start

```bash
docker compose up --build
```

- **Frontend**: [http://localhost:4200](http://localhost:4200)
- **Backend API**: [http://localhost:8081](http://localhost:8081)

## Architecture

| Service   | Stack                              | Port |
|-----------|-------------------------------------|------|
| Backend   | Java 17 + Spring Boot + Maven      | 8081 (container 8080) |
| Frontend  | Angular 17 + TypeScript + ECharts  | 4200 |

Both run in Docker with hot reload on the frontend.

## Project structure

```
/autojoin        Core Java library (join algorithm)
/backend         Spring Boot REST API wrapper
/frontend        Angular single‑page application
/scripts         DBLP data generation + setup helpers
docker-compose.yml
```

## API endpoints

| Method | Path                          | Description                          |
|--------|-------------------------------|--------------------------------------|
| GET    | `/api/benchmarks`             | list available fixtures              |
| POST   | `/api/benchmarks/run-async`   | start a run (pairId, method); returns job id |
| GET    | `/api/benchmarks/jobs/{id}`   | poll a running job                   |
| GET    | `/api/results`                | list past run summaries              |
| GET    | `/api/results/{id}`           | detailed result + mismatches         |
| GET    | `/api/results/{id}/csv`       | joined pairs as CSV                  |
| GET    | `/api/results/{id}/trace`     | per-stage algorithm trace            |
| DELETE | `/api/results`                | erase all stored runs                |
| GET    | `/api/system/info`            | heap + baseline-timeout settings     |
| POST   | `/api/uploads/join`           | upload CSVs and run join (API only; no UI yet) |
