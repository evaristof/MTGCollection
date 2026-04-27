package com.evaristof.mtgcollection.config;

import java.io.IOException;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the bundled React SPA embedded under {@code classpath:/static/} and
 * falls back to {@code index.html} for unknown paths so React Router can
 * handle client-side routing.
 *
 * <p>The previous implementation was an {@code @GetMapping} controller with a
 * regex that only forbade dots in the first path segment, which meant URLs
 * like {@code /assets/index-abc123.js} were matched by the fallback and
 * forwarded to {@code /index.html}. The browser then refused to execute the
 * response as a JS module because the MIME type came back as
 * {@code text/html}, and the page rendered blank.
 *
 * <p>Using a {@link PathResourceResolver} avoids regex gymnastics: we first
 * look up the exact file on the classpath, and only if it does not exist do
 * we fall back to {@code index.html}. API, H2 console and actuator paths are
 * left alone so their own controllers continue to serve them.
 *
 * <p>Only active when {@code static/index.html} exists on the classpath — in
 * plain {@code mvn spring-boot:run} (backend only, frontend on Vite dev
 * server) the fallback target is absent and Spring Boot simply returns 404
 * for unknown routes, which is the expected behavior.
 */
@Configuration
public class SpaFallbackController implements WebMvcConfigurer {

    private static final String STATIC_LOCATION = "classpath:/static/";
    private static final Resource INDEX_HTML = new ClassPathResource("/static/index.html");

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(STATIC_LOCATION)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location)
                            throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        if (isBackendPath(resourcePath) || looksLikeStaticAsset(resourcePath)) {
                            return null;
                        }
                        return INDEX_HTML.exists() ? INDEX_HTML : null;
                    }
                });
    }

    private static boolean isBackendPath(String resourcePath) {
        return resourcePath.startsWith("api/")
                || resourcePath.equals("api")
                || resourcePath.startsWith("h2-console")
                || resourcePath.startsWith("actuator/")
                || resourcePath.equals("actuator")
                || resourcePath.equals("error");
    }

    private static boolean looksLikeStaticAsset(String resourcePath) {
        int lastSlash = resourcePath.lastIndexOf('/');
        String lastSegment = resourcePath.substring(lastSlash + 1);
        return lastSegment.contains(".");
    }
}
