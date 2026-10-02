package uk.gov.hmcts.cp.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.cp.auth.AdminAuthProperties;
import uk.gov.hmcts.cp.auth.AdminTokenValidator;
import uk.gov.hmcts.cp.auth.AuthMode;
import uk.gov.hmcts.cp.auth.EntraAuthProperties;
import uk.gov.hmcts.cp.auth.EntraTokenValidator;

import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;

/**
 * Wires the admin realm.
 *
 * <p>Note what is different from {@link AppConfig}: the demo realm verifies against an in-memory key
 * this service owns, which is why it needs no egress. The admin realm verifies against a <b>real</b>
 * tenant's published keys, so it does fetch JWKS over HTTPS. That is the one outbound call this
 * service makes, and it is only made when the admin API is enabled.
 *
 * <p>Neither the validator nor its properties are exposed as beans of a type the demo realm also
 * uses — {@link AdminTokenValidator} is its own type — so nothing here makes the existing injection
 * points ambiguous.
 */
@Configuration
@Slf4j
public class AdminAuthConfig {

    private static final long JWKS_REFRESH_TIMEOUT_MS = Duration.ofSeconds(15).toMillis();
    private static final long JWKS_MIN_REFRESH_INTERVAL_MS = Duration.ofSeconds(30).toMillis();
    private static final long JWKS_OUTAGE_TTL_MS = Duration.ofHours(24).toMillis();
    private static final long JWKS_CACHE_TTL_SECONDS = 600;

    @Bean
    public AdminTokenValidator adminTokenValidator(final AdminAuthProperties properties)
            throws MalformedURLException {

        if (properties.isDisabled()) {
            return new AdminTokenValidator(null, properties);
        }

        // Constructed directly rather than injected: EntraAuthProperties is a bean describing the
        // DEMO issuer, and this realm needs a second, unrelated set of values. Same class, because
        // the validation logic must be identical to the one guarding the live API.
        final EntraAuthProperties adminProperties = new EntraAuthProperties(
                AuthMode.ENFORCE,
                properties.getTenantId(),
                properties.getAudience(),
                properties.getIssuer(),
                properties.getJwksUri(),
                properties.getClockSkewSeconds(),
                JWKS_CACHE_TTL_SECONDS);

        final JWKSource<SecurityContext> jwkSource = JWKSourceBuilder
                .create(URI.create(adminProperties.getJwksUri()).toURL())
                .cache(Duration.ofSeconds(JWKS_CACHE_TTL_SECONDS).toMillis(), JWKS_REFRESH_TIMEOUT_MS)
                .rateLimited(JWKS_MIN_REFRESH_INTERVAL_MS)
                .outageTolerant(JWKS_OUTAGE_TTL_MS)
                .build();

        log.info("Admin realm verifying against {}", adminProperties.getJwksUri());
        return new AdminTokenValidator(new EntraTokenValidator(adminProperties, jwkSource), properties);
    }
}
