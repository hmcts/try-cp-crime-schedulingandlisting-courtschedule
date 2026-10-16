package uk.gov.hmcts.cp.config;

import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.cp.auth.EntraAuthProperties;
import uk.gov.hmcts.cp.auth.EntraTokenValidator;
import uk.gov.hmcts.cp.demo.DemoSigningKey;
import uk.gov.hmcts.cp.services.ClockService;

import java.net.MalformedURLException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;

/**
 * Wires the one thing that differs between the two token realms: where the verification keys come
 * from.
 *
 * <p>{@link EntraTokenValidator} is byte-identical to the copy guarding the live API and is given a
 * {@link JWKSource} either way — swapping the source rather than editing the validator is what keeps
 * the auth behaviour a consumer meets here the behaviour they meet in production.
 *
 * @see TokenKeySource
 */
@Configuration
@Slf4j
public class AppConfig {

    private static final long JWKS_REFRESH_TIMEOUT_MS = Duration.ofSeconds(15).toMillis();
    private static final long JWKS_MIN_REFRESH_INTERVAL_MS = Duration.ofSeconds(30).toMillis();
    private static final long JWKS_OUTAGE_TTL_MS = Duration.ofHours(24).toMillis();

    @Bean
    public ClockService clockService() {
        return new ClockService(Clock.systemUTC());
    }

    /**
     * {@link TokenKeySource#SELF_ISSUED}: the keys are held in memory, because this sandbox is its
     * own issuer — it mints the tokens it verifies. That makes it work with no egress at all, no CP
     * network and no Entra, which is what lets it run anywhere.
     */
    @Bean
    @ConditionalOnProperty(name = "auth.key-source", havingValue = "SELF_ISSUED", matchIfMissing = true)
    public JWKSource<SecurityContext> selfIssuedJwkSource(final DemoSigningKey demoSigningKey) {
        log.info("Verifying tokens against the in-memory demo key set");
        return new ImmutableJWKSet<>(demoSigningKey.publicJwkSet());
    }

    /**
     * {@link TokenKeySource#ENTRA}: a real tenant's published keys, fetched over HTTPS. This is the
     * only outbound call the service makes, and it exists only in this mode.
     *
     * <p>Cached so verification costs no call per request, rate-limited so an unknown {@code kid}
     * cannot be used to hammer Entra, and outage-tolerant so a JWKS endpoint being unreachable does
     * not take authentication down with it. Key rotation is absorbed without a redeploy.
     *
     * <p>{@code keySourceProperties} is injected for its startup check, not its value: it refuses to
     * construct when {@code auth.*} still holds the self-issued identifiers, and depending on it here
     * makes that ordering explicit rather than incidental.
     */
    @Bean
    @ConditionalOnProperty(name = "auth.key-source", havingValue = "ENTRA")
    public JWKSource<SecurityContext> entraJwkSource(final EntraAuthProperties authProperties,
                                                     final AuthKeySourceProperties keySourceProperties)
            throws MalformedURLException {

        final long cacheTtlMs = Duration.ofSeconds(authProperties.getJwksCacheTtlSeconds()).toMillis();
        log.info("Verifying tokens against Entra JWKS at {} (key source {})",
                authProperties.getJwksUri(), keySourceProperties.getKeySource());

        return JWKSourceBuilder
                .create(URI.create(authProperties.getJwksUri()).toURL())
                .cache(cacheTtlMs, JWKS_REFRESH_TIMEOUT_MS)
                .rateLimited(JWKS_MIN_REFRESH_INTERVAL_MS)
                .outageTolerant(JWKS_OUTAGE_TTL_MS)
                .build();
    }

    @Bean
    public EntraTokenValidator entraTokenValidator(final EntraAuthProperties authProperties,
                                                   final JWKSource<SecurityContext> jwkSource) {
        return new EntraTokenValidator(authProperties, jwkSource);
    }
}
