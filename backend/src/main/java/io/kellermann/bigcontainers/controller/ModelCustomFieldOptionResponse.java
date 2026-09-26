package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.service.ModelCustomFieldOptionView;
import java.util.UUID;

/** Response element for the dropdown-option endpoints. */
public record ModelCustomFieldOptionResponse(
        UUID id, UUID modelCustomFieldId, String value, int displayOrder, boolean archived) {

    public static ModelCustomFieldOptionResponse from(ModelCustomFieldOptionView view) {
        return new ModelCustomFieldOptionResponse(
                view.id(), view.modelCustomFieldId(), view.value(), view.displayOrder(), view.archived());
    }
}
