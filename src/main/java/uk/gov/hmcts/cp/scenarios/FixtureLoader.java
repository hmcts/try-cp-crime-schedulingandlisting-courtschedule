package uk.gov.hmcts.cp.scenarios;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the committed fixtures and checks every one against the published contract.
 *
 * <p>This is the build-time half of the contract gate. It runs with no database, which is what keeps
 * {@code ScenarioCatalogContractTest} fast and makes a drifted fixture fail {@code ./gradlew build}
 * rather than waiting for a deployment. The ingest endpoint applies the same check, through the same
 * {@link ContractValidator}, to recordings arriving at runtime.
 */
@Component
@Slf4j
public class FixtureLoader {

    private static final String DEFINITIONS = "stubs/scenarios.yaml";
    private static final String STUB_DIR = "stubs/";

    private final ContractValidator contractValidator;

    public FixtureLoader(final ContractValidator contractValidator) {
        this.contractValidator = contractValidator;
    }

    /**
     * Reads and validates every committed fixture.
     *
     * @throws IllegalStateException if a fixture is missing, unreadable, duplicated, or does not
     *                               match the contract
     */
    public List<Fixture> load() {
        final ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        final List<Fixture> fixtures = new ArrayList<>();
        final Set<String> seen = new HashSet<>();

        for (final ScenarioDefinition definition : readDefinitions(yaml)) {
            final String body = readBody(definition);
            try {
                contractValidator.validate(definition.status(), body);
            } catch (final ContractViolationException e) {
                throw new IllegalStateException(
                        "Fixture '" + definition.id() + "' (" + definition.body() + ") does not match the "
                                + "published contract. Fix the fixture, or the contract version in "
                                + "build.gradle. " + e.getMessage(), e);
            }
            if (!seen.add(definition.caseUrn())) {
                throw new IllegalStateException("Duplicate case URN in " + DEFINITIONS + ": " + definition.caseUrn());
            }
            fixtures.add(new Fixture(
                    definition.id(), definition.caseUrn(), definition.status(), definition.summary(), body));
        }

        log.info("Validated {} committed fixtures", fixtures.size());
        return List.copyOf(fixtures);
    }

    private static List<ScenarioDefinition> readDefinitions(final ObjectMapper yaml) {
        try (InputStream in = new ClassPathResource(DEFINITIONS).getInputStream()) {
            final ScenarioFile file = yaml.readValue(in, ScenarioFile.class);
            if (file.scenarios() == null || file.scenarios().isEmpty()) {
                throw new IllegalStateException(DEFINITIONS + " defines no scenarios");
            }
            return file.scenarios();
        } catch (final IOException e) {
            throw new IllegalStateException("Could not read " + DEFINITIONS, e);
        }
    }

    private static String readBody(final ScenarioDefinition definition) {
        final String path = STUB_DIR + definition.body();
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (final IOException e) {
            throw new IllegalStateException("Could not read fixture body " + path, e);
        }
    }

    /**
     * Wrapper matching the top-level {@code scenarios:} key of the definitions file.
     */
    private record ScenarioFile(List<ScenarioDefinition> scenarios) {
    }
}
