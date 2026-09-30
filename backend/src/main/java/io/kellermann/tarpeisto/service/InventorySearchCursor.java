package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.exception.ValidationFailedException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/** A search continuation binds its actual semantic anchor to one normalized query. */
record InventorySearchCursor(UUID id, String anchor) {
    static InventorySearchCursor parse(String cursor, String filters) {
        if (cursor == null) return null;
        try {
            if (cursor.length() > 8192) throw new IllegalArgumentException();
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\n", 3);
            if (parts.length != 3 || !parts[0].equals(encoded(filters))) throw new IllegalArgumentException();
            return new InventorySearchCursor(UUID.fromString(parts[1]), parts[2]);
        } catch (IllegalArgumentException e) {
            throw new ValidationFailedException("cursor is invalid for these filters.");
        }
    }

    static String encode(UUID id, String anchor, String filters) {
        return encoded(encoded(filters) + "\n" + id + "\n" + anchor);
    }

    static String filters(Object... values) {
        StringBuilder result = new StringBuilder();
        for (Object value : values) {
            if (value == null) result.append("-1:");
            else {
                String text = value.toString();
                result.append(text.length()).append(':').append(text);
            }
        }
        return result.toString();
    }

    private static String encoded(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
