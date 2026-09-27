package io.kellermann.tarpeisto.controller;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the compiled single-page application and forwards client-side (React Router) routes to
 * {@code index.html} so a deep link such as {@code /assets/AB12CD} works on a full page load.
 *
 * <p>{@code /api/**} and {@code /actuator/**} are resolved by their own, higher-precedence
 * handler mappings and never reach this configuration for paths that are actually mapped; for
 * paths under those prefixes that are <em>not</em> mapped anywhere, the resolver below
 * deliberately declines to serve {@code index.html}, so the request falls through to Spring's
 * ordinary "no resource found" handling and an RFC 9457 problem document (via {@code
 * spring.mvc.problemdetails.enabled=true}) rather than an HTML page.
 *
 * <p>The frontend does not exist yet as of Phase 0 (see {@code -PskipFrontend=true} in the Gradle
 * build); {@code static/index.html} is simply absent until it is built, so this fallback quietly
 * resolves to nothing and requests get an ordinary 404 in the meantime.
 */
@Configuration(proxyBeanMethods = false)
public class SpaForwardingConfiguration implements WebMvcConfigurer {

    private static final String STATIC_LOCATION = "classpath:/static/";
    private static final String INDEX_HTML_CLASSPATH_LOCATION = "static/index.html";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(STATIC_LOCATION)
                .resourceChain(true)
                .addResolver(new SpaFallbackResourceResolver());
    }

    private static final class SpaFallbackResourceResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = location.createRelative(resourcePath);
            if (requested.exists() && requested.isReadable()) {
                return requested;
            }
            if (isReservedBackendPath(resourcePath) || hasFileExtension(resourcePath)) {
                return null;
            }
            Resource index = new ClassPathResource(INDEX_HTML_CLASSPATH_LOCATION);
            return index.exists() ? index : null;
        }

        private static boolean isReservedBackendPath(String resourcePath) {
            return resourcePath.equals("api")
                    || resourcePath.startsWith("api/")
                    || resourcePath.equals("actuator")
                    || resourcePath.startsWith("actuator/");
        }

        private static boolean hasFileExtension(String resourcePath) {
            int lastSegmentStart = resourcePath.lastIndexOf('/') + 1;
            return resourcePath.indexOf('.', lastSegmentStart) >= 0;
        }
    }
}
