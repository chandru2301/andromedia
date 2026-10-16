# Jira ingestion

Indexes Jira Cloud issues (title, ADF-converted description, comments) into the Lucene service index as `sourceType=JIRA`.

## Auth

- REST Basic Auth: email + API token
- Base URL: `https://your-site.atlassian.net`

## Endpoints

- `POST /api/v1/integrations/jira/test`
- `POST /api/v1/index/jira`
- `GET /api/v1/index/jira/{jobId}`

Jira Cloud search uses `POST /rest/api/3/search/jql` (the old `/rest/api/3/search` returns HTTP 410).

### Test

```bash
curl -X POST http://localhost:8083/api/v1/integrations/jira/test \
  -H 'Content-Type: application/json' \
  -d '{"baseUrl":"https://yourname.atlassian.net","email":"you@gmail.com","apiToken":"xxx","projectKeys":["KAN"]}'
```

### Index

```bash
curl -X POST http://localhost:8083/api/v1/index/jira \
  -H 'Content-Type: application/json' \
  -d '{"service":"local","baseUrl":"https://yourname.atlassian.net","email":"you@gmail.com","apiToken":"xxx","projectKeys":["KAN"],"lookbackDays":90,"embed":false}'
```

## ADF plain text

Jira Cloud returns descriptions and comments as Atlassian Document Format (ADF) JSON. `JiraApiClient` recursively extracts `text` fields from nested `content` arrays before chunking — raw ADF is never stored.

## Filtering

Closed/cancelled/done/resolved issues older than `lookbackDays` (default 90) are skipped. Open issues are kept regardless of age.

## Rebuild note

Run `mvn -pl core -am spring-boot:run` so the `ingestion` module is rebuilt with core.
