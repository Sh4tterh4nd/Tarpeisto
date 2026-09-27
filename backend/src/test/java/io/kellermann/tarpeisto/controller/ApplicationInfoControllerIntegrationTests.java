package io.kellermann.tarpeisto.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class ApplicationInfoControllerIntegrationTests extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void applicationInfoIsReachableAnonymouslyAndReturnsJson() {
        ResponseEntity<ApplicationInfoResponse> response =
                restTemplate.getForEntity("/api/v1/application", ApplicationInfoResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        MediaType contentType = response.getHeaders().getContentType();
        assertThat(contentType).isNotNull();
        assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().applicationName()).isNotBlank();
        assertThat(response.getBody().oidcConfigured()).isFalse();
    }

    @Test
    void unknownApiPathReturnsAProblemDetailDocumentRatherThanAnHtmlErrorPage() {
        // Phase 0 has no authentication yet, so /api/v1/** paths other than the explicit
        // allowlist (see SecurityConfiguration) are denied before Spring MVC's own
        // "no handler found" resolution ever runs. Either layer must answer with an RFC 9457
        // problem document, never an HTML error page - which is what this test asserts.
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/does-not-exist", String.class);

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        MediaType contentType = response.getHeaders().getContentType();
        assertThat(contentType).isNotNull();
        // isCompatibleWith ignores the charset parameter (the actual response is
        // "application/problem+json;charset=UTF-8"), which is still the RFC 9457 media type.
        assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).doesNotContain("<html");
        assertThat(response.getBody()).contains("\"status\"");
    }
}
