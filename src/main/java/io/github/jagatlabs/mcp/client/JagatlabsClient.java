package io.github.jagatlabs.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jagatlabs.mcp.config.JagatlabsProperties;
import io.github.jagatlabs.mcp.model.*;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@Component
public class JagatlabsClient {
    private final JagatlabsProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public JagatlabsClient(JagatlabsProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public CatalogPage searchServices(String search, String category, int page, int limit) {
        String query = "?page=" + page + "&limit=" + limit + "&is_active=true";
        if (search != null) query += "&search=" + encode(search);
        if (category != null) query += "&category=" + encode(category);
        JsonNode root = request("GET", "/api/v1/ai/" + query, null, false);
        JsonNode data = root.path("data");
        require(data.isArray());
        List<AiService> items = new ArrayList<>();
        data.forEach(item -> items.add(service(item)));
        JsonNode meta = root.path("meta");
        require(meta.isObject() && meta.path("page").isIntegralNumber() && meta.path("limit").isIntegralNumber()
                && meta.path("total").isIntegralNumber() && meta.path("total_pages").isIntegralNumber()
                && meta.path("has_next").isBoolean() && meta.path("has_prev").isBoolean());
        return new CatalogPage(List.copyOf(items), new CatalogPage.Pagination(meta.get("page").asInt(),
                meta.get("limit").asInt(), meta.get("total").asLong(), meta.get("total_pages").asInt(),
                meta.get("has_next").asBoolean(), meta.get("has_prev").asBoolean()));
    }

    public AiService getService(int id) { return service(request("GET", "/api/v1/ai/" + id, null, false).path("data")); }

    public ModelInfo getModelInfo() {
        JsonNode root = request("GET", "/api/v1/replicate/model-info", null, false);
        String model = text(root, "model");
        if (model == null) model = text(root, "model_name");
        if (model == null) model = text(root, "name");
        // This endpoint has an open object schema. Absent optional fields remain null.
        return new ModelInfo(model, text(root, "description"));
    }

    public Prediction createPrediction(ImageRequest input) {
        return prediction(request("POST", "/api/v1/replicate/create-prediction", input, true));
    }

    public Prediction getPrediction(String id) {
        return prediction(request("GET", "/api/v1/replicate/status/" + encode(id), null, true));
    }

    private JsonNode request(String method, String path, Object body, boolean authenticated) {
        if (authenticated && properties.token().isBlank()) throw new JagatlabsException("AUTHENTICATION_REQUIRED");
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            URI uri = properties.baseUrl().resolve(path);
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(properties.requestTimeout())
                    .header("Accept", "application/json");
            if (authenticated) request.header("Authorization", "Bearer " + properties.token());
            if (body != null) request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)));
            else request.method(method, HttpRequest.BodyPublishers.noBody());
            pending = http.sendAsync(request.build(), info -> new LimitedBodySubscriber(properties.maxResponseBytes()));
            HttpResponse<byte[]> response = pending.get(properties.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
            int status = response.statusCode();
            if (status < 200 || status >= 300) throw new JagatlabsException(switch (status) {
                case 401 -> "AUTHENTICATION_REQUIRED";
                case 403 -> "ACCESS_DENIED";
                case 404 -> "NOT_FOUND";
                case 429 -> "RATE_LIMITED";
                default -> status >= 500 || status >= 300 && status < 400 ? "UPSTREAM_UNAVAILABLE" : "UPSTREAM_REJECTED";
            });
            JsonNode result;
            try { result = mapper.readTree(response.body()); }
            catch (Exception e) { throw new JagatlabsException("INVALID_UPSTREAM_RESPONSE"); }
            require(result != null && result.isObject());
            require(!result.path("ok").isBoolean() || result.path("ok").asBoolean());
            return result;
        } catch (JagatlabsException e) { throw e;
        } catch (TimeoutException e) { throw new JagatlabsException("UPSTREAM_TIMEOUT");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JagatlabsException("UPSTREAM_UNAVAILABLE");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof JagatlabsException safe) throw safe;
            if (cause instanceof HttpTimeoutException) throw new JagatlabsException("UPSTREAM_TIMEOUT");
            throw new JagatlabsException("UPSTREAM_UNAVAILABLE");
        } catch (Exception e) { throw new JagatlabsException("UPSTREAM_UNAVAILABLE");
        } finally { if (pending != null && !pending.isDone()) pending.cancel(true); }
    }

    private static AiService service(JsonNode node) {
        require(node.isObject() && node.path("id").isIntegralNumber() && node.path("name").isTextual()
                && node.path("creator").isTextual() && node.path("is_active").isBoolean() && node.path("is_featured").isBoolean());
        List<String> tags = strings(node.path("tags"));
        JsonNode price = node.path("price");
        require(price.isMissingNode() || price.isNull() || price.isNumber());
        return new AiService(node.get("id").asInt(), node.get("name").asText(), node.get("creator").asText(),
                text(node, "description"), text(node, "category"), tags, node.get("is_active").asBoolean(),
                node.get("is_featured").asBoolean(), price.isNumber() ? price.decimalValue() : null);
    }

    private static Prediction prediction(JsonNode node) {
        require(node.path("id").isTextual() && !node.get("id").asText().isBlank()
                && node.path("status").isTextual() && !node.get("status").asText().isBlank());
        JsonNode progress = node.path("progress");
        require(progress.isMissingNode() || progress.isNull() || progress.isNumber());
        return new Prediction(node.get("id").asText(), node.get("status").asText(),
                progress.isNumber() ? progress.asDouble() : null, strings(node.path("urls")));
    }
    private static List<String> strings(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return List.of();
        require(node.isArray());
        List<String> values = new ArrayList<>();
        node.forEach(value -> { require(value.isTextual()); values.add(value.asText()); });
        return List.copyOf(values);
    }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        require(value.isMissingNode() || value.isNull() || value.isTextual());
        return value.isTextual() ? value.asText() : null;
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static void require(boolean valid) { if (!valid) throw new JagatlabsException("INVALID_UPSTREAM_RESPONSE"); }
}
