package io.kellermann.tarpeisto.service;

import java.util.List;

/** Cursor page for the physical-assets inventory view. */
public record AssetSearchPageView(List<AssetSearchView> items, String nextCursor) {}
