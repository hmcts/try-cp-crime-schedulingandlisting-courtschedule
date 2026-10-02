package uk.gov.hmcts.cp.controllers;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.auth.AuthorizationPolicy;
import uk.gov.hmcts.cp.demo.DemoClient;
import uk.gov.hmcts.cp.demo.DemoClientRegistry;
import uk.gov.hmcts.cp.demo.DemoTokenMinter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;

/**
 * The sandbox's own token endpoint, shaped like the Entra one a consumer will use in production.
 *
 * <p>It accepts the same {@code client_credentials} form post as
 * {@code login.microsoftonline.com/{tenant}/oauth2/v2.0/token} and returns the same response fields,
 * so a consumer can write its token-acquisition code here and change only the URL when it moves to a
 * real environment.
 *
 * <p>The path is fixed rather than carrying a {@code /{tenant}/} segment, because
 * {@link AuthorizationPolicy} exempts paths by exact match — a variable segment could not be
 * enumerated, and a prefix exemption would silently exempt anything added beneath it later.
 */
@RestController
@Slf4j
public class DemoTokenController {

    private static final String GRANT_TYPE_CLIENT_CREDENTIALS = "client_credentials";

    private final DemoClientRegistry clientRegistry;
    private final DemoTokenMinter tokenMinter;

    public DemoTokenController(final DemoClientRegistry clientRegistry, final DemoTokenMinter tokenMinter) {
        this.clientRegistry = clientRegistry;
        this.tokenMinter = tokenMinter;
    }

    @PostMapping(
            value = AuthorizationPolicy.PATH_TOKEN,
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> token(
            @RequestParam(name = "grant_type", required = false) final String grantType,
            @RequestParam(name = "client_id", required = false) final String clientId,
            @RequestParam(name = "client_secret", required = false) final String clientSecret) {

        if (!GRANT_TYPE_CLIENT_CREDENTIALS.equals(grantType)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "unsupported_grant_type",
                    "error_description", "This sandbox supports only grant_type=client_credentials."));
        }

        final Optional<DemoClient> client = clientRegistry.find(clientId)
                .filter(candidate -> secretMatches(candidate, clientSecret));

        if (client.isEmpty()) {
            // Deliberately does not distinguish "no such client" from "wrong secret" — the same
            // reticence a real authorisation server shows, and one of the two failure paths a
            // consumer can exercise here on purpose.
            log.info("Demo token request rejected: unknown client or bad secret");
            return ResponseEntity.status(401).body(Map.of(
                    "error", "invalid_client",
                    "error_description", "Unknown client_id, or client_secret does not match. "
                            + "The demo credentials are listed at GET /scenarios."));
        }

        final DemoClient demoClient = client.get();
        log.info("Issuing demo token for client:{} roles:{}", demoClient.clientId(), demoClient.roles());

        return ResponseEntity.ok(Map.of(
                "access_token", tokenMinter.mint(demoClient),
                "token_type", "Bearer",
                "expires_in", tokenMinter.ttlSeconds()));
    }

    /**
     * Constant-time comparison. The demo secret is published, so nothing is protected by this — it is
     * here so the sandbox does not model a comparison a consumer might copy into real code.
     */
    private static boolean secretMatches(final DemoClient client, final String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                client.clientSecret().getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
