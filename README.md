# Jagatlabs MCP Server

A Java 21 / Spring Boot MCP server that lets an AI assistant discover Jagatlabs AI services, submit image generation, and track its result. Built as a portfolio project with a real HTTP integration boundary and a reproducible local demo.

**Start locally with the mock backend. No account, provider key, or generation credits are needed.** Live mode uses your Jagatlabs account and can consume credits.

## Workflow

```text
MCP client → Spring AI STDIO server → validated workflow service → Jagatlabs HTTP API
                                                                 ↳ local mock for demos
```

1. Search for an image service and inspect its details.
2. Read the model information.
3. Approve generation and submit a prompt.
4. Check the returned prediction ID for status and output URLs.

This server supplies tools to an MCP host. It does not run an LLM or require an LLM API key itself.

## Quick start

Requirements: JDK 21; Python 3.12+ for the mock, or Docker Compose. Maven is downloaded by the wrapper on first use.

Build and verify on macOS/Linux:

```sh
./mvnw -B verify
python3 demo/mock_backend.py
```

On Windows PowerShell:

```powershell
.\mvnw.cmd -B verify
python demo/mock_backend.py
```

Keep the mock running, then configure your MCP client with the following server entry. Replace the JAR path with its absolute path on your machine:

```json
{
  "mcpServers": {
    "jagatlabs": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/jagatlabs-mcp-server/target/jagatlabs-mcp-server.jar"],
      "env": {
        "JAGATLABS_MODE": "mock",
        "JAGATLABS_BASE_URL": "http://127.0.0.1:8090",
        "JAGATLABS_TOKEN": "demo-token"
      }
    }
  }
}
```

On Windows use a path such as `D:/Apps/sping-mcp/target/jagatlabs-mcp-server.jar`. Use the client's STDIO server configuration format if its settings differ. `demo-token` is a public fixture value, not a production credential.

Launching the JAR directly will wait for MCP messages on stdin. Use an MCP client to interact; there is no browser UI or listening HTTP port.

## Tools

| Tool | Arguments | Behavior |
| --- | --- | --- |
| `search_ai_services` | Optional `search`, `category`, `page` (default 1), `limit` (default 10, max 100) | Searches active catalog entries |
| `get_ai_service` | `id`: positive integer | Retrieves one catalog service |
| `get_image_model_info` | None | Reads allowlisted model information |
| `create_image_prediction` | `prompt`, `confirmGeneration`; optional `outputFormat`, `outputQuality`, `seed` | Submits once and returns a prediction ID |
| `get_image_prediction` | `predictionId` | Checks once; returns status and available URLs |

Example tool arguments for the local demo:

```json
{"search":"garden","category":"image","page":1,"limit":10}
```

```json
{"prompt":"A peaceful garden in watercolor","confirmGeneration":true,"outputFormat":"webp","outputQuality":90,"seed":42}
```

```json
{"predictionId":"demo-prediction-1"}
```

Try asking your assistant: “Find the garden image service and inspect it. Ask for my approval before generating a watercolor garden, then check the prediction.” The mock returns a deterministic ID and an illustrative `example.com` output URL; it does not generate a real image.

Generation accepts `png`, `jpg`, or `webp`, quality 1..100, seed -1..2147483647, and a nonblank prompt up to 4000 characters. The upstream supplies defaults for model type, speed, and resolution. `confirmGeneration=true` is an accidental-call guard; your MCP host must still obtain real user approval.

## Docker demo

```sh
docker compose build mcp
docker compose up -d --wait mock
```

Configure a STDIO MCP server to launch:

```json
{
  "command": "docker",
  "args": ["compose", "-f", "/absolute/path/to/compose.yaml", "run", "--rm", "-T", "mcp"]
}
```

`-T` avoids a TTY and preserves JSON protocol output; Compose attaches stdin. The MCP container shares the mock's network namespace and reaches it over loopback. The runtime image runs as UID 10001; Compose adds a read-only filesystem and drops Linux capabilities. Stop the mock with `docker compose down`.

## Live Jagatlabs connection

Use the [Jagatlabs API documentation](https://jagatlabs-jagatlabs-be.avdtfp.easypanel.host/api-docs) to obtain the appropriate account access. In your MCP client's environment settings, set:

```text
JAGATLABS_MODE=live
JAGATLABS_BASE_URL=https://jagatlabs-jagatlabs-be.avdtfp.easypanel.host
JAGATLABS_TOKEN=<your-Jagatlabs-bearer-token>
```

Supply tokens through your client's secret settings or process environment. Do not put real tokens in committed JSON files or tool arguments. The `.env.example` file is documentation; the Java application does not automatically read `.env` files. Public catalog and model-info calls do not send authorization. Prediction creation and status require the configured bearer token.

This is a **single-operator local server**. Every tool uses that operator's upstream account. Remote hosting, multiuser authorization, token delegation, and OAuth discovery are outside this release. See [security boundaries](SECURITY.md).

## Reliability and operations

- Finite connect and whole-response deadlines; bounded bodies, including chunked responses.
- Redirects disabled. HTTP is permitted only for loopback mock mode; live mode requires HTTPS.
- Creation is never automatically retried. If creation times out, the submission outcome is unknown: do not automatically submit again.
- Validated tool inputs, fixed upstream routes, encoded query values, and typed allowlisted outputs.
- Stable errors: `VALIDATION_ERROR`, `GENERATION_CONFIRMATION_REQUIRED`, `AUTHENTICATION_REQUIRED`, `ACCESS_DENIED`, `NOT_FOUND`, `RATE_LIMITED`, `UPSTREAM_TIMEOUT`, `UPSTREAM_REJECTED`, `UPSTREAM_UNAVAILABLE`, `INVALID_UPSTREAM_RESPONSE`, `INTERNAL_ERROR`.
- Logs go to stderr and record tool, outcome, duration, and a correlation ID. Prompts, tokens, response bodies, and provider logs are excluded.
- In-process Micrometer counters (`jagatlabs.tool.calls`) and timers (`jagatlabs.tool.duration`) demonstrate instrumentation. There is no metrics HTTP endpoint or durable audit store; attach a registry/exporter when adapting for deployment.

Do not enable framework HTTP wire logging in an environment containing real credentials. Backend descriptions and output URLs are untrusted data, not instructions. The server returns URLs without fetching them.

## Verification and development

```sh
./mvnw -B verify
python3 -m unittest discover -s demo -p 'test_*.py'
```

`verify` runs unit/HTTP integration tests, builds the executable JAR, and launches a real child process to test MCP initialization, tool schemas, the mock workflow, error handling, stdout purity, and EOF shutdown. Tests use local fixtures and never call paid APIs. JaCoCo reports appear in `target/site/jacoco/index.html`; protocol stderr appears in `target/mcp-test-stderr.log`.

GitHub Actions verifies Linux and Windows, builds the container, and tests the demo backend. Dependency Review checks public-repository pull requests; Dependabot proposes dependency updates. These workflows must run on your GitHub repository before their status can be claimed.

If your Windows network uses a trusted TLS inspection certificate, Maven may need the Windows certificate store rather than your JDK's store. For that environment, set `MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NUL` in your shell. Certificate verification remains enabled; do not disable TLS checks.

See [architecture and tradeoffs](docs/architecture.md), [verification results](docs/verification.md), [approved design](docs/superpowers/specs/2026-10-27-jagatlabs-mcp-design.md), and [implementation plan](docs/superpowers/plans/2026-10-27-jagatlabs-mcp.md).

## Project status

Portfolio reference implementation using Spring AI 1.1.8 and Spring Boot 3.5.16 on Java 21. It demonstrates enterprise engineering practices; it is not a certified production platform. Live compatibility depends on the external API and credentials. The model-info endpoint has a loosely specified response schema; unavailable allowlisted fields remain null. Live paid generation is deliberately excluded from verification.
