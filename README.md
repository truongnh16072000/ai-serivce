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

The `conversationId` is echoed when supplied and generated when omitted. Each Codex execution is
ephemeral, so this ID is an application correlation ID; it does not resume Codex context.

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

Prompts are written through stdin and never interpolated into a shell command. Codex runs with an
empty temporary workspace, `--ephemeral`, `--ignore-user-config`, and the read-only sandbox. The
temporary workspace is deleted after every request.

This service does not define a deployment-specific identity system. Before exposing it beyond a
trusted network, put it behind authenticated ingress and enforce caller-level rate limits in
addition to the built-in process concurrency limit.

## Verify

```bash
./gradlew clean build
```
