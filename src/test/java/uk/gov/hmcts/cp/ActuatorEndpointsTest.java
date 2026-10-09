package uk.gov.hmcts.cp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two endpoints the platform reads: {@code /health}, which the chart-java probes call, and
 * {@code /info}.
 *
 * <p>Both must answer <strong>unauthenticated</strong>. {@code management.endpoints.web.base-path}
 * is {@code /}, so they sit at the context root rather than under {@code /actuator} — if they ever
 * stop being exempt in {@link uk.gov.hmcts.cp.auth.AuthorizationPolicy}, the liveness probe gets a
 * 401 and the pod CrashLoopBackOffs rather than failing visibly.
 *
 * <p>The {@code /info} assertion is the one that catches a silent regression. The endpoint is
 * exposed either way; it only returns anything because the Gradle build generates
 * {@code META-INF/build-info.properties}. Remove the {@code springBoot.buildInfo} block and
 * {@code /info} answers 200 with {@code {}} — healthy-looking and useless.
 */
@SpringBootTest
@Testcontainers
class ActuatorEndpointsTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("admin.auth.disabled", () -> "true");
    }

    private final MockMvc mockMvc;

    ActuatorEndpointsTest(@Autowired final WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    @DisplayName("health_should_answer_200_without_a_token_and_report_the_database")
    void healthIsPublicAndCoversTheDatabase() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/health/liveness", "/health/readiness"})
    @DisplayName("probe_groups_should_answer_200_at_the_paths_chart_java_calls")
    void probeGroupsAnswerAtTheChartPaths(final String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("info_should_answer_200_without_a_token_and_carry_build_details")
    void infoIsPublicAndCarriesBuildDetails() throws Exception {
        mockMvc.perform(get("/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.build.name").exists())
                .andExpect(jsonPath("$.build.version").exists())
                .andExpect(jsonPath("$.build.time").exists())
                .andExpect(jsonPath("$.build.group").value("uk.gov.hmcts.cp"));
    }
}
