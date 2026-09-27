package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.service.AssetSearchPageView;
import java.util.List;

/** Cursor page returned by {@code GET /api/v1/assets}. */
public record AssetSearchPageResponse(List<AssetSearchResponse> items, String nextCursor) {
    public static AssetSearchPageResponse from(AssetSearchPageView page) {
        return new AssetSearchPageResponse(
                page.items().stream().map(AssetSearchResponse::from).toList(), page.nextCursor());
    }
}
