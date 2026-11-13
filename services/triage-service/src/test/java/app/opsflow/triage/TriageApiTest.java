package app.opsflow.triage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "opsflow.internal.token=test-internal-token-123")
@AutoConfigureMockMvc
class TriageApiTest {
    static final String T = "test-internal-token-123";
    @Autowired MockMvc mvc;

    @Test
    void needsTheInternalToken() throws Exception {
        mvc.perform(post("/triage").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("unauthorized"));
        mvc.perform(post("/triage").header("X-Internal-Token", "wrong").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void classifiesWithRules() throws Exception {
        mvc.perform(post("/triage").header("X-Internal-Token", T).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketId\":\"42\",\"title\":\"VPN unreachable\",\"description\":\"urgent, whole office offline\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketId").value("42"))
                .andExpect(jsonPath("$.priority").value("P1"))
                .andExpect(jsonPath("$.ownerTeam").value("network-team"))
                .andExpect(jsonPath("$.source").value("rules"))
                .andExpect(jsonPath("$.matched", hasItem("vpn")));
    }

    @Test
    void validates() throws Exception {
        mvc.perform(post("/triage").header("X-Internal-Token", T).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.titleOrDescription").value("Give a title or a description"));
        mvc.perform(post("/triage").header("X-Internal-Token", T).contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("malformed_request"));
    }

    @Test
    void describesRules() throws Exception {
        mvc.perform(get("/triage/rules").header("X-Internal-Token", T))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priorities[0].level").value("P1"))
                .andExpect(jsonPath("$.defaultTeam").value("general-support"))
                .andExpect(jsonPath("$.llm.enabled").value(false));
        mvc.perform(get("/triage/health").header("X-Internal-Token", T)).andExpect(jsonPath("$.status").value("UP"));
    }
}
