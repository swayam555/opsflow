package app.opsflow.tracker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "opsflow.internal.token=test-internal-token-123")
@AutoConfigureMockMvc
class TrackerApiTest {
    static final String T = "test-internal-token-123";
    @Autowired MockMvc mvc;

    ResultActions create(String key, String body) throws Exception {
        var req = post("/issues").header("X-Internal-Token", T).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) req.header("Idempotency-Key", key);
        return mvc.perform(req);
    }

    @Test
    void createsLocalIssuesOncePerKey() throws Exception {
        String body = "{\"summary\":\"[P1] Orders DB down\",\"description\":\"Postgres not responding\",\"priority\":\"P1\",\"team\":\"db-team\",\"ticketKey\":\"OPS-1001\"}";
        String key = com.jayway.jsonpath.JsonPath.read(create("run-1:1", body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.key", matchesPattern("INC-\\d+")))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.externalKey").doesNotExist())
                .andReturn().getResponse().getContentAsString(), "$.key");
        create("run-1:1", body).andExpect(status().isOk()).andExpect(jsonPath("$.key").value(key));
        mvc.perform(get("/issues/" + key.toLowerCase()).header("X-Internal-Token", T)).andExpect(jsonPath("$.priority").value("P1"));
    }

    @Test
    void closesAllOpenIssuesOfATicket() throws Exception {
        create(null, "{\"summary\":\"a\",\"ticketKey\":\"OPS-2002\"}").andExpect(status().isCreated());
        create(null, "{\"summary\":\"b\",\"ticketKey\":\"OPS-2002\"}").andExpect(status().isCreated());
        mvc.perform(post("/issues/close-for-ticket").header("X-Internal-Token", T).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketKey\":\"OPS-2002\",\"note\":\"fixed\"}"))
                .andExpect(jsonPath("$.closed.length()").value(2));
        mvc.perform(get("/issues?ticket=OPS-2002&status=OPEN").header("X-Internal-Token", T)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/issues?ticket=OPS-2002&status=DONE").header("X-Internal-Token", T)).andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void validatesAndSearches() throws Exception {
        create(null, "{\"summary\":\"\",\"priority\":\"P9\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.summary").value("Summary is required"))
                .andExpect(jsonPath("$.fields.priority").value("Priority must be P1, P2 or P3"));
        mvc.perform(post("/jira/create").header("X-Internal-Token", T).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Legacy VPN thing\",\"description\":\"x\"}")).andExpect(status().isCreated()).andExpect(jsonPath("$.priority").value("P3"));
        mvc.perform(get("/issues").param("q", "legacy vpn").header("X-Internal-Token", T)).andExpect(jsonPath("$.items[*].summary", hasItem("Legacy VPN thing")));
        mvc.perform(get("/issues/settings").header("X-Internal-Token", T)).andExpect(jsonPath("$.jiraConfigured").value(false));
        mvc.perform(get("/issues/INC-1").header("X-Internal-Token", T)).andExpect(status().isNotFound());
    }
}
