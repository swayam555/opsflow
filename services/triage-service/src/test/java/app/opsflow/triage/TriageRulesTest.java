package app.opsflow.triage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TriageRulesTest {
    static TriageRules rules;

    @BeforeAll
    static void load() throws IOException {
        rules = TriageRules.load(new ClassPathResource("triage-rules.yml").getInputStream());
    }

    @Test
    void outageOnTheDatabaseIsP1ForTheDbTeam() {
        Verdict v = rules.classify("7", "Orders DB down", "Postgres is not responding since 10:02, checkout fails");
        assertThat(v.priority()).isEqualTo("P1");
        assertThat(v.ownerTeam()).isEqualTo("db-team");
        assertThat(v.confidence()).isEqualTo("high");
        assertThat(v.matched()).contains("down", "not responding", "db", "postgres");
        assertThat(v.reason()).startsWith("P1: outage or urgent wording").contains("db-team from");
        assertThat(v.ticketId()).isEqualTo("7");
    }

    @Test
    void errorsAreP2AndTheTitleCounts() {
        Verdict v = rules.classify(null, "Login page throws an exception", "");
        assertThat(v.priority()).isEqualTo("P2");
        assertThat(v.ownerTeam()).isEqualTo("auth-team");
    }

    @Test
    void wholeWordsOnly() {
        // "ci" inside "decision", "db" inside "feedback", "down" inside "download" must not count
        Verdict v = rules.classify(null, "Decision on feedback form", "Please add a download button");
        assertThat(v.priority()).isEqualTo("P3");
        assertThat(v.ownerTeam()).isEqualTo("general-support");
        assertThat(v.confidence()).isEqualTo("low");
        assertThat(v.matched()).isEmpty();
    }

    @Test
    void phrasesMatchAcrossExtraSpaces() {
        Verdict v = rules.classify(null, "API not\n  responding", null);
        assertThat(v.priority()).isEqualTo("P1");
    }

    @Test
    void theTeamWithMostMatchesWinsAndTiesGoToTheFirstListed() {
        assertThat(rules.classify(null, "Deploy pipeline broke the database migration", "release blocked").ownerTeam())
                .isEqualTo("devops-team"); // deploy, pipeline, release (3) vs database, migration (2)
        assertThat(rules.classify(null, "dns and db", null).ownerTeam()).isEqualTo("db-team"); // 1:1, db-team listed first
    }

    @Test
    void p1BeatsP2WhenBothAppear() {
        assertThat(rules.classify(null, "Errors everywhere, site is down", null).priority()).isEqualTo("P1");
    }

    @Test
    void customRulesAreValidated() {
        assertThatThrownBy(() -> TriageRules.load(new ByteArrayInputStream("teams: []".getBytes(StandardCharsets.UTF_8))))
                .hasMessageContaining("priorities");
        String yaml = """
                priorities: [{level: SEV1, reason: fire, keywords: [fire]}]
                default-priority: SEV3
                teams: [{team: facilities, keywords: [printer]}]
                default-team: helpdesk
                """;
        try {
            TriageRules custom = TriageRules.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
            assertThat(custom.classify(null, "Printer on fire", null)).extracting(Verdict::priority, Verdict::ownerTeam).containsExactly("SEV1", "facilities");
            assertThat(custom.priorityLevels()).containsExactly("SEV1", "SEV3");
            assertThat(custom.teamNames()).containsExactly("facilities", "helpdesk");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
