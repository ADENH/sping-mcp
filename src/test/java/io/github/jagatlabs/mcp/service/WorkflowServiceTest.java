package io.github.jagatlabs.mcp.service;

import io.github.jagatlabs.mcp.client.JagatlabsClient;
import io.github.jagatlabs.mcp.client.JagatlabsException;
import io.github.jagatlabs.mcp.model.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowServiceTest {
    JagatlabsClient client;
    WorkflowService service;
    SimpleMeterRegistry metrics;
    @BeforeEach void setup() {
        client = mock(JagatlabsClient.class);
        metrics = new SimpleMeterRegistry();
        service = new WorkflowService(client, metrics);
    }

    @Test void searchAppliesDefaults() {
        service.search(null, null, null, null);
        verify(client).searchServices(null, null, 1, 10);
    }
    @Test void acceptsPaginationBoundaries() {
        service.search("garden", "image", 1, 100);
        verify(client).searchServices("garden", "image", 1, 100);
    }
    @Test void rejectsPaginationAndSearchBeforeCallingUpstream() {
        assertThatThrownBy(() -> service.search(null, null, 0, 10)).hasMessage("VALIDATION_ERROR");
        assertThatThrownBy(() -> service.search(null, null, 1, 101)).hasMessage("VALIDATION_ERROR");
        assertThatThrownBy(() -> service.search("x".repeat(501), null, 1, 10)).hasMessage("VALIDATION_ERROR");
        assertThatThrownBy(() -> service.search(null, "x".repeat(101), 1, 10)).hasMessage("VALIDATION_ERROR");
        verifyNoInteractions(client);
    }
    @Test void rejectsInvalidServiceId() {
        assertThatThrownBy(() -> service.details(0)).hasMessage("VALIDATION_ERROR");
        verifyNoInteractions(client);
    }
    @ParameterizedTest @ValueSource(strings = {"", "../secret", "id/other", "id?x=y", "id#fragment", "id%2Fother", " space"})
    void rejectsUnsafePredictionId(String id) {
        assertThatThrownBy(() -> service.status(id)).hasMessage("VALIDATION_ERROR");
        verifyNoInteractions(client);
    }
    @Test void rejectsNullId() {
        assertThatThrownBy(() -> service.status(null)).hasMessage("VALIDATION_ERROR");
        verifyNoInteractions(client);
    }
    @Test void requiresAffirmativeConfirmation() {
        assertThatThrownBy(() -> service.create("garden", null, null, null, false)).hasMessage("GENERATION_CONFIRMATION_REQUIRED");
        assertThatThrownBy(() -> service.create("garden", null, null, null, null)).hasMessage("GENERATION_CONFIRMATION_REQUIRED");
        verifyNoInteractions(client);
    }
    @Test void validatesPromptAndGenerationOptions() {
        assertThatThrownBy(() -> service.create(" ", null, null, null, true)).hasMessage("VALIDATION_ERROR");
        assertThatThrownBy(() -> service.create(null, null, null, null, true)).hasMessage("VALIDATION_ERROR");
        assertThatThrownBy(() -> service.create("x".repeat(4001), null, null, null, true)).hasMessage("VALIDATION_ERROR");
        assertThatThrownBy(() -> service.create("garden", "gif", 90, -1, true)).hasMessage("VALIDATION_ERROR");
        assertThatThrownBy(() -> service.create("garden", "webp", 0, -1, true)).hasMessage("VALIDATION_ERROR");
        assertThatThrownBy(() -> service.create("garden", "webp", 101, -1, true)).hasMessage("VALIDATION_ERROR");
        assertThatThrownBy(() -> service.create("garden", "webp", 90, -2, true)).hasMessage("VALIDATION_ERROR");
        verifyNoInteractions(client);
    }
    @Test void createsWithDefaultsAndRecordsSafeMetrics() {
        when(client.createPrediction(any())).thenReturn(new Prediction("pred-1", "starting", null, List.of()));
        var result = service.create("garden", null, null, null, true);
        assertThat(result.id()).isEqualTo("pred-1");
        var argument = ArgumentCaptor.forClass(ImageRequest.class);
        verify(client).createPrediction(argument.capture());
        assertThat(argument.getValue()).isEqualTo(new ImageRequest("garden", "webp", 100, -1));
        assertThat(metrics.get("jagatlabs.tool.calls").tags("tool", "create_image_prediction", "outcome", "success").counter().count()).isEqualTo(1);
        assertThat(metrics.get("jagatlabs.tool.duration").timer().count()).isEqualTo(1);
    }
    @Test void delegatesReadTools() {
        service.details(7);
        service.modelInfo();
        service.status("pred-1");
        verify(client).getService(7);
        verify(client).getModelInfo();
        verify(client).getPrediction("pred-1");
    }
    @Test void propagatesSanitizedErrorsAndRecordsFailure() {
        when(client.getPrediction("pred-1")).thenThrow(new JagatlabsException("RATE_LIMITED"));
        assertThatThrownBy(() -> service.status("pred-1")).hasMessage("RATE_LIMITED");
        assertThat(metrics.get("jagatlabs.tool.calls").tags("tool", "get_image_prediction", "outcome", "RATE_LIMITED").counter().count()).isEqualTo(1);
    }
    @Test void sanitizesUnexpectedFailures() {
        when(client.getPrediction("pred-1")).thenThrow(new IllegalStateException("Bearer secret-token"));
        assertThatThrownBy(() -> service.status("pred-1")).hasMessage("INTERNAL_ERROR");
    }
}
