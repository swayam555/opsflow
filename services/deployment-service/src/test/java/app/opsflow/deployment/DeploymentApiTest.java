package app.opsflow.deployment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"opsflow.internal.token=test-internal-token-123", "opsflow.deploy.step-millis=60"})
@AutoConfigureMockMvc
class DeploymentApiTest {
    static final String T = "test-internal-token-123";
    @Autowired MockMvc mvc;
    @Autowired Rollouts rollouts;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void reset() {
        rollouts.failNext.set(0);
    }

    ResultActions deploy(String key, String body) throws Exception {
        var req = post("/deployments").header("X-Internal-Token", T).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) req.header("Idempotency-Key", key);
        return mvc.perform(req);
    }

    JsonNode waitFinished(String id) throws Exception {
        Instant end = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(end)) {
            JsonNode d = json.readTree(mvc.perform(get("/deployments/" + id).header("X-Internal-Token", T)).andReturn().getResponse().getContentAsString());
            String s = d.get("status").asString();
            if (s.equals("SUCCEEDED") || s.equals("FAILED")) return d;
            Thread.sleep(50);
        }
        throw new AssertionError("deployment did not finish");
    }

    @Test
    void rollsOutAndLogsEachStep() throws Exception {
        String body = deploy("run-a:2", "{\"environment\":\"Staging\",\"service\":\"checkout\",\"version\":\"2.4.1\",\"ticketKey\":\"OPS-1001\"}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.key", startsWith("DEP-")))
                .andExpect(jsonPath("$.environment").value("staging"))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).get("id").asString();
        JsonNode done = waitFinished(id);
        assertThat(done.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(done.get("log").asString()).contains("Pulling checkout:2.4.1").contains("Instance 3/3").contains("is live on staging");
        // same key: same deployment, nothing new started
        deploy("run-a:2", "{\"environment\":\"staging\"}").andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/deployments/" + done.get("key").asString()).header("X-Internal-Token", T)).andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void injectedFailureRollsBack() throws Exception {
        mvc.perform(post("/deployments/chaos").header("X-Internal-Token", T).contentType(MediaType.APPLICATION_JSON).content("{\"failNext\":1}"))
                .andExpect(jsonPath("$.failNext").value(1));
        String id = json.readTree(deploy(null, "{\"environment\":\"production\"}").andReturn().getResponse().getContentAsString()).get("id").asString();
        JsonNode done = waitFinished(id);
        assertThat(done.get("status").asString()).isEqualTo("FAILED");
        assertThat(done.get("failureReason").asString()).contains("rolled back");
        assertThat(rollouts.failNext.get()).isZero();
        String next = json.readTree(deploy(null, "{\"environment\":\"production\"}").andReturn().getResponse().getContentAsString()).get("id").asString();
        assertThat(waitFinished(next).get("status").asString()).isEqualTo("SUCCEEDED");
    }

    @Test
    void refusesUnknownAndFrozenEnvironments() throws Exception {
        deploy(null, "{\"environment\":\"moon\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.environment", containsString("dev, staging, production")));
        deploy(null, "{\"environment\":\"dev\",\"service\":\"bad name!\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.service").exists());
        mvc.perform(put("/deployments/freezes/production").header("X-Internal-Token", T).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Diwali sale change freeze\"}")).andExpect(jsonPath("$.frozen[0].environment").value("production"));
        deploy(null, "{\"environment\":\"production\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("environment_frozen"))
                .andExpect(jsonPath("$.message", containsString("Diwali")));
        mvc.perform(delete("/deployments/freezes/production").header("X-Internal-Token", T)).andExpect(jsonPath("$.frozen").isEmpty());
        mvc.perform(post("/deployments/chaos").header("X-Internal-Token", T).contentType(MediaType.APPLICATION_JSON).content("{\"failNext\":99}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listsAndKeepsTheOldEndpoint() throws Exception {
        mvc.perform(post("/deploy").header("X-Internal-Token", T).contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"dev\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.environment").value("dev"));
        mvc.perform(get("/deployments?environment=dev").header("X-Internal-Token", T)).andExpect(jsonPath("$.items[0].environment").value("dev"));
        mvc.perform(get("/deployments/DEP-1").header("X-Internal-Token", T)).andExpect(status().isNotFound());
        mvc.perform(get("/deployments")).andExpect(status().isUnauthorized());
    }
}
