# Jagatlabs MCP AI Workflow Assistant

## Intent
Build a runnable GitHub portfolio project demonstrating enterprise engineering practices through a Spring MCP adapter for the existing Jagatlabs AI backend. A reviewer must be able to complete a realistic workflow without credentials or paid API calls.

## Goals
- Discover AI services, inspect details and model capabilities, submit image generation, and check prediction status through MCP.
- Demonstrate explicit contracts, validation, secure credential handling, bounded network calls, operational visibility, and reproducible builds.
- Provide a fully local mock backend and realistic success and failure examples.

## Constraints and approach
The user selected the AI workflow assistant over a catalog-only server. Catalog-only is simpler but does not demonstrate asynchronous authenticated workflows. A full adapter for every backend endpoint would add unrelated administration and credential-management surface. Implement a curated five-tool adapter instead.

Proposed engineering choices: Java 21, Maven wrapper, Spring Boot and a compatible stable Spring AI release verified against official documentation at implementation time. A single application with tool, application-service, and upstream-client layers is sufficient. No separate database is needed because Jagatlabs owns state.

## MCP contract
Use local STDIO transport for version one. It gives desktop MCP clients a simple launch command and keeps the server's configured upstream identity isolated to one local operator. Logging goes to stderr, never the protocol's stdout. A remotely shared multiuser MCP endpoint and OAuth authorization server are out of scope for this version.

Tools:
1. `search_ai_services`: GET `/api/v1/ai/`, with search/category and bounded page/limit; active entries by default.
2. `get_ai_service`: GET `/api/v1/ai/{ai_id}`, positive integer ID.
3. `get_image_model_info`: GET `/api/v1/replicate/model-info`.
4. `create_image_prediction`: POST `/api/v1/replicate/create-prediction`, required prompt and a documented subset of options from ReplicateImageRequest. Returns prediction ID and status immediately.
5. `get_image_prediction`: GET `/api/v1/replicate/status/{prediction_id}`, validated path-safe identifier; returns status and available image URLs.

Match request field names and supported enum values to the published OpenAPI contract. Use typed outputs containing only workflow-relevant fields. Do not expose raw provider logs or raw upstream error bodies. Upstream data is untrusted content, never instructions.

Generation can consume credits: tool metadata and documentation must identify this side effect. Require explicit `confirmGeneration=true` as a guard against accidental calls; document that it is not a substitute for host/client user approval. Do not automatically retry creation. Status checks are one request per invocation, with no blocking polling loop.

## Configuration and security
Default to the local mock workflow. Live mode requires an explicit configured HTTPS base URL and an environment-provided Jagatlabs bearer token for generation/status. Public catalog operations do not require a token. No login/password or token-management tools. Credentials must never appear in tool arguments, logs, errors, fixtures, or source control. Each local process represents a single upstream account; never present this as safe for multiple remote users.

Allow HTTP only for explicitly configured loopback mock endpoints. Disable redirects to prevent credential forwarding. Validate user inputs and encode query parameters; tool callers cannot override upstream hosts or paths. Configure finite connect/read timeouts and response-size limits. No retries in version one; expose stable error codes for validation, authentication, authorization, missing resources, throttling, timeout, unavailable upstream, and malformed responses.

## Operations and delivery
Log tool name, outcome, duration, and correlation ID without prompts, credentials, full URLs, or response bodies. Include basic Micrometer counters/timers and describe their local operational use; no public management HTTP server is needed for STDIO.

Deliver Maven wrapper, Dockerfile running as a non-root user, compose setup for a local HTTP mock backend, environment example with placeholders, GitHub Actions build/test checks and dependency review where supported, README quick start and client configuration, architecture notes and security limitations. Docker STDIO invocation must preserve stdin and avoid a TTY.

## Verification
Unit tests cover validation and workflow guards. HTTP integration tests cover exact methods, paths, query encoding, bearer-header behavior, timeout, non-2xx mapping, invalid JSON, and creation without retries. An MCP protocol smoke test initializes the actual server, lists tools, and performs catalog, create, and status calls against the mock backend. Assert protocol stdout remains valid. CI runs `mvn verify`; local verification uses the same command. Paid live generation is not part of automated verification.

## Non-goals and success criteria
No frontend, admin tools, database, deployment, GitHub publication, remote multiuser hosting, or compliance certification. Success means a reviewer can clone, build, configure an MCP client, and complete the mocked workflow using documented commands, with tests proving both normal behavior and important failure boundaries.

## Left to implementation
Package names, compatible dependency patch versions, fixture tooling, logging format, and test libraries are builder choices. Any remote HTTP transport or credential delegation is a future design change.
