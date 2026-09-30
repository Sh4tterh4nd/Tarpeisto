package io.kellermann.tarpeisto.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.controller.matrix.AuthorizationMatrixProbe;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Inventories actual application-owned MVC handlers, independent of annotations and OpenAPI. */
class SecurityAuthorizationMatrixIntegrationTests extends AbstractIntegrationTest {
    private static final Pattern ALTERNATIVES = Pattern.compile("\\{[^}:]+:([A-Za-z]+(?:\\|[A-Za-z]+)+)\\}");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Autowired
    @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping handlers;

    @Test
    void everyApplicationMappingHasExactlyOneReviewedAuthorizationRow() throws Exception {
        assertReviewed(matrix());
    }

    @Test
    void anUnannotatedHandlerInAnApplicationSubpackageCannotEscapeTheReviewGate() throws Exception {
        var mapping = RequestMappingInfo.paths("/api/v1/security-review-probe")
                .methods(RequestMethod.OPTIONS)
                .options(handlers.getBuilderConfiguration())
                .build();
        var probe = new AuthorizationMatrixProbe();
        handlers.registerMapping(mapping, probe, AuthorizationMatrixProbe.class.getMethod("probe"));
        try {
            assertThatThrownBy(() -> assertReviewed(matrix()))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("security-review-probe");
            var reviewed = new ArrayList<>(matrix());
            reviewed.add(
                    "| OPTIONS | /api/v1/security-review-probe | AuthorizationMatrixProbe#probe -> test-only handler | Y | Y | Y | Y | N | N | test only | safe | none |");
            assertReviewed(reviewed);
        } finally {
            handlers.unregisterMapping(mapping);
        }
        assertReviewed(matrix());
    }

    @Test
    void duplicateAndStaleReviewedRowsFailTheGate() throws Exception {
        var duplicate = new ArrayList<>(matrix());
        String row = duplicate.stream()
                .filter(line -> line.startsWith("| GET | /api/v1/application | "))
                .findFirst()
                .orElseThrow();
        duplicate.add(row);
        assertThatThrownBy(() -> assertReviewed(duplicate))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("duplicate");
        var stale = new ArrayList<>(matrix());
        stale.add(row.replace("/api/v1/application", "/api/v1/obsolete-route"));
        assertThatThrownBy(() -> assertReviewed(stale))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("obsolete-route");
    }

    @Test
    void wideningRegexConstraintsProducesAnAdditionalReviewRequirement() {
        assertThat(expandLiteralAlternatives("/templates/{id}/{action:archive|restore}"))
                .containsExactly("/templates/{id}/archive", "/templates/{id}/restore");
        assertThat(expandLiteralAlternatives("/templates/{id}/{action:archive|restore|delete}"))
                .contains("/templates/{id}/delete");
        assertThat(expandLiteralAlternatives("/templates/{id}/{action:.*}"))
                .containsExactly("/templates/{id}/{action:.*}");
    }

    private List<String> matrix() throws Exception {
        return Files.readAllLines(Path.of("../docs/SECURITY_AUTHORIZATION_MATRIX.md"));
    }

    private void assertReviewed(List<String> matrix) {
        Set<String> documented = new HashSet<>();
        Set<String> actual = new HashSet<>();
        for (String line : matrix) {
            if (!line.matches("^\\| [A-Z]+ \\|.*")) continue;
            String[] columns = line.split("\\|", -1);
            assertThat(columns).as("complete matrix row: %s", line).hasSize(14);
            String method = columns[1].strip();
            assertThatCodeMethod(method);
            String route = method + " " + columns[2].strip();
            assertThat(documented.add(route))
                    .as("duplicate reviewed route: %s", route)
                    .isTrue();
            assertThat(columns[3].strip()).contains("#", " -> ");
            for (int i = 4; i <= 9; i++) assertThat(columns[i].strip()).isIn("Y", "N", "S", "C");
            assertThat(columns[11].strip()).isEqualTo(SAFE_METHODS.contains(method) ? "safe" : "required");
            assertThat(columns[12].strip()).isIn("none", "login", "lookup", "redemption");
        }
        handlers.getHandlerMethods().forEach((mapping, handler) -> {
            if (!handler.getBeanType().getName().startsWith("io.kellermann.tarpeisto.")) return;
            assertThat(mapping.getMethodsCondition().getMethods())
                    .as("explicit application method: %s", mapping)
                    .isNotEmpty();
            mapping.getMethodsCondition()
                    .getMethods()
                    .forEach(method -> mapping.getPatternValues().forEach(path -> {
                        for (String concrete : expandLiteralAlternatives(path)) {
                            String route = method.name() + " " + concrete;
                            assertThat(actual.add(route))
                                    .as("duplicate MVC route: %s", route)
                                    .isTrue();
                            String delegation = handler.getBeanType().getSimpleName() + "#"
                                    + handler.getMethod().getName();
                            assertThat(matrix)
                                    .as("reviewed controller delegation: %s", route)
                                    .anySatisfy(line -> assertThat(line)
                                            .startsWith("| " + method.name() + " | " + concrete + " | " + delegation
                                                    + " -> "));
                        }
                    }));
        });
        assertThat(documented)
                .as("missing or stale reviewed application routes")
                .containsExactlyInAnyOrderElementsOf(actual);
    }

    private void assertThatCodeMethod(String method) {
        assertThat(RequestMethod.valueOf(method).name()).isEqualTo(method);
    }

    private static List<String> expandLiteralAlternatives(String path) {
        var matcher = ALTERNATIVES.matcher(path);
        if (!matcher.find()) return List.of(path);
        List<String> expanded = new ArrayList<>();
        for (String alternative : matcher.group(1).split("\\|")) {
            expanded.addAll(expandLiteralAlternatives(
                    path.substring(0, matcher.start()) + alternative + path.substring(matcher.end())));
        }
        return expanded;
    }
}
