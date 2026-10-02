package uk.gov.hmcts.cp.controllers;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.auth.AuthorizationPolicy;
import uk.gov.hmcts.cp.demo.DemoSigningKey;

import java.util.Map;

/**
 * Publishes the public half of the demo signing key.
 *
 * <p>The validator does not read this endpoint — it verifies against an in-memory key set, so the
 * service needs no network access to authenticate a request. This exists so a consumer can inspect
 * the key, and so the sandbox presents the same discovery surface as a real issuer.
 */
@RestController
public class JwksController {

    private final DemoSigningKey demoSigningKey;

    public JwksController(final DemoSigningKey demoSigningKey) {
        this.demoSigningKey = demoSigningKey;
    }

    @GetMapping(value = AuthorizationPolicy.PATH_JWKS, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jwks() {
        // publicKeysOnly=true, over a set already reduced to its public half — the private key
        // never leaves the process by either route.
        return demoSigningKey.publicJwkSet().toJSONObject(true);
    }
}
