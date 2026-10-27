# Architecture

## Boundaries

`JagatlabsTools` declares five MCP tools with input descriptions and read/write annotations. `WorkflowService` owns validation, generation confirmation, safe outcome logging, and metrics. `JagatlabsClient` translates the fixed workflow into HTTP requests and maps allowlisted response fields to immutable records.

The application uses Spring AI's STDIO server and annotation discovery. There is no embedded web server. This keeps a process scoped to one operator and makes credentials available only to the backend adapter. Spring Boot's banner is disabled; Logback targets stderr. MCP stdout is verified as JSON-RPC in the packaged integration test.

The pinned SDK's STDIO transport negotiates protocol revision `2024-11-05`. The integration test requests a newer revision and checks the supported fallback before continuing. Use an MCP host that accepts this revision; a newer HTTP transport and protocol revision would need a separate compatibility review.

## Endpoint mapping

| MCP tool | Upstream request | Authorization |
| --- | --- | --- |
| search_ai_services | GET /api/v1/ai/ | None |
| get_ai_service | GET /api/v1/ai/{id} | None |
| get_image_model_info | GET /api/v1/replicate/model-info | None |
| create_image_prediction | POST /api/v1/replicate/create-prediction | Configured bearer token |
| get_image_prediction | GET /api/v1/replicate/status/{predictionId} | Configured bearer token |

Creation sends `prompt`, `output_format`, `output_quality`, and `seed` as defined in Jagatlabs' OpenAPI contract. Model/speed/resolution remain backend defaults. Catalog responses unwrap `data` and `meta`. Prediction outputs exclude provider `logs` and `error`; failed status is still returned for the caller to inspect.

## Failure model

The JDK HTTP client has redirects disabled and a finite connect deadline. Each asynchronous request has an explicit whole-response deadline and a receiving subscriber that cancels oversized bodies. This bounds both chunked and fixed-length payloads. No retries occur, including POST. There is no internal polling loop. An ambiguous creation timeout must be reconciled outside this adapter; the API does not document a creation idempotency key.

JSON shape validation separates provider failures from usable workflow data. HTTP errors are converted to stable application codes without copying provider payloads or exception causes. The workflow layer sanitizes unexpected failures as `INTERNAL_ERROR`.

## Mock versus live

Default configuration points at loopback without a token; read tools work once the mock is started. The documented MCP configuration explicitly supplies public fixture token `demo-token` for mock prediction tools. The Python standard-library mock has deterministic IDs and completion responses and no upstream dependency.

Live mode must be selected explicitly, requires an HTTPS origin, and obtains a bearer token from environment configuration. No login, password, credential-management, or administrative operations are exposed. Upstream base URLs are operator configuration, never model-controlled input.

## Observability

Each service invocation creates a correlation ID and records its tool, outcome, and elapsed time on stderr. Metric labels contain only fixed tool names and public error codes. Correlation IDs, prompts, and prediction IDs are not metric labels. Micrometer's simple registry is local and ephemeral; a production adaptation needs an exporter and retention policy. This is operational logging, not a durable compliance audit trail.

## Deliberate tradeoffs

- One Maven module is enough for this adapter; no database duplicates Jagatlabs state.
- Local STDIO avoids prematurely claiming remote authorization support.
- A narrow tool catalog offers stronger validation and reviewability than exposing every OpenAPI endpoint.
- No retry is safer for credit-consuming creation; read retries can be added separately if justified.
- Container images and workflow actions use maintained release tags. Organizations needing immutable supply-chain inputs should pin reviewed digests/commit SHAs and configure automated updates.
- Scaling to multiple users requires a new authorization design, caller identity propagation, per-user credentials, rate limits, and appropriate secret storage.

## Reference sources

- [Spring AI 1.1 STDIO server documentation](https://docs.spring.io/spring-ai/reference/1.1/api/mcp/mcp-stdio-sse-server-boot-starter-docs.html)
- [Jagatlabs OpenAPI contract](https://jagatlabs-jagatlabs-be.avdtfp.easypanel.host/openapi.json)

API contract inspected on October 27, 2026. HTTP schemas do not prove live provider availability or successful paid generation.
