# Spring Boot + Local Codex Integration

## Goal

Build a backend that accepts a user's question, sends it to a locally installed Codex CLI, and returns or streams Codex's response to a web or mobile client.

## Architecture

```text
Web / Mobile Client
        |
        v
Spring Boot API
  - authentication
  - validation
  - rate limiting
  - conversation handling
        |
        v
Java ProcessBuilder
        |
        v
codex exec --json --ephemeral
        |
        v
JSONL events
        |
        v
HTTP response, SSE, or WebSocket stream
```

## Recommended MVP

Use Java `ProcessBuilder` to launch a fresh, ephemeral Codex process for each request:

```bash
codex exec --json --ephemeral "<user question>"
```

For safer argument handling, the backend can pass the prompt through standard input instead of embedding it in a shell command. Read Codex's JSONL output progressively and translate it into either:

- A normal JSON response for simple request/response behavior.
- Server-Sent Events (SSE) or WebSockets for streaming updates.

## Example API and Process Flow

Request:

```http
POST /api/v1/chat
Content-Type: application/json
```

```json
{
  "conversationId": "abc123",
  "message": "Arrange my tasks into a daily schedule."
}
```

Conceptual Spring Boot endpoint:

```java
@PostMapping("/api/v1/chat")
public AskResponse ask(@RequestBody AskRequest request) {
    return codexService.ask(request.message());
}
```

Conceptual process launch:

```java
ProcessBuilder processBuilder = new ProcessBuilder(
    "codex", "exec", "--json", "--ephemeral", request
);

processBuilder.redirectErrorStream(true);
Process process = processBuilder.start();
```

The service should consume JSONL events, collect or stream assistant messages, handle process failures and timeouts, and return a stable application-level response format.

## Security Cautions

Do not expose unrestricted Codex execution directly to users. Codex can potentially interact with files, tools, and shell commands depending on its configuration.

Apply these controls:

- Run Codex in an isolated, dedicated working directory.
- Use an appropriate restrictive sandbox, such as read-only when filesystem access is unnecessary.
- Keep sessions ephemeral when conversation persistence is not required.
- Never construct a shell command by concatenating raw user input; use `ProcessBuilder` arguments or standard input.
- Validate request size and content, authenticate callers, and enforce rate limits.
- Add execution timeouts, output limits, concurrency limits, logging, and safe process termination.
- Do not expose secrets or sensitive server files to the Codex process.

## Alternatives

| Option | Best fit | Trade-off |
|---|---|---|
| OpenAI Responses API | General AI responses without local agent or filesystem behavior | Simplest backend integration, but does not use the locally authenticated Codex CLI |
| `ProcessBuilder` + `codex exec` | Fast Spring Boot MVP using local Codex | A new process per request and lifecycle management are required |
| Codex App Server | Deeper, longer-lived production integration | More capable protocol integration, but more implementation complexity in Java |
| Codex TypeScript SDK | Node.js or TypeScript backends | Convenient SDK, but not a native Spring Boot solution |

## Recommendation

Start with **Spring Boot + Java `ProcessBuilder` + `codex exec --json --ephemeral`**. Keep the public API independent of the Codex integration details so the backend can later migrate to Codex App Server or the Responses API without requiring client changes.
