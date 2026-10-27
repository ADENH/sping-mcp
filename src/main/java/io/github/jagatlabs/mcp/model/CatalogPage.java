package io.github.jagatlabs.mcp.model;

import java.util.List;

public record CatalogPage(List<AiService> data, Pagination meta) {
    public record Pagination(int page, int limit, long total, int totalPages, boolean hasNext, boolean hasPrev) {}
}
