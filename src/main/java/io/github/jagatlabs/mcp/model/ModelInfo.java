package io.github.jagatlabs.mcp.model;

/** Only allowlisted fields from the backend's loosely specified model-info object. */
public record ModelInfo(String model, String description) {}
