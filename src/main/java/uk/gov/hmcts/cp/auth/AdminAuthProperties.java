package uk.gov.hmcts.cp.auth;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Configuration for the admin realm — the one guarding ingest and publish.
 *
 * <p>Separate from {@link EntraAuthProperties} because it points somewhere completely different.
 * That one describes this sandbox's own demo issuer, whose credentials are published in the API
 * catalogue. This one describes a <b>real</b> Entra tenant. Conflating them would mean the demo
 * client id and secret, printed for anyone to use, opened a write path into a deployed environment.
 *
 * <p>Fails startup on incomplete configuration, for the same reason the demo realm does: a blank
 * audience accepts a token minted for any resource.
 */
@Getter
@Service
@Slf4j
public class AdminAuthProperties {

    private static final int MAX_CLOCK_SKEW_SECONDS = 300;

    private final String tenantId;
    private final String audience;
    private final String issuer;
    private final String jwksUri;
    private final int clockSkewSeconds;

    /** Role required to record. Automated and frequent. */
    private final String writeRole;

    /** Role required to publish. This is the step that puts data on a public endpoint. */
    private final String publishRole;

    /**
     * Turns the admin surface off entirely, answering 503 rather than leaving it open.
     *
     * <p>For environments where no Entra app registration exists yet. It is not a bypass — nothing
     * becomes reachable when this is set, it becomes unreachable.
     */
    private final boolean disabled;

    public AdminAuthProperties(
            @Value("${admin.auth.tenant-id:}") final String tenantId,
            @Value("${admin.auth.audience:}") final String audience,
            @Value("${admin.auth.issuer:}") final String issuer,
            @Value("${admin.auth.jwks-uri:}") final String jwksUri,
            @Value("${admin.auth.clock-skew-seconds:60}") final int clockSkewSeconds,
            @Value("${admin.auth.write-role:app.recordings.write}") final String writeRole,
            @Value("${admin.auth.publish-role:app.recordings.publish}") final String publishRole,
            @Value("${admin.auth.disabled:false}") final boolean disabled) {

        this.tenantId = tenantId;
        this.audience = audience;
        this.clockSkewSeconds = Math.min(clockSkewSeconds, MAX_CLOCK_SKEW_SECONDS);
        this.writeRole = writeRole;
        this.publishRole = publishRole;
        this.disabled = disabled;
        this.issuer = issuer.isBlank() ? defaultIssuer(tenantId) : issuer;
        this.jwksUri = jwksUri.isBlank() ? defaultJwksUri(tenantId) : jwksUri;

        validate();

        if (disabled) {
            log.warn("ADMIN: the recording admin API is DISABLED - /admin/** answers 503. "
                    + "Recordings cannot be ingested or published in this environment.");
        } else {
            log.info("Admin auth initialised issuer:{} audience:{} writeRole:{} publishRole:{}",
                    this.issuer, this.audience, this.writeRole, this.publishRole);
        }
    }

    private void validate() {
        if (disabled) {
            return;
        }
        if (tenantId.isBlank()) {
            throw new IllegalStateException(
                    "admin.auth.tenant-id must be set, or admin.auth.disabled=true. The admin API "
                            + "writes into this environment and cannot be left unguarded.");
        }
        if (audience.isBlank()) {
            throw new IllegalStateException(
                    "admin.auth.audience must be set, or admin.auth.disabled=true. A blank audience "
                            + "would accept tokens minted for any resource.");
        }
    }

    private static String defaultIssuer(final String tenantId) {
        return "https://login.microsoftonline.com/" + tenantId + "/v2.0";
    }

    private static String defaultJwksUri(final String tenantId) {
        return "https://login.microsoftonline.com/" + tenantId + "/discovery/v2.0/keys";
    }
}
