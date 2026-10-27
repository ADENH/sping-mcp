package io.github.jagatlabs.mcp.model;

import java.math.BigDecimal;
import java.util.List;

public record AiService(int id, String name, String creator, String description, String category,
                        List<String> tags, boolean active, boolean featured, BigDecimal price) {}
