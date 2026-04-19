package com.evaristof.mtgcollection.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards any non-API, non-static request to {@code /index.html} so the
 * bundled React SPA can handle client-side routing (React Router) when the
 * frontend is served from the Spring Boot jar in packaged distributions.
 *
 * <p>Only active when {@code static/index.html} exists on the classpath — in
 * plain {@code mvn spring-boot:run} (backend only, frontend on Vite dev
 * server) the forward target is absent and Spring Boot simply returns 404 for
 * unknown routes, which is the expected behavior.
 */
@Controller
public class SpaFallbackController {

    /**
     * Matches any single-segment or nested path that does NOT start with
     * {@code api/}, does NOT contain a dot (so real files like {@code app.js}
     * or {@code favicon.ico} hit the static resource resolver first), and is
     * not one of the Spring Boot management / H2 console endpoints.
     */
    @GetMapping(value = {
            "/{path:^(?!api$|h2-console$|actuator$)[^.]+}",
            "/{path:^(?!api|h2-console|actuator)[^.]+}/**"
    })
    public String forwardToSpa() {
        return "forward:/index.html";
    }
}
