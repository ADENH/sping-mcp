package io.github.jagatlabs.mcp.service;

import io.github.jagatlabs.mcp.client.JagatlabsClient;
import io.github.jagatlabs.mcp.client.JagatlabsException;
import io.github.jagatlabs.mcp.model.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class WorkflowService {
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowService.class);
    private final JagatlabsClient client;
    private final MeterRegistry metrics;

    public WorkflowService(JagatlabsClient client, MeterRegistry metrics) { this.client = client; this.metrics = metrics; }

    public CatalogPage search(String search, String category, Integer page, Integer limit) {
        return measured("search_ai_services", () -> {
            int p = page == null ? 1 : page;
            int size = limit == null ? 10 : limit;
            valid(p >= 1 && size >= 1 && size <= 100);
            valid(search == null || search.length() <= 500);
            valid(category == null || category.length() <= 100);
            return client.searchServices(search, category, p, size);
        });
    }

    public AiService details(int id) {
        return measured("get_ai_service", () -> { valid(id > 0); return client.getService(id); });
    }

    public ModelInfo modelInfo() { return measured("get_image_model_info", client::getModelInfo); }

    public Prediction create(String prompt, String outputFormat, Integer outputQuality, Integer seed, Boolean confirmGeneration) {
        return measured("create_image_prediction", () -> {
            if (!Boolean.TRUE.equals(confirmGeneration)) throw new JagatlabsException("GENERATION_CONFIRMATION_REQUIRED");
            valid(prompt != null && !prompt.isBlank() && prompt.length() <= 4000);
            String format = outputFormat == null ? "webp" : outputFormat;
            int quality = outputQuality == null ? 100 : outputQuality;
            int randomSeed = seed == null ? -1 : seed;
            valid(Set.of("png", "jpg", "webp").contains(format));
            valid(quality >= 1 && quality <= 100 && randomSeed >= -1);
            return client.createPrediction(new ImageRequest(prompt, format, quality, randomSeed));
        });
    }

    public Prediction status(String predictionId) {
        return measured("get_image_prediction", () -> {
            valid(predictionId != null && predictionId.matches("[A-Za-z0-9_-]{1,128}"));
            return client.getPrediction(predictionId);
        });
    }

    private <T> T measured(String tool, Supplier<T> action) {
        String correlationId = UUID.randomUUID().toString();
        String outcome = "success";
        Timer.Sample sample = Timer.start(metrics);
        long started = System.nanoTime();
        try { return action.get(); }
        catch (JagatlabsException e) { outcome = e.getMessage(); throw e; }
        catch (Exception e) { outcome = "INTERNAL_ERROR"; throw new JagatlabsException(outcome); }
        finally {
            metrics.counter("jagatlabs.tool.calls", "tool", tool, "outcome", outcome).increment();
            sample.stop(metrics.timer("jagatlabs.tool.duration", "tool", tool));
            LOG.info("tool={} outcome={} duration_ms={} correlation_id={}", tool, outcome,
                    (System.nanoTime() - started) / 1_000_000, correlationId);
        }
    }
    private static void valid(boolean condition) { if (!condition) throw new JagatlabsException("VALIDATION_ERROR"); }
}
