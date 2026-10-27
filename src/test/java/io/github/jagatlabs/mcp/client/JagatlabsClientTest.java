package io.github.jagatlabs.mcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.jagatlabs.mcp.config.JagatlabsProperties;
import io.github.jagatlabs.mcp.model.ImageRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class JagatlabsClientTest {
    HttpServer server;
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<String> path = new AtomicReference<>();
    final AtomicReference<String> authorization = new AtomicReference<>();
    final AtomicReference<String> method = new AtomicReference<>();
    final AtomicReference<String> body = new AtomicReference<>();
    volatile int status = 200;
    volatile String response = "{}";
    volatile long delay;
    volatile long bodyDelay;
    volatile boolean chunked;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            path.set(exchange.getRequestURI().toASCIIString());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            method.set(exchange.getRequestMethod());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try { Thread.sleep(delay); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Location", "/credential-leak");
            try {
                exchange.sendResponseHeaders(status, chunked ? 0 : bytes.length);
                if (bodyDelay > 0) {
                    exchange.getResponseBody().write(bytes, 0, 1);
                    exchange.getResponseBody().flush();
                    try { Thread.sleep(bodyDelay); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
                } else exchange.getResponseBody().write(bytes);
            }
            finally { exchange.close(); }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }

    JagatlabsClient client(String token, int maxBytes, Duration timeout) {
        return new JagatlabsClient(new JagatlabsProperties("mock", URI.create("http://127.0.0.1:" + server.getAddress().getPort()), token,
                Duration.ofSeconds(1), timeout, maxBytes), new ObjectMapper());
    }
    JagatlabsClient client() { return client("fixture-token", 65536, Duration.ofSeconds(2)); }

    @Test void encodesSearchAndKeepsPublicCallsAnonymous() {
        response = "{\"data\":[],\"meta\":{\"page\":1,\"limit\":10,\"total\":0,\"total_pages\":0,\"has_next\":false,\"has_prev\":false}}";
        var result = client().searchServices("art &is_active=false/猫", "image", 1, 10);
        assertThat(result.data()).isEmpty();
        assertThat(path.get()).contains("search=art%20%26is_active%3Dfalse%2F%E7%8C%AB", "is_active=true", "category=image");
        assertThat(authorization.get()).isNull();
        assertThat(method.get()).isEqualTo("GET");
    }

    @Test void mapsServiceEnvelopeAndIgnoresUnneededFields() {
        response = "{\"data\":{\"id\":7,\"name\":\"Image AI\",\"creator\":\"Jagatlabs\",\"is_active\":true,\"is_featured\":false,\"private_debug\":\"hidden\"}}";
        var result = client().getService(7);
        assertThat(result.id()).isEqualTo(7);
        assertThat(result.name()).isEqualTo("Image AI");
        assertThat(path.get()).isEqualTo("/api/v1/ai/7");
    }

    @Test void createsOnceWithBearerAndContractFieldNames() throws Exception {
        response = "{\"id\":\"pred-1\",\"status\":\"starting\",\"logs\":\"secret logs\"}";
        var result = client().createPrediction(new ImageRequest("a garden", "webp", 90, -1));
        assertThat(result.id()).isEqualTo("pred-1");
        assertThat(path.get()).isEqualTo("/api/v1/replicate/create-prediction");
        assertThat(method.get()).isEqualTo("POST");
        assertThat(authorization.get()).isEqualTo("Bearer fixture-token");
        assertThat(new ObjectMapper().readTree(body.get()).get("output_quality").asInt()).isEqualTo(90);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void getsStatusWithoutExposingProviderLogsOrErrors() {
        response = "{\"id\":\"pred-1\",\"status\":\"failed\",\"error\":\"Bearer secret\",\"logs\":\"sensitive\",\"progress\":0.5}";
        var result = client().getPrediction("pred-1");
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.toString()).doesNotContain("secret", "sensitive");
        assertThat(path.get()).isEqualTo("/api/v1/replicate/status/pred-1");
    }

    @Test void missingTokenDoesNotCallUpstream() {
        assertThatThrownBy(() -> client("", 1024, Duration.ofSeconds(1)).getPrediction("pred-1"))
                .isInstanceOf(JagatlabsException.class).hasMessage("AUTHENTICATION_REQUIRED");
        assertThat(calls.get()).isZero();
    }

    @ParameterizedTest @CsvSource({"401,AUTHENTICATION_REQUIRED", "403,ACCESS_DENIED", "404,NOT_FOUND", "429,RATE_LIMITED", "500,UPSTREAM_UNAVAILABLE", "302,UPSTREAM_UNAVAILABLE", "422,UPSTREAM_REJECTED"})
    void mapsErrorsWithoutLeakingBodyOrRetrying(int code, String error) {
        status = code; response = "secret provider payload";
        assertThatThrownBy(() -> client().createPrediction(new ImageRequest("garden", "webp", 90, -1)))
                .isInstanceOf(JagatlabsException.class).hasMessage(error);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void boundsFullResponseDeadline() {
        delay = 700;
        assertThatThrownBy(() -> client("fixture-token", 1024, Duration.ofMillis(100)).getPrediction("pred-1"))
                .hasMessage("UPSTREAM_TIMEOUT");
    }
    @Test void rejectsOversizedResponse() {
        response = "x".repeat(2048);
        assertThatThrownBy(() -> client("fixture-token", 1024, Duration.ofSeconds(2)).getPrediction("pred-1"))
                .hasMessage("INVALID_UPSTREAM_RESPONSE");
    }
    @Test void boundsDeadlineEvenAfterHeadersArrive() {
        response = "{\"id\":\"pred-1\",\"status\":\"starting\"}";
        bodyDelay = 700;
        assertThatThrownBy(() -> client("fixture-token", 1024, Duration.ofMillis(150)).getPrediction("pred-1"))
                .hasMessage("UPSTREAM_TIMEOUT");
    }
    @Test void rejectsChunkedOversizedResponseWithoutContentLength() {
        chunked = true; response = "x".repeat(4096);
        assertThatThrownBy(() -> client("fixture-token", 1024, Duration.ofSeconds(2)).getPrediction("pred-1"))
                .hasMessage("INVALID_UPSTREAM_RESPONSE");
    }
    @Test void mapsAllowlistedModelInfoFields() {
        response = "{\"model\":\"hidream-l1-fast\",\"description\":\"image model\",\"private_key\":\"secret\"}";
        var model = client().getModelInfo();
        assertThat(model.model()).isEqualTo("hidream-l1-fast");
        assertThat(model.toString()).doesNotContain("private_key", "secret");
        assertThat(authorization.get()).isNull();
    }
    @Test void reportsConnectionFailureWithoutExposingHostOrToken() {
        server.stop(0);
        assertThatThrownBy(() -> client().getPrediction("pred-1")).hasMessage("UPSTREAM_UNAVAILABLE");
    }
    @Test void rejectsMalformedJson() {
        response = "not json";
        assertThatThrownBy(() -> client().getPrediction("pred-1")).hasMessage("INVALID_UPSTREAM_RESPONSE");
    }
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {" THIS IS NOT JSON", "{\"error\":\"failure\"}"})
    void rejectsTrailingGarbageAndConcatenatedJson(String suffix) {
        response = "{\"id\":\"pred-1\",\"status\":\"succeeded\"}" + suffix;
        assertThatThrownBy(() -> client().getPrediction("pred-1")).hasMessage("INVALID_UPSTREAM_RESPONSE");
    }
    @Test void rejectsStructurallyInvalidSuccess() {
        response = "{}";
        assertThatThrownBy(() -> client().getPrediction("pred-1")).hasMessage("INVALID_UPSTREAM_RESPONSE");
    }
    @Test void rejectsUnsafeConfiguration() {
        assertThatThrownBy(() -> new JagatlabsProperties("live", URI.create("http://example.com"), "token", Duration.ofSeconds(1), Duration.ofSeconds(1), 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JagatlabsProperties("mock", URI.create("http://example.com"), "token", Duration.ofSeconds(1), Duration.ofSeconds(1), 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JagatlabsProperties("live", URI.create("https://user:password@example.com/?secret=x"), "token", Duration.ofSeconds(1), Duration.ofSeconds(1), 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
