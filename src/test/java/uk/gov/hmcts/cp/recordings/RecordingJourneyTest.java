package uk.gov.hmcts.cp.recordings;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.gov.hmcts.cp.auth.AdminAuthProperties;
import uk.gov.hmcts.cp.auth.AdminTokenValidator;
import uk.gov.hmcts.cp.auth.AuthMode;
import uk.gov.hmcts.cp.auth.EntraAuthProperties;
import uk.gov.hmcts.cp.auth.EntraTokenValidator;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole pipeline: capture arrives, is checked against the contract, is stored unpublished,
 * serves nothing, and only starts serving once someone with the publish role approves it.
 *
 * <p>Runs against a real Postgres and the real filter chain. The only thing swapped is where the
 * admin realm gets its verification key — {@code ENFORCE} stays on, so these tests exercise the
 * same validation that guards a deployed environment.
 */
@SpringBootTest
@Testcontainers
class RecordingJourneyTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("admin.auth.tenant-id", () -> AdminTokens.TENANT_ID);
        registry.add("admin.auth.audience", () -> AdminTokens.AUDIENCE);
        registry.add("admin.auth.issuer", () -> AdminTokens.ISSUER);
    }

    /**
     * Swaps only the key source, exactly as the service does for its demo realm. Marked primary so
     * the production bean — which would fetch JWKS from a real tenant — is not the one injected.
     */
    @TestConfiguration
    static class TestAdminRealm {
        @Bean
        @Primary
        AdminTokenValidator testAdminTokenValidator(final AdminAuthProperties properties) {
            final EntraAuthProperties adminProperties = new EntraAuthProperties(
                    AuthMode.ENFORCE, AdminTokens.TENANT_ID, AdminTokens.AUDIENCE,
                    AdminTokens.ISSUER, "", 60, 600);
            final JWKSource<SecurityContext> source =
                    new ImmutableJWKSet<>(new JWKSet(AdminTokens.KEY.toPublicJWK()));
            return new AdminTokenValidator(new EntraTokenValidator(adminProperties, source), properties);
        }
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).addFilters(
                context.getBeansOfType(jakarta.servlet.Filter.class).values()
                        .toArray(new jakarta.servlet.Filter[0])).build();
    }

    private static final String VALID_BODY = """
            {"courtSchedule":[{"hearings":[{"hearingId":"HRG-REC-1","hearingType":"Trial"}]}]}""";

    private static String ingestPayload(final String caseUrn, final String body) {
        return """
                {"caseUrn":"%s","httpStatus":200,"body":%s,"recordedFrom":"dev","summary":"captured"}"""
                .formatted(caseUrn, quote(body));
    }

    /** The body travels as JSON text, so it has to be escaped into the request document. */
    private static String quote(final String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    @DisplayName("ingest without a token is refused by the admin realm")
    void ingestRequiresAToken() throws Exception {
        mvc().perform(post("/admin/recordings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ingestPayload("TIN-NOAUTH-01", VALID_BODY)))
                .andExpect(status().isUnauthorized())
                // Names the realm, so it is distinguishable from a demo-token rejection on the
                // public API - different credential, different audience, same status code.
                .andExpect(header().string("WWW-Authenticate", containsString("realm=\"admin\"")));
    }

    @Test
    @DisplayName("the write role alone cannot publish")
    void writeRoleCannotPublish() throws Exception {
        final String captured = mvc().perform(post("/admin/recordings")
                        .header("Authorization", AdminTokens.bearerFor(List.of(AdminTokens.WRITE_ROLE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ingestPayload("TIN-ROLE-01", VALID_BODY)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        final String id = captured.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mvc().perform(post("/admin/recordings/" + id + "/publish")
                        .header("Authorization", AdminTokens.bearerFor(List.of(AdminTokens.WRITE_ROLE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioId\":\"role-test\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a body that does not match the contract is rejected, naming the field")
    void contractViolationIsRejectedAtIngest() throws Exception {
        final String undeclared = """
                {"courtSchedule":[{"hearings":[{"hearingId":"H","lastUpdatedTime":"2026-07-09T09:00:00Z"}]}]}""";

        mvc().perform(post("/admin/recordings")
                        .header("Authorization", AdminTokens.bearerFor(List.of(AdminTokens.WRITE_ROLE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ingestPayload("TIN-BAD-01", undeclared)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("contract_violation"))
                .andExpect(jsonPath("$.message").value(containsString("lastUpdatedTime")));
    }

    @Test
    @DisplayName("a recording serves nothing until it is published, then serves immediately")
    void unpublishedServesNothingThenPublishGoesLive() throws Exception {
        final String urn = "TIN-JOURNEY-01";

        final String captured = mvc().perform(post("/admin/recordings")
                        .header("Authorization", AdminTokens.bearerFor(List.of(AdminTokens.WRITE_ROLE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ingestPayload(urn, VALID_BODY)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNPUBLISHED"))
                .andExpect(jsonPath("$.recordedFrom").value("dev"))
                .andReturn().getResponse().getContentAsString();

        final String id = captured.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        // Stored, validated - and invisible. This is the whole point of the two-stage design.
        final String demoToken = demoBearer();
        mvc().perform(get("/case/" + urn + "/courtschedule").header("Authorization", demoToken))
                .andExpect(status().isNotFound());

        mvc().perform(post("/admin/recordings/" + id + "/publish")
                        .header("Authorization", AdminTokens.bearerFor(List.of(AdminTokens.PUBLISH_ROLE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioId\":\"journey\",\"summary\":\"published by test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        // Live with no redeploy.
        mvc().perform(get("/case/" + urn + "/courtschedule").header("Authorization", demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courtSchedule[0].hearings[0].hearingId").value("HRG-REC-1"));
    }

    @Test
    @DisplayName("committed fixtures are seeded and served, and say where they came from")
    void fixturesAreSeededAndLabelled() throws Exception {
        mvc().perform(get("/case/TIN-ALLOCATED-01/courtschedule").header("Authorization", demoBearer()))
                .andExpect(status().isOk());

        mvc().perform(get("/scenarios"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"recordedFrom\":\"fixture\"")));
    }

    /** A demo token for the public read API — a different realm entirely from the admin one. */
    private String demoBearer() throws Exception {
        final String response = mvc().perform(post("/oauth2/v2.0/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials"
                                + "&client_id=11111111-1111-4111-8111-111111111111"
                                + "&client_secret=try-it-now-demo-secret"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + response.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }
}
