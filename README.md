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

### Image generation

Generate one image from a text prompt through Codex's built-in `$imagegen` capability:

```bash
curl --fail-with-body \
  --request POST http://localhost:8080/api/v1/images/generations \
  --header 'Content-Type: application/json' \
  --data '{"prompt":"A lighthouse during a storm, cinematic editorial illustration."}' \
  --output codex-image.png
```

The response is the generated image as a binary download with an attachment filename such as
`codex-image.png`. It includes `Cache-Control: no-store`; no temporary image path is exposed.

To guide generation with one to five PNG, JPEG, or WebP reference images, send multipart data:

```bash
curl --fail-with-body \
  --request POST http://localhost:8080/api/v1/images/generations \
  --form 'prompt=Keep the composition but use a warm editorial illustration style.' \
  --form 'images=@reference-1.png' \
  --form 'images=@reference-2.jpg' \
  --output codex-image.png
```

Each reference may be at most 10 MB. References are copied into the isolated Codex workspace and
deleted as soon as generation finishes.

Image generation runs in a fresh ephemeral Codex thread and counts against the Codex account's
image-generation usage limits. The image is copied into the response before the temporary Codex
workspace is deleted, including when Codex supplies a temporary saved path.

## Configuration

| Environment variable | Default | Purpose |
|---|---:|---|
| `CODEX_EXECUTABLE` | `codex` | Codex CLI executable path |
| `CODEX_WORKSPACE_ROOT` | JVM temporary directory | Parent for isolated per-request workspaces |
| `CODEX_GENERATED_IMAGES_ROOT` | `$CODEX_HOME/generated_images` | Trusted Codex image cache read and cleaned after generation |
| `CODEX_TIMEOUT` | `2m` | Per-request process timeout |
| `CODEX_MAX_CONCURRENT_REQUESTS` | `4` | Maximum active Codex processes |
| `CODEX_MAX_OUTPUT_BYTES` | `1048576` | Maximum JSONL stdout bytes per process |
| `CODEX_MAX_IMAGE_OUTPUT_BYTES` | `26214400` | Maximum JSONL stdout bytes for an image-generation process |
| `IMAGE_MAX_REFERENCE_FILE_SIZE` | `10MB` | Multipart limit for one reference image |
| `IMAGE_MAX_REFERENCE_REQUEST_SIZE` | `52MB` | Multipart limit for an entire reference-image request |
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
