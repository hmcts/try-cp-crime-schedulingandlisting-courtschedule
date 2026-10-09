package uk.gov.hmcts.cp.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

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

    /**
     * The actuator health endpoint and everything under it.
     *
     * <p>This is the one place the "enumerate, never infer" rule above is relaxed, and deliberately.
     * chart-java probes {@code /health/liveness} and {@code /health/readiness}, and Spring publishes
     * one path per health group — so enumerating them means the liveness probe starts answering 401
     * the day somebody adds a group, and the pod stops becoming ready for a reason nothing explains.
     *
     * <p>Matched as the exact path or a child of it, never a bare {@code startsWith}: {@code /healthz}
     * and {@code /health-admin} are <b>not</b> exempt. The blast radius is bounded by what Spring
     * mounts under {@code /health/}, which is health groups only, and {@code show-details: always}
     * already publishes the same component detail at {@code /health} itself.
     */
    public static final String HEALTH_PREFIX = "/health";

    /**
     * A Spring health group name: one path segment of plain identifier characters. Excludes {@code .}
     * so {@code .} and {@code ..} cannot match, and excludes {@code %} so nothing percent-encoded can.
     */
    private static final Pattern HEALTH_GROUP = Pattern.compile("[A-Za-z0-9_-]+");

    /** The demo authorisation-server endpoints. A caller must reach these without a token. */
    public static final String PATH_TOKEN = "/oauth2/v2.0/token";
    public static final String PATH_JWKS = "/.well-known/jwks.json";
    public static final String PATH_SCENARIOS = "/scenarios";

    private static final Set<String> KNOWN_ROLES = Set.of(ROLE_READ);

    /**
     * Infrastructure and demo-discovery endpoints, carrying no case data.
     *
     * <p>{@code /info} and {@code /prometheus} are listed <b>explicitly</b> because
     * {@code management.endpoints.web.base-path} is {@code /} in this service (chart-java probes
     * {@code /health}, not {@code /actuator/health}). With a base path of {@code /} the
     * {@link #publicPathRoots} rule below degenerates — {@code "/health".startsWith("//")} is false —
     * so without these entries they are answered with 401. Do not remove them without also moving
     * the actuator off the context root. {@code /health} and its groups are handled by
     * {@link #HEALTH_PREFIX} instead.
     */
    private static final Set<String> PUBLIC_EXACT_PATHS = Set.of(
            "/", "", "/error",
            "/info", "/prometheus",
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
                || isHealthPath(path)
                || isAdminPath(requestUri)
                || publicPathRoots.stream().anyMatch(root -> path.equals(root) || path.startsWith(root + "/"));
    }

    /**
     * True for {@code /health} and a single health-group segment beneath it, and nothing else.
     *
     * <p>Deliberately narrower than {@code startsWith("/health/")}. That form exempts
     * {@code /health/../case/{urn}/courtschedule} — a data endpoint — because the string does start
     * with the prefix. Spring publishes one segment per health group, so requiring exactly one
     * segment admits every real probe path and nothing else. {@code AuthorizationPolicyTest} covers
     * both halves.
     *
     * <p>The group is matched against {@link #HEALTH_GROUP} rather than merely checked for a
     * {@code '/'}, because the caller passes {@link jakarta.servlet.http.HttpServletRequest#getRequestURI()},
     * which is <b>not</b> percent-decoded. A {@code '/'} test alone would accept
     * {@code /health/%2e%2e%2fcase%2f{urn}%2fcourtschedule} — one segment by that measure, a traversal
     * once decoded. Tomcat rejects encoded slashes by default, but that is a container default and
     * not somewhere to put an authorisation boundary. Health group names are plain identifiers, so
     * the character class costs nothing and closes the question.
     */
    private static boolean isHealthPath(final String path) {
        if (HEALTH_PREFIX.equals(path)) {
            return true;
        }
        if (!path.startsWith(HEALTH_PREFIX + "/")) {
            return false;
        }
        return HEALTH_GROUP.matcher(path.substring(HEALTH_PREFIX.length() + 1)).matches();
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
