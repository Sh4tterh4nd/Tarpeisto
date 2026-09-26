package io.kellermann.bigcontainers.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Pure unit test of the JSR-380 constraints on {@link SeedProperties}: no Spring context. */
class SeedPropertiesTests {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void aBlankDefaultOrganizationNameFailsValidation() {
        Set<ConstraintViolation<SeedProperties>> violations =
                validator.validate(new SeedProperties(" ", null, null, null, null));

        assertThat(violations).isNotEmpty();
    }

    @Test
    void aNonBlankDefaultOrganizationNamePassesValidation() {
        assertThat(validator.validate(new SeedProperties("Default Organization", null, null, null, null)))
                .isEmpty();
    }

    @Test
    void blankOptionalOwnerBootstrapFieldsPassValidation() {
        assertThat(validator.validate(new SeedProperties("Default Organization", "", "", "", "")))
                .isEmpty();
    }
}
