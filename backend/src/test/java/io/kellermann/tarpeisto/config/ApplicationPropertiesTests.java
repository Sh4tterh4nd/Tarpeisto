package io.kellermann.tarpeisto.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test of the JSR-380 constraints on {@link ApplicationProperties}: no Spring context.
 */
class ApplicationPropertiesTests {

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
    void aBlankDisplayNameFailsValidation() {
        ApplicationProperties properties = new ApplicationProperties(" ", null);

        Set<ConstraintViolation<ApplicationProperties>> violations = validator.validate(properties);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void aValidDisplayNameWithNoPublicBaseUrlPassesValidation() {
        ApplicationProperties properties = new ApplicationProperties("Tarpeisto", null);

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void anAbsolutePublicBaseUrlPassesValidation() {
        ApplicationProperties properties =
                new ApplicationProperties("Tarpeisto", URI.create("https://inventory.example.org"));

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void aRelativePublicBaseUrlFailsValidation() {
        ApplicationProperties properties = new ApplicationProperties("Tarpeisto", URI.create("/relative"));

        assertThat(validator.validate(properties)).isNotEmpty();
    }
}
