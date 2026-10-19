package uk.gov.hmcts.amp.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import uk.gov.hmcts.amp.filters.ClientIdResolutionFilter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What {@link TokenKeySource#ENTRA} actually removes.
 *
 * <p>The flag would be worth little if it only swapped the key source: the service would still be
 * minting tokens stamped with a real tenant's issuer and signed with its own key, which no verifier
 * anywhere would accept, while {@code /scenarios} went on advertising the credentials to do it. So
 * the token endpoint, the JWKS endpoint and the demo clients leave the context entirely, and these
 * tests are what stop one of them being wired back in unconditionally.
 *
 * <p>The JWKS URI here is never fetched — Nimbus resolves keys lazily, on the first token — so no
 * egress is needed to assert the wiring.
 */
@SpringBootTest(properties = {
    "auth.key-source=ENTRA",
    "auth.tenant-id=531ff96d-0ae9-462a-8d2d-bec7c0b42082",
    "auth.audience=9b6b1c6e-4a1f-4c4d-9c10-6e4f0a1d2b33",
    "auth.issuer=https://login.microsoftonline.com/531ff96d-0ae9-462a-8d2d-bec7c0b42082/v2.0"})
@Testcontainers
class EntraKeySourceTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private final MockMvc mockMvc;

    /**
     * The auth filter is registered explicitly. {@code webAppContextSetup} alone installs no servlet
     * filters, so the read API would answer from the controller unauthenticated and the 401
     * assertion below would fail for a reason that has nothing to do with the token realm.
     */
    EntraKeySourceTest(@Autowired final WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(ClientIdResolutionFilter.class))
                .build();
    }

    @Test
    @DisplayName("the_demo_token_endpoint_should_not_exist")
    void tokenEndpointIsGone() throws Exception {
        mockMvc.perform(post("/oauth2/v2.0/token")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=x&client_secret=y"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the_demo_jwks_endpoint_should_not_exist")
    void jwksEndpointIsGone() throws Exception {
        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("scenarios_should_report_the_entra_realm_and_publish_no_credentials")
    void scenariosDescribesTheEntraRealm() throws Exception {
        mockMvc.perform(get("/scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authentication.mode").value("entra"))
                .andExpect(jsonPath("$.authentication.requiredRole").value("app.read"))
                .andExpect(jsonPath("$.authentication.tokenEndpoint")
                        .value("https://login.microsoftonline.com/531ff96d-0ae9-462a-8d2d-bec7c0b42082"
                                + "/oauth2/v2.0/token"))
                .andExpect(jsonPath("$.authentication.scope")
                        .value("9b6b1c6e-4a1f-4c4d-9c10-6e4f0a1d2b33/.default"))
                .andExpect(jsonPath("$.demoClients").doesNotExist());
    }

    @Test
    @DisplayName("the_court_schedule_endpoint_should_still_require_a_token")
    void readApiStillRequiresTheToken() throws Exception {
        mockMvc.perform(get("/case/ABC123/courtschedule"))
                .andExpect(status().isUnauthorized());
    }
}
