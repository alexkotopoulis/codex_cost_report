# Codex Cost Report

A single Maven-built Helidon application that serves both:

- JSON APIs for token usage read from `~/.codex/sessions/**/*.jsonl`
- A Preact dashboard for month, week, day, and session drilldown views

The scanner sums each `token_count` event's `last_token_usage` for a session. Session prompts are extracted from user message records, excluding the synthetic environment context blocks.

## Run

```bash
mvn package
java -jar target/codex-cost-report-1.0.0-SNAPSHOT.jar
```

Open `http://localhost:8080/`.

Use a different session directory or port when needed:

```bash
java -Dcodex.sessions.dir=/path/to/sessions -Dserver.port=9090 -jar target/codex-cost-report-1.0.0-SNAPSHOT.jar
```

## API

- `GET /api/overview`
- `GET /api/sessions/{id}`
