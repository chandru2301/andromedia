# Linear ingestion

Indexes Linear issues (title, description, comments) into the Lucene service index as `sourceType=LINEAR`.

## Auth

- GraphQL Bearer token (`lin_api_…`) at `https://api.linear.app/graphql`

## Endpoints

- `POST /api/v1/integrations/linear/test`
- `POST /api/v1/index/linear`
- `GET /api/v1/index/linear/{jobId}`

### Test

```bash
curl -X POST http://localhost:8083/api/v1/integrations/linear/test \
  -H 'Content-Type: application/json' \
  -d '{"apiKey":"lin_api_xxx","teamKeys":["Softtwig"]}'
```

Team identifiers accept **team key** (e.g. `SOF`) or **team name** (e.g. `Softtwig`).

### Index

```bash
curl -X POST http://localhost:8083/api/v1/index/linear \
  -H 'Content-Type: application/json' \
  -d '{"service":"local","apiKey":"lin_api_xxx","teamKeys":["Softtwig"],"lookbackDays":90,"embed":false}'
```

## Filtering

- Team keys required for indexing
- Cancelled/duplicate states older than `lookbackDays` (default 90) are skipped

## Rebuild note

Run `mvn -pl core -am spring-boot:run` so the `ingestion` module is rebuilt with core.
