package uk.gov.hmcts.cp.auth;

import java.util.List;
import java.util.UUID;

/**
 * The outcome of successfully validating an Entra access token.
 *
 * @param clientId the calling application's Entra client id, from {@code azp}. This is the tenancy
 *                 key for every repository query. Note it is <b>not</b> {@code oid}/{@code sub},
 *                 which is the service principal object id.
 * @param roles    application roles from the {@code roles} claim, never null
 * @param verified false only in {@link AuthMode#OFF} / {@link AuthMode#OBSERVE}, where the client id
 *                 came from an unverified token. Anything security-relevant must check this.
 */
public record ValidatedCaller(UUID clientId, List<String> roles, boolean verified) {

    public ValidatedCaller {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public boolean hasRole(final String role) {
        return roles.contains(role);
    }
}
