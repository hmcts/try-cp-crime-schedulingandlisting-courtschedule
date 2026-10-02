package uk.gov.hmcts.cp.demo;

import org.springframework.stereotype.Service;
import uk.gov.hmcts.cp.auth.AuthorizationPolicy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The two demo clients this sandbox recognises.
 *
 * <p>Two rather than one on purpose: with a second client that holds no application role, a consumer
 * can exercise the 403 branch of the contract for real — the same validator that guards the live API
 * refuses it, with {@code MISSING_ROLE}, rather than a canned error body being served. Presenting the
 * wrong secret to the token endpoint covers 401 the same way.
 */
@Service
public class DemoClientRegistry {

    /**
     * Full-access demo client: tokens carry {@code app.read} and reach the court schedule endpoint.
     */
    public static final UUID READER_CLIENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    /** Role-less demo client: tokens verify correctly but are refused with 403 by the policy. */
    public static final UUID NO_ROLE_CLIENT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    /** Published in the catalogue alongside the client ids — this sandbox guards nothing. */
    public static final String DEMO_SECRET = "try-it-now-demo-secret";

    private final Map<UUID, DemoClient> clients = Map.of(
            READER_CLIENT_ID, new DemoClient(
                    READER_CLIENT_ID, DEMO_SECRET, List.of(AuthorizationPolicy.ROLE_READ),
                    "Full access. Tokens carry the app.read role and are accepted by every endpoint."),
            NO_ROLE_CLIENT_ID, new DemoClient(
                    NO_ROLE_CLIENT_ID, DEMO_SECRET, List.of(),
                    "No application role, as an app registration that has not been granted access looks. "
                            + "Its tokens are signed correctly but refused with 403 insufficient_scope — "
                            + "use this to exercise your error handling for unentitled credentials."));

    public Optional<DemoClient> find(final String clientId) {
        return parse(clientId).map(clients::get);
    }

    public List<DemoClient> all() {
        return List.copyOf(clients.values());
    }

    private static Optional<UUID> parse(final String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(clientId));
        } catch (final IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
