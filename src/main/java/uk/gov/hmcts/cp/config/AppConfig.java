package uk.gov.hmcts.cp.config;

import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.cp.auth.EntraAuthProperties;
import uk.gov.hmcts.cp.auth.EntraTokenValidator;
import uk.gov.hmcts.cp.demo.DemoSigningKey;
import uk.gov.hmcts.cp.services.ClockService;

import java.time.Clock;

@Configuration
public class AppConfig {

    @Bean
    public ClockService clockService() {
        return new ClockService(Clock.systemUTC());
    }

    /**
     * The one deliberate difference from the live service's wiring.
     *
     * <p>There, this is a {@code JWKSourceBuilder} fetching a real tenant's keys over HTTPS. Here the
     * keys are held in memory, because this sandbox is its own issuer: it mints the tokens it
     * verifies. That makes it work with no egress at all — no CP network, no Entra — which is the
     * whole reason it can run in CNP.
     *
     * <p>Swapping the source rather than editing the validator is what lets
     * {@link EntraTokenValidator} stay byte-identical to the copy guarding the live API, so the auth
     * behaviour a consumer meets here is the behaviour they will meet in production.
     */
    @Bean
    public JWKSource<SecurityContext> demoJwkSource(final DemoSigningKey demoSigningKey) {
        return new ImmutableJWKSet<>(demoSigningKey.publicJwkSet());
    }

    @Bean
    public EntraTokenValidator entraTokenValidator(final EntraAuthProperties authProperties,
                                                   final JWKSource<SecurityContext> demoJwkSource) {
        return new EntraTokenValidator(authProperties, demoJwkSource);
    }
}
