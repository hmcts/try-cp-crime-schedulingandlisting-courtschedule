package uk.gov.hmcts.cp.demo;

import java.util.List;
import java.util.UUID;

/**
 * A published set of demo credentials.
 *
 * <p>These are deliberately public — they are printed in the API catalogue so anyone evaluating the
 * API can call it without requesting access. They authorise nothing beyond this sandbox, which holds
 * no real data and reaches no backend.
 *
 * @param clientId     the {@code azp} claim of tokens minted for this client. A UUID because the real
 *                     validator parses {@code azp} as one, exactly as Entra issues it.
 * @param clientSecret presented at the token endpoint; a mismatch is the sandbox's genuine 401 path
 * @param roles        the {@code roles} claim. A client with none of the recognised roles is the
 *                     sandbox's genuine 403 path.
 * @param description  shown on {@code /scenarios} so the two clients are self-documenting
 */
public record DemoClient(UUID clientId, String clientSecret, List<String> roles, String description) {

    public DemoClient {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
