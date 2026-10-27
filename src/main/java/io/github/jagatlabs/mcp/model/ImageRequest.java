package io.github.jagatlabs.mcp.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Deliberately small subset of the upstream ReplicateImageRequest contract. */
public record ImageRequest(String prompt, @JsonProperty("output_format") String outputFormat,
                           @JsonProperty("output_quality") int outputQuality, int seed) {
    @Override public String toString() { return "ImageRequest[prompt=REDACTED]"; }
}
