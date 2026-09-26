package io.kellermann.bigcontainers.exception;

import io.kellermann.bigcontainers.model.AssetCodeRejectionReason;
import java.util.Objects;

/**
 * A manually entered or scanned public asset code failed normalization/checksum validation
 * <strong>before</strong> any database lookup ran (ADR-0002 "Validation order"). Distinct from
 * {@link NotFoundException}, which reports a well-formed, checksum-valid code that simply does not
 * match any asset: the two failure modes need different corrective action from the user (retype
 * versus "this code does not exist here"), so they must never collapse into the same response.
 */
public class InvalidAssetCodeException extends ApplicationException {

    private static final String ERROR_CODE = "INVALID_ASSET_CODE";

    private final AssetCodeRejectionReason reason;

    public InvalidAssetCodeException(AssetCodeRejectionReason reason) {
        super(ERROR_CODE, "The asset code is not valid: " + Objects.requireNonNull(reason, "reason must not be null"));
        this.reason = reason;
    }

    public AssetCodeRejectionReason reason() {
        return reason;
    }
}
