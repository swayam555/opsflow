package app.opsflow.orchestrator;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowCatalogTest {
    static Workflow parse(String yaml) {
        return WorkflowCatalog.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test.yml");
    }

    @Test
    void picksTheMostSpecificTemplate() throws IOException {
        WorkflowCatalog c = new WorkflowCatalog("", "incident-basic");
        assertThat(c.all()).extracting(Workflow::id).containsExactly("major-incident", "db-incident", "release-request", "access-request", "incident-basic");
        assertThat(c.all()).allMatch(Workflow::valid);
        assertThat(c.select("P1", "auth-team").orElseThrow().id()).isEqualTo("major-incident");
        assertThat(c.select("P2", "db-team").orElseThrow().id()).isEqualTo("db-incident");
        assertThat(c.select("P3", "devops-team").orElseThrow().id()).isEqualTo("release-request");
        assertThat(c.select("P3", "auth-team").orElseThrow().id()).isEqualTo("access-request");
        assertThat(c.select("P2", "network-team").orElseThrow().id()).isEqualTo("incident-basic");
        assertThat(c.select(null, null).orElseThrow().id()).isEqualTo("incident-basic");
    }

    @Test
    void reportsEveryProblemInABrokenFile() {
        Workflow w = parse("""
                id: Bad_ID
                steps:
                  - type: teleport
                  - type: deployment
                  - type: notify
                  - type: log
                    message: ok
                    retries: 9
                """);
        assertThat(w.valid()).isFalse();
        assertThat(w.errors()).anyMatch(e -> e.contains("lower-case"))
                .anyMatch(e -> e.contains("unknown type 'teleport'"))
                .anyMatch(e -> e.contains("needs an 'environment'"))
                .anyMatch(e -> e.contains("notify step needs a 'message'"))
                .anyMatch(e -> e.contains("'retries'"));
        assertThat(parse("steps: [unclosed").errors()).anyMatch(e -> e.startsWith("Not valid YAML"));
        assertThat(parse("id: empty\nsteps: []").errors()).contains("'steps' must be a non-empty list");
    }

    @Test
    void fillsVariables() {
        assertThat(StepRunner.fill("${priority} ${key}: ${title} ${nope} $5", java.util.Map.of("priority", "P1", "key", "OPS-1", "title", "a$b\\c")))
                .isEqualTo("P1 OPS-1: a$b\\c ${nope} $5");
        assertThat(Workflow.label(java.util.Map.of("type", "deployment", "environment", "staging"))).isEqualTo("Deploy to staging");
    }
}
