package uk.gov.hmcts.cp.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Decides which requests need a token, and that the caller holds a recognised role.
 *
 * <p><b>Deny by default:</b> every request is validated unless its path is listed as exempt below.
 * Exemptions are enumerated, never inferred from a prefix — a prefix rule silently exempts endpoints
 * added later.
 *
 * <p>Diverges from the real service in exactly one respect: this sandbox additionally exempts the
 * demo token endpoint, the demo JWKS and the scenario listing, because a caller cannot present a
 * token until it has fetched one. Everything else is enforced identically.
 */
@Service
public class AuthorizationPolicy {

    /** The only application role this sandbox issues, mirroring the real API. */
    public static final String ROLE_READ = "app.read";

    /**
     * Everything under here is guarded by {@link uk.gov.hmcts.cp.filters.AdminAuthFilter}, which
     * runs at a lower order and has already accepted or rejected the request by the time the demo
     * filter consults this policy.
     *
     * <p><b>This prefix is not public.</b> It is the one case where
     * {@link #isExemptFromValidation(String)} returning true does not mean "no token required" — it
     * means "a different, stricter realm has already handled it". Removing the admin filter without
     * removing this would leave ingest and publish wide open.
     */
    public static final String ADMIN_PREFIX = "/admin";

    /** The demo authorisation-server endpoints. A caller must reach these without a token. */
    public static final String PATH_TOKEN = "/oauth2/v2.0/token";
    public static final String PATH_JWKS = "/.well-known/jwks.json";
    public static final String PATH_SCENARIOS = "/scenarios";

    private static final Set<String> KNOWN_ROLES = Set.of(ROLE_READ);

    /**
     * Infrastructure and demo-discovery endpoints, carrying no case data.
     *
     * <p>{@code /health}, {@code /info} and {@code /prometheus} are listed <b>explicitly</b> because
     * {@code management.endpoints.web.base-path} is {@code /} in this service (chart-java probes
     * {@code /health}, not {@code /actuator/health}). With a base path of {@code /} the
     * {@link #publicPathRoots} rule below degenerates — {@code "/health".startsWith("//")} is false —
     * so without these entries the liveness probe is answered with 401 and the pod never becomes
     * ready. Do not remove them without also moving the actuator off the context root.
     */
    private static final Set<String> PUBLIC_EXACT_PATHS = Set.of(
            "/", "", "/error",
            "/health", "/health/liveness", "/health/readiness", "/info", "/prometheus",
            PATH_TOKEN, PATH_JWKS, PATH_SCENARIOS);

    /**
     * Tracks {@code management.endpoints.web.base-path} rather than a hardcoded "/actuator" — some
     * deployments of this same image sit behind an ingress route that forwards the full path
     * unrewritten, and are given a different base path (e.g. "/case/actuator") accordingly.
     *
     * <p>A base path of {@code /} contributes nothing and is dropped, so the exact list above is the
     * only thing exempting the actuator in the default configuration.
     */
    private final List<String> publicPathRoots;

    public AuthorizationPolicy(@Value("${management.endpoints.web.base-path}") final String actuatorBasePath) {
        this.publicPathRoots = actuatorBasePath == null || actuatorBasePath.isBlank() || "/".equals(actuatorBasePath)
                ? List.of()
                : List.of(stripTrailingSlash(actuatorBasePath));
    }

    /**
     * True when this filter chain's demo realm should not validate the request — either because the
     * path is genuinely public, or because the admin realm owns it. See {@link #ADMIN_PREFIX} for
     * why those two cases share one method.
     */
    public boolean isExemptFromValidation(final String requestUri) {
        final String path = stripTrailingSlash(requestUri);
        return PUBLIC_EXACT_PATHS.contains(path)
                || isAdminPath(requestUri)
                || publicPathRoots.stream().anyMatch(root -> path.equals(root) || path.startsWith(root + "/"));
    }

    /** True for anything the admin realm guards. Static so the admin filter can ask without a bean. */
    public static boolean isAdminPath(final String requestUri) {
        final String path = stripTrailingSlash(requestUri);
        return ADMIN_PREFIX.equals(path) || path.startsWith(ADMIN_PREFIX + "/");
    }

    /** The roles this API recognises; a caller needs at least one of them. */
    public Set<String> knownRoles() {
        return KNOWN_ROLES;
    }

    /**
     * @throws TokenValidationException with {@code INSUFFICIENT_ROLE} when the caller holds no role
     *         this API recognises
     */
    public void assertAuthorized(final ValidatedCaller caller) throws TokenValidationException {
        if (KNOWN_ROLES.stream().noneMatch(caller::hasRole)) {
            throw new TokenValidationException(TokenValidationException.Reason.INSUFFICIENT_ROLE);
        }
    }

    /**
     * Strips a single trailing slash. Traversal and encoding are already normalised by the servlet
     * container before {@code getRequestURI()} reaches here.
     */
    private static String stripTrailingSlash(final String requestUri) {
        final String trimmed = requestUri == null ? "" : requestUri.trim();
        return trimmed.length() > 1 && trimmed.endsWith("/")
                ? trimmed.substring(0, trimmed.length() - 1)
                : trimmed;
    }
}
