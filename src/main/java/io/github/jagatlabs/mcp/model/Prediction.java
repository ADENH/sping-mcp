package io.github.jagatlabs.mcp.model;

import java.util.List;

/** Provider logs and raw provider errors are intentionally excluded. */
public record Prediction(String id, String status, Double progress, List<String> urls) {}
