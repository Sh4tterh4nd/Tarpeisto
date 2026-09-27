package io.kellermann.tarpeisto.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Proves SecurityConfiguration's deny-by-default posture for the API surface. */
class SecurityIntegrationTests extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void unAllowlistedApiPathIsDeniedByDefault() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/organizations", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // Compatibility rather than equality: the response carries an explicit charset
        // (application/problem+json;charset=UTF-8), which is still the RFC 9457 media type.
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .matches(contentType -> contentType.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(response.getBody()).contains("\"errorCode\":\"AUTHENTICATION_REQUIRED\"");
    }

    @Test
    void allowlistedApplicationInfoEndpointRemainsReachableWithoutAuthentication() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/application", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void allowlistedInitialSetupStatusRemainsReachableWithoutAuthentication() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/setup", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"setupRequired\":");
    }

    @Test
    void allowlistedHealthEndpointRemainsReachableWithoutAuthentication() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
