package uk.gov.hmcts.cp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.jspecify.annotations.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allows the published Swagger UI to call this sandbox from the browser.
 *
 * <p>"Try it out" issues a cross-origin fetch from wherever the API catalogue is hosted. Without this
 * the browser blocks the response before any application code runs, producing a failure that looks
 * like the service is down while the identical request from curl succeeds — a confusing enough
 * symptom that it is worth stating plainly here.
 *
 * <p>Origins are configured rather than wildcarded so the allowed caller is reviewable.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public CorsConfig(@Value("${demo.allowed-origins:}") final String allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null || allowedOrigins.isBlank()
                ? new String[0]
                : allowedOrigins.split("\\s*,\\s*");
    }

    @Override
    public void addCorsMappings(@NonNull final CorsRegistry registry) {
        if (allowedOrigins.length == 0) {
            return;
        }
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "Accept", "traceId")
                // traceId is what TracingFilter sets and what every error body quotes; without exposing it a
                // browser client cannot read the value it is asked to include in an escalation.
                .exposedHeaders("WWW-Authenticate", "traceId")
                // No credentials: this sandbox authenticates with a bearer token, never a cookie, so
                // allowing credentialed requests would widen the surface for nothing.
                .allowCredentials(false)
                .maxAge(3600);
    }
}
