# Jagatlabs MCP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a runnable portfolio MCP server backed by Jagatlabs, with a credential-free mock workflow.

**Architecture:** A local STDIO server exposes five curated tools through Spring AI. A workflow service validates inputs and delegates to a bounded HTTP client; a separate local mock backend exercises the same HTTP boundary.

**Tech Stack:** Java 21, Maven wrapper, compatible stable Spring Boot/Spring AI, JUnit, HTTP fixtures, Docker, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-27-jagatlabs-mcp-design.md`

## Global Constraints
- Local STDIO transport for version one; logging goes to stderr.
- Default to the local mock workflow; live mode requires explicit HTTPS base URL.
- Credentials come from environment configuration, never tool inputs.
- Do not automatically retry creation; require `confirmGeneration=true`.
- No database, frontend, remote multiuser endpoint, or live paid test calls.

## Review Focus
- Reserved characters in searches must remain query data, not become new query parameters.
- Missing credentials or generation confirmation must fail before any network call.
- Oversized or malformed upstream responses must become sanitized tool errors.
- Redirects must never forward upstream credentials.
- Packaged startup must emit only MCP JSON on stdout and shut down cleanly on EOF.

### Task 1: Bounded upstream client and reproducible build
**Files:** `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, `src/main/java/io/github/jagatlabs/mcp/client/JagatlabsClient.java`, `src/main/java/io/github/jagatlabs/mcp/config/JagatlabsProperties.java`, `src/main/java/io/github/jagatlabs/mcp/model/`, `src/test/java/io/github/jagatlabs/mcp/client/JagatlabsClientTest.java`.

**Interfaces:** `searchServices(String search, String category, int page, int limit)`, `getService(int id)`, `getModelInfo()`, `createPrediction(ImageRequest request)`, `getPrediction(String id)`. Return typed workflow DTOs. `JagatlabsException` carries a stable sanitized error code.

- [ ] Verify stable compatible dependencies against official docs and Maven metadata; pin versions.
- [ ] Create build configuration and failing client tests: exact paths/verbs, encoded query, public requests without authorization, authenticated requests with authorization, missing token causes zero requests, 401/403/404/429/5xx mapping, timeout, redirects, malformed JSON and response-size limits.
- [ ] Run `mvn test -Dtest=JagatlabsClientTest` and confirm failure before implementation.
- [ ] Implement configuration validation, DTOs, disabled redirects, finite timeouts and bounded body reads; exclude raw error bodies from exceptions.
- [ ] Run the client tests until all pass.

### Task 2: Validated MCP tools and local mock workflow
**Files:** `src/main/java/io/github/jagatlabs/mcp/JagatlabsMcpApplication.java`, `src/main/java/io/github/jagatlabs/mcp/tools/JagatlabsTools.java`, `src/main/java/io/github/jagatlabs/mcp/service/WorkflowService.java`, `src/main/resources/application.yml`, `src/main/resources/logback-spring.xml`, `src/test/java/io/github/jagatlabs/mcp/service/WorkflowServiceTest.java`, `demo/mock_backend.py`.

**Interfaces:** Expose `search_ai_services`, `get_ai_service`, `get_image_model_info`, `create_image_prediction`, `get_image_prediction`. Service wraps the Task 1 client, enforces input limits and confirmation, and records outcome/duration/correlation ID without prompt or response logging.

- [ ] Add failing tests for page/limit boundaries, blank and overlong prompts, unsafe prediction IDs, supported generation options, missing confirmation, and sanitized errors. Assert rejected inputs make zero upstream calls.
- [ ] Run `mvn test -Dtest=WorkflowServiceTest` and confirm expected failure.
- [ ] Implement service and tool registration, explicit side-effect descriptions, typed outputs, and stderr logging with Micrometer instrumentation.
- [ ] Implement a deterministic HTTP mock using Python standard library with catalog, model info, create, and completed-status routes; mock credentials are fixture values only.
- [ ] Run the unit suite and exercise mock routes locally.

### Task 3: Packaged protocol verification and portfolio delivery
**Files:** `src/test/java/io/github/jagatlabs/mcp/McpProtocolIT.java`, `Dockerfile`, `compose.yaml`, `.dockerignore`, `.gitignore`, `.env.example`, `.github/workflows/ci.yml`, `.github/workflows/dependency-review.yml`, `README.md`, `docs/architecture.md`, `SECURITY.md`.

**Interfaces:** `mvn verify` packages the application and launches a real child JVM for MCP initialization, tool discovery and invocation against a local HTTP fixture. README contains Java and Docker STDIO client commands plus explicit live environment configuration.

- [ ] Add protocol integration test that asserts five tools, valid schemas, catalog search, guarded creation, prediction completion, sanitized upstream failures, stdout JSON purity, and clean EOF shutdown.
- [ ] Run `mvn verify` and fix failures using observed output.
- [ ] Add non-root Docker image, mock compose service with health check, placeholder environment settings, build/test CI and dependency review restricted to supported pull requests.
- [ ] Write clone/build/run/client instructions, sample workflow, API endpoint mapping, security boundary and limitations, plus honest verification results.
- [ ] Run `mvn verify` again after delivery changes; run Docker checks if Docker is available and report if unavailable.
- [ ] Review final diff for secrets, stdout logging, unintended live defaults, and consistency with the approved spec.

## Self-review
Every spec section maps to the three tasks. Client tests own HTTP/security failure boundaries; service tests own validation and workflow side effects; packaged protocol tests own MCP compatibility and stdout. Dependency versions and fixture tooling remain implementation choices. No publication or deployment is included.
