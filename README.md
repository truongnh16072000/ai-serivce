# AI Service

Spring Boot API that sends a prompt to a locally authenticated Codex CLI process and returns the
final assistant message.

## Requirements

- Java 21
- A working `codex` executable authenticated for the operating-system user that runs this service

## Run

```bash
./gradlew bootRun
```

Send a request:

```bash
curl --fail-with-body \
  --request POST http://localhost:8080/api/v1/chat \
  --header 'Content-Type: application/json' \
  --data '{"conversationId":"abc123","message":"Arrange my tasks into a daily schedule."}'
```

The `conversationId` is generated when omitted. Reuse the returned ID in later requests to resume
the same durable Codex thread with its previous context. Turns for one conversation are processed
sequentially; an overlapping request returns `409 Conflict` (or an SSE `error` event).

Success response:

```json
{
  "conversationId": "abc123",
  "answer": "..."
}
```

### Streaming response

Use the SSE endpoint to receive assistant text incrementally:

```bash
curl --no-buffer --fail-with-body \
  --request POST http://localhost:8080/api/v1/chat/stream \
  --header 'Accept: text/event-stream' \
  --header 'Content-Type: application/json' \
  --data '{"conversationId":"abc123","message":"Explain server-sent events."}'
```

The stream emits `started`, one or more `delta`, and `completed` events. If execution fails after
the stream has opened, it emits a terminal `error` event instead of changing the HTTP status.

```text
event:started
data:{"conversationId":"abc123"}

event:delta
data:{"text":"Server-sent"}

event:completed
data:{"conversationId":"abc123","answer":"Server-sent events ..."}
```

Validation and execution failures use `application/problem+json`. Capacity errors return `429`,
Codex failures return `502`, and execution timeouts return `504`.

## Configuration

| Environment variable | Default | Purpose |
|---|---:|---|
| `CODEX_EXECUTABLE` | `codex` | Codex CLI executable path |
| `CODEX_WORKSPACE_ROOT` | JVM temporary directory | Parent for isolated per-request workspaces |
| `CODEX_TIMEOUT` | `2m` | Per-request process timeout |
| `CODEX_MAX_CONCURRENT_REQUESTS` | `4` | Maximum active Codex processes |
| `CODEX_MAX_OUTPUT_BYTES` | `1048576` | Maximum JSONL stdout bytes per process |
| `DATABASE_URL` | Local H2 file | JDBC URL for durable conversation metadata |
| `DATABASE_USERNAME` | `sa` | Database username |
| `DATABASE_PASSWORD` | empty | Database password |
| `DATABASE_POOL_SIZE` | `6` | Maximum JDBC connection pool size |

Prompts are encoded as App Server JSON-RPC messages and never interpolated into a shell command.
Codex runs in an empty temporary workspace with the read-only sandbox. Application conversation
metadata and completed user/assistant messages are stored in the configured relational database;
Codex thread history is persisted under `CODEX_HOME` and resumed by its stored thread ID.

This service does not define a deployment-specific identity system. Before exposing it beyond a
trusted network, put it behind authenticated ingress and enforce caller-level rate limits in
addition to the built-in process concurrency limit.

## Verify

```bash
./gradlew clean build
```
