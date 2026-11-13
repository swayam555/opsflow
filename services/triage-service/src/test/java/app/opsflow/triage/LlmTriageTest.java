package app.opsflow.triage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"opsflow.internal.token=test-internal-token-123", "opsflow.llm.api-key=fake-key"})
@AutoConfigureMockMvc
class LlmTriageTest {
    static final FakeLlm llm;

    static {
        try {
            llm = new FakeLlm();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("opsflow.llm.base-url", llm::baseUrl);
    }

    @AfterAll
    static void stop() {
        llm.close();
    }

    @Autowired MockMvc mvc;

    @BeforeEach
    void reset() {
        llm.status.set(200);
    }

    private org.springframework.test.web.servlet.ResultActions triage(String body) throws Exception {
        return mvc.perform(post("/triage").header("X-Internal-Token", "test-internal-token-123")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void usesTheModelWhenItAnswersSensibly() throws Exception {
        llm.reply.set("```json\n{\"priority\":\"p2\",\"ownerTeam\":\"DB-team\",\"reason\":\"Slow reports point at the database.\"}\n```");
        triage("{\"title\":\"Reports are slow\",\"description\":\"monthly report takes 3 minutes\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("llm"))
                .andExpect(jsonPath("$.priority").value("P2"))
                .andExpect(jsonPath("$.ownerTeam").value("db-team"))
                .andExpect(jsonPath("$.reason").value("Slow reports point at the database."));
        assertThat(llm.lastRequest.get()).doesNotContain("fake-key") // the key goes in the header, never the body
                .contains("Reports are slow").contains("general-support");
    }

    @Test
    void fallsBackWhenTheModelInventsATeam() throws Exception {
        llm.reply.set("{\"priority\":\"P1\",\"ownerTeam\":\"marketing\",\"reason\":\"x\"}");
        triage("{\"title\":\"Checkout down\"}")
                .andExpect(jsonPath("$.source").value("rules"))
                .andExpect(jsonPath("$.priority").value("P1"))
                .andExpect(jsonPath("$.note", containsString("unknown team 'marketing'")));
    }

    @Test
    void fallsBackWhenTheServiceFails() throws Exception {
        llm.status.set(500);
        triage("{\"title\":\"Password reset email never arrives\"}")
                .andExpect(jsonPath("$.source").value("rules"))
                .andExpect(jsonPath("$.ownerTeam").value("auth-team"))
                .andExpect(jsonPath("$.note", containsString("AI service failed")));
    }

    @Test
    void rulesModeSkipsTheModel() throws Exception {
        llm.reply.set("{\"priority\":\"P1\",\"ownerTeam\":\"db-team\",\"reason\":\"x\"}");
        mvc.perform(post("/triage?mode=rules").header("X-Internal-Token", "test-internal-token-123")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"printer jam\"}"))
                .andExpect(jsonPath("$.source").value("rules")).andExpect(jsonPath("$.priority").value("P3"));
    }
}
