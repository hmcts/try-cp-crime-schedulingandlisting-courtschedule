package uk.gov.hmcts.cp.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.cp.auth.EntraAuthProperties;

import java.util.UUID;

/**
 * Which token realm this deployment runs, and the startup check that stops the two being confused.
 *
 * <p>Separate from {@link EntraAuthProperties} on purpose: that class is a byte-identical copy of the
 * one guarding the live API and must not gain a field this sandbox invented. Everything specific to
 * running both realms from one binary lives here instead.
 *
 * <p>The check it performs is narrow and worth stating. {@code auth.*} defaults to the self-issued
 * identifiers so local development needs no configuration at all; in {@link TokenKeySource#ENTRA}
 * those same defaults would mean "verify real Entra tokens against a tenant that does not exist",
 * which fails at request time with a 401 per call and nothing in the logs explaining it. So it fails
 * at startup instead, naming the variable that was not set.
 */
@Getter
@Service
@Slf4j
public class AuthKeySourceProperties {

    private static final String HTTPS = "https://";

    private final TokenKeySource keySource;
    private final String selfIssuedTenantId;
    private final String selfIssuedAudience;
    private final String selfIssuedIssuer;

    public AuthKeySourceProperties(
            @Value("${auth.key-source:ENTRA}") final TokenKeySource keySource,
            @Value("${demo.self-issued.tenant-id}") final String selfIssuedTenantId,
            @Value("${demo.self-issued.audience}") final String selfIssuedAudience,
            @Value("${demo.self-issued.issuer}") final String selfIssuedIssuer,
            final EntraAuthProperties authProperties) {

        this.keySource = keySource;
        this.selfIssuedTenantId = selfIssuedTenantId;
        this.selfIssuedAudience = selfIssuedAudience;
        this.selfIssuedIssuer = selfIssuedIssuer;

        if (keySource == TokenKeySource.ENTRA) {
            assertEntraConfigured(authProperties, selfIssuedTenantId, selfIssuedAudience, selfIssuedIssuer);
            log.info("Token key source ENTRA — verifying real Entra tokens against {}", authProperties.getJwksUri());
        } else {
            log.info("Token key source SELF_ISSUED — this sandbox mints and verifies its own tokens. "
                    + "They are not Entra tokens and authenticate against nothing else.");
        }
    }

    public boolean isSelfIssued() {
        return keySource == TokenKeySource.SELF_ISSUED;
    }

    /**
     * Refuses to start when {@code auth.key-source=ENTRA} but {@code auth.*} still describes this
     * service's own invented issuer.
     *
     * <p>Reached through the constructor, which is what the tests exercise — a bean that cannot be
     * built is the behaviour being asserted, not this method in isolation.
     *
     * @throws IllegalStateException naming the environment variable that must be set
     */
    private static void assertEntraConfigured(final EntraAuthProperties auth,
                                      final String selfIssuedTenantId,
                                      final String selfIssuedAudience,
                                      final String selfIssuedIssuer) {

        requireOverridden(auth.getTenantId(), selfIssuedTenantId, "AUTH_TENANT_ID",
                "the directory (tenant) id of the Entra tenant that issues the tokens");
        requireOverridden(auth.getAudience(), selfIssuedAudience, "AUTH_AUDIENCE",
                "this API's own application (client) id");
        requireOverridden(auth.getIssuer(), selfIssuedIssuer, "AUTH_ISSUER",
                "https://login.microsoftonline.com/<tenant-id>/v2.0");

        if (!auth.getJwksUri().startsWith(HTTPS)) {
            throw new IllegalStateException(
                    "auth.key-source=ENTRA requires AUTH_JWKS_URI to be an https URL, or AUTH_TENANT_ID "
                            + "set so it can be derived. Got: " + auth.getJwksUri());
        }

        // Not fatal, because EntraAuthProperties is shared verbatim with the live service and this
        // sandbox must not impose a rule that one does not. Loud, because the symptom otherwise is
        // every token rejected with no clue why - see the debugging table in CLAUDE.md.
        if (!isUuid(auth.getAudience())) {
            log.warn("SECURITY: auth.audience is '{}', which is not a bare GUID. A v2.0 Entra access "
                    + "token carries the resource's application id in 'aud', not an api:// URI, so "
                    + "every token will be rejected with INVALID_CLAIMS.", auth.getAudience());
        }
    }

    private static void requireOverridden(final String configured, final String selfIssued,
                                          final String variable, final String expected) {
        if (configured.equalsIgnoreCase(selfIssued)) {
            throw new IllegalStateException(
                    "auth.key-source=ENTRA but " + variable + " is still the self-issued sandbox value. "
                            + "Set it to " + expected + ". Leaving it would verify real Entra tokens "
                            + "against an issuer that does not exist, rejecting every request.");
        }
    }

    private static boolean isUuid(final String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }
}
