package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.model.ModelCustomFieldOption;
import java.util.UUID;

/** Read projection of a {@link ModelCustomFieldOption} for the catalog API. */
public record ModelCustomFieldOptionView(
        UUID id, UUID modelCustomFieldId, String value, int displayOrder, boolean archived) {

    public static ModelCustomFieldOptionView from(ModelCustomFieldOption option) {
        return new ModelCustomFieldOptionView(
                option.getId(),
                option.getModelCustomFieldId(),
                option.getValue(),
                option.getDisplayOrder(),
                option.isArchived());
    }
}
