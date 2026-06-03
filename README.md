# LSDI - AutoJoin Benchmark Suite

Interactive web UI for running AutoJoin benchmarks, visualizing precision/recall, and uploading your own CSV pairs.

## Quick start

```bash
docker compose up --build
```

- **Frontend**: [http://localhost:4200](http://localhost:4200)
- **Backend API**: [http://localhost:8080](http://localhost:8080)

## Architecture

| Service   | Stack                              | Port |
|-----------|-------------------------------------|------|
| Backend   | Java 17 + Spring Boot + Maven      | 8080 |
| Frontend  | Angular 17 + TypeScript + ECharts  | 4200 |

Both run in Docker with hot reload on the frontend.

## Project structure

```
/autojoin        Core Java library (join algorithm)
/backend         Spring Boot REST API wrapper
/frontend        Angular single‑page application
docker-compose.yml
```

## API endpoints

| Method | Path                      | Description                  |
|--------|---------------------------|------------------------------|
| GET    | `/api/benchmarks`         | list available fixtures      |
| POST   | `/api/benchmarks/run`     | run a benchmark by pairId    |
| GET    | `/api/results`            | list past run summaries      |
| GET    | `/api/results/{id}`       | detailed result + mismatches |
| POST   | `/api/uploads/join`       | upload CSVs and run join     |