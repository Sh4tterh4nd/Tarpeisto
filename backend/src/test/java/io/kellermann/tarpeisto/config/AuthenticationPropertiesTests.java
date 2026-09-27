package io.kellermann.tarpeisto.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test of {@link AuthenticationProperties}'s mode-fail-fast validation: no Spring
 * context. Proves that selecting {@code LOCAL_AND_OIDC} without a usable provider configuration is
 * rejected by JSR-380 validation - which {@code ConfigurationPropertiesBindingPostProcessor}
 * applies at application startup - rather than starting a half-configured application
 * (implementation plan section 3.2/3.4).
 */
class AuthenticationPropertiesTests {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    private static final AuthenticationProperties.OidcProvider USABLE_OIDC = new AuthenticationProperties.OidcProvider(
            "Example IdP", URI.create("https://idp.example.org"), "client-id", "client-secret", List.of("openid"));

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
    void localOnlyModeNeedsNoOidcConfiguration() {
        AuthenticationProperties properties = new AuthenticationProperties(AuthenticationMode.LOCAL_ONLY, null, false);

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void localAndOidcModeWithFullyUsableConfigurationPassesValidation() {
        AuthenticationProperties properties =
                new AuthenticationProperties(AuthenticationMode.LOCAL_AND_OIDC, USABLE_OIDC, false);

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void localAndOidcModeWithNoOidcBlockAtAllFailsValidation() {
        AuthenticationProperties properties =
                new AuthenticationProperties(AuthenticationMode.LOCAL_AND_OIDC, null, false);

        Set<ConstraintViolation<AuthenticationProperties>> violations = validator.validate(properties);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void localAndOidcModeWithBlankDisplayNameFailsValidation() {
        AuthenticationProperties properties = new AuthenticationProperties(
                AuthenticationMode.LOCAL_AND_OIDC,
                new AuthenticationProperties.OidcProvider(
                        " ",
                        USABLE_OIDC.issuerUri(),
                        USABLE_OIDC.clientId(),
                        USABLE_OIDC.clientSecret(),
                        USABLE_OIDC.scopes()),
                false);

        assertThat(validator.validate(properties)).isNotEmpty();
    }

    @Test
    void localAndOidcModeWithMissingIssuerUriFailsValidation() {
        AuthenticationProperties properties = new AuthenticationProperties(
                AuthenticationMode.LOCAL_AND_OIDC,
                new AuthenticationProperties.OidcProvider(
                        USABLE_OIDC.displayName(),
                        null,
                        USABLE_OIDC.clientId(),
                        USABLE_OIDC.clientSecret(),
                        USABLE_OIDC.scopes()),
                false);

        assertThat(validator.validate(properties)).isNotEmpty();
    }

    @Test
    void localAndOidcModeWithBlankClientSecretFailsValidation() {
        AuthenticationProperties properties = new AuthenticationProperties(
                AuthenticationMode.LOCAL_AND_OIDC,
                new AuthenticationProperties.OidcProvider(
                        USABLE_OIDC.displayName(),
                        USABLE_OIDC.issuerUri(),
                        USABLE_OIDC.clientId(),
                        "",
                        USABLE_OIDC.scopes()),
                false);

        assertThat(validator.validate(properties)).isNotEmpty();
    }

    @Test
    void localAndOidcModeWithNoScopesFailsValidation() {
        AuthenticationProperties properties = new AuthenticationProperties(
                AuthenticationMode.LOCAL_AND_OIDC,
                new AuthenticationProperties.OidcProvider(
                        USABLE_OIDC.displayName(),
                        USABLE_OIDC.issuerUri(),
                        USABLE_OIDC.clientId(),
                        USABLE_OIDC.clientSecret(),
                        List.of()),
                false);

        assertThat(validator.validate(properties)).isNotEmpty();
    }
}
