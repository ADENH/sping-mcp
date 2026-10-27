package io.github.jagatlabs.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class McpProtocolIT {
    private final ObjectMapper mapper = new ObjectMapper();
    private final BlockingQueue<String> stdout = new LinkedBlockingQueue<>();
    private final AtomicInteger creations = new AtomicInteger();
    private final AtomicInteger requests = new AtomicInteger();
    private BufferedWriter stdin;
    private long sequence;

    @Test void packagedServerSupportsWorkflowAndKeepsStdoutPure() throws Exception {
        HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            requests.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            String body;
            int status = 200;
            if (path.equals("/api/v1/ai/")) {
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
                body = "{\"data\":[],\"meta\":{\"page\":1,\"limit\":10,\"total\":0,\"total_pages\":0,\"has_next\":false,\"has_prev\":false}}";
            } else if (path.equals("/api/v1/ai/1")) {
                body = "{\"data\":{\"id\":1,\"name\":\"Demo AI\",\"creator\":\"Demo\",\"is_active\":true,\"is_featured\":false}}";
            } else if (path.equals("/api/v1/replicate/model-info")) {
                body = "{\"model\":\"hidream-l1-fast\",\"description\":\"fixture model\"}";
            } else if (path.equals("/api/v1/replicate/create-prediction")) {
                assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer demo-token");
                assertThat(mapper.readTree(exchange.getRequestBody()).get("prompt").asText()).isEqualTo("a garden");
                creations.incrementAndGet();
                body = "{\"id\":\"demo-prediction-1\",\"status\":\"starting\"}";
            } else if (path.endsWith("/rate-limited")) {
                status = 429;
                body = "{\"detail\":\"Bearer upstream-secret\"}";
            } else if (path.endsWith("/demo-prediction-1")) {
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer demo-token");
                body = "{\"id\":\"demo-prediction-1\",\"status\":\"succeeded\",\"urls\":[\"https://example.com/demo.webp\"],\"logs\":\"provider-secret\"}";
            } else { status = 404; body = "{}"; }
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        backend.start();
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        ProcessBuilder builder = new ProcessBuilder(executable, "-jar", "target/jagatlabs-mcp-server.jar");
        builder.environment().put("JAGATLABS_MODE", "mock");
        builder.environment().put("JAGATLABS_BASE_URL", "http://127.0.0.1:" + backend.getAddress().getPort());
        builder.environment().put("JAGATLABS_TOKEN", "demo-token");
        builder.redirectError(Path.of("target", "mcp-test-stderr.log").toFile());
        Process process = builder.start();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) stdout.add(line);
            } catch (IOException e) { stdout.add("reader failure"); }
        });
        try {
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            JsonNode init = request("initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "portfolio-smoke-test", "version", "1.0")));
            assertThat(init.path("result").path("serverInfo").path("name").asText()).isEqualTo("jagatlabs-mcp-server");
            assertThat(init.path("result").path("protocolVersion").asText()).isEqualTo("2024-11-05");
            send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
            JsonNode tools = request("tools/list", Map.of()).path("result").path("tools");
            assertThat(tools).hasSize(5);
            boolean foundGeneration = false;
            for (JsonNode tool : tools) {
                assertThat(tool.path("inputSchema").path("type").asText()).isEqualTo("object");
                if (tool.get("name").asText().equals("create_image_prediction")) {
                    foundGeneration = true;
                    assertThat(tool.path("annotations").path("readOnlyHint").asBoolean()).isFalse();
                    assertThat(tool.path("annotations").path("idempotentHint").asBoolean()).isFalse();
                    assertThat(tool.path("inputSchema").path("required").toString()).contains("prompt", "confirmGeneration");
                }
            }
            assertThat(foundGeneration).isTrue();
            assertThat(call("search_ai_services", Map.of()).toString()).contains("total");
            assertThat(call("get_ai_service", Map.of("id", 1)).toString()).contains("Demo AI");
            assertThat(call("get_image_model_info", Map.of()).toString()).contains("hidream-l1-fast");

            JsonNode refused = call("create_image_prediction", Map.of("prompt", "a garden", "confirmGeneration", false));
            assertThat(refused.path("isError").asBoolean()).isTrue();
            assertThat(refused.toString()).contains("GENERATION_CONFIRMATION_REQUIRED");
            assertThat(creations.get()).isZero();
            int beforeInvalid = requests.get();
            JsonNode invalid = call("get_image_prediction", Map.of("predictionId", "../secret"));
            assertThat(invalid.path("isError").asBoolean()).isTrue();
            assertThat(requests.get()).isEqualTo(beforeInvalid);

            JsonNode created = call("create_image_prediction", Map.of("prompt", "a garden", "confirmGeneration", true));
            assertThat(created.path("isError").asBoolean()).isFalse();
            assertThat(created.toString()).contains("demo-prediction-1");
            assertThat(creations.get()).isEqualTo(1);
            JsonNode completed = call("get_image_prediction", Map.of("predictionId", "demo-prediction-1"));
            assertThat(completed.path("isError").asBoolean()).isFalse();
            assertThat(completed.toString()).contains("succeeded", "https://example.com/demo.webp").doesNotContain("provider-secret");
            JsonNode failure = call("get_image_prediction", Map.of("predictionId", "rate-limited"));
            assertThat(failure.path("isError").asBoolean()).isTrue();
            assertThat(failure.toString()).contains("RATE_LIMITED").doesNotContain("upstream-secret");
            stdin.close();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("server exits on STDIN EOF").isTrue();
            assertThat(process.exitValue()).isZero();
            reader.join(2000);
            for (String extra : stdout) assertThat(mapper.readTree(extra).path("jsonrpc").asText()).isEqualTo("2.0");
            String logs = java.nio.file.Files.readString(Path.of("target/mcp-test-stderr.log"));
            assertThat(logs).contains("correlation_id=", "create_image_prediction").doesNotContain("a garden", "demo-token", "upstream-secret", "provider-secret");
        } finally {
            process.destroyForcibly();
            backend.stop(0);
        }
    }

    private JsonNode call(String name, Map<String, ?> arguments) throws Exception {
        JsonNode response = request("tools/call", Map.of("name", name, "arguments", arguments));
        assertThat(response.has("error")).as(response.toString()).isFalse();
        return response.path("result");
    }
    private JsonNode request(String method, Map<String, ?> params) throws Exception {
        long id = ++sequence;
        send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            String line = stdout.poll(2, TimeUnit.SECONDS);
            if (line == null) continue;
            JsonNode result = mapper.readTree(line);
            assertThat(result.path("jsonrpc").asText()).as("protocol stdout: " + line).isEqualTo("2.0");
            if (result.path("id").asLong(-1) == id) return result;
        }
        throw new AssertionError("Timed out waiting for " + method + "; see target/mcp-test-stderr.log");
    }
    private void send(Map<String, ?> message) throws IOException {
        stdin.write(mapper.writeValueAsString(message)); stdin.newLine(); stdin.flush();
    }
}
