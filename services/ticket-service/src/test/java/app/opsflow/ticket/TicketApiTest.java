package app.opsflow.ticket;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"opsflow.internal.token=test-internal-token-123", "opsflow.api.token=operator-token-123",
        "opsflow.api.viewer-token=viewer-token-123", "opsflow.jobs.base-backoff-millis=100", "opsflow.jobs.sweep-millis=100"})
@AutoConfigureMockMvc
class TicketApiTest {
    static final FakePlatform fake;

    static {
        try {
            fake = new FakePlatform();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        for (String s : new String[]{"triage", "orchestrator", "notification", "deployment", "tracker"}) r.add("opsflow.services." + s + "-url", fake::url);
    }

    @AfterAll
    static void stop() {
        fake.close();
    }

    @Autowired MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();

    static MockHttpServletRequestBuilder op(MockHttpServletRequestBuilder b) {
        return b.header("Authorization", "Bearer operator-token-123").header("X-Actor", "Asha <script>").contentType(MediaType.APPLICATION_JSON);
    }

    ResultActions create(String body) throws Exception {
        return mvc.perform(op(post("/api/tickets")).content(body));
    }

    JsonNode ticket(String key) throws Exception {
        return json.readTree(mvc.perform(op(get("/api/tickets/" + key))).andReturn().getResponse().getContentAsString());
    }

    JsonNode await(String key, Predicate<JsonNode> ok) throws Exception {
        Instant end = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(end)) {
            JsonNode t = ticket(key);
            if (ok.test(t)) return t;
            Thread.sleep(30);
        }
        throw new AssertionError("ticket never got there: " + ticket(key));
    }

    String created(String body) throws Exception {
        return json.readTree(create(body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("key").asString();
    }

    void finish(JsonNode t, String status, String error) throws Exception {
        String runId = t.get("ticket").get("currentRunId").asString();
        mvc.perform(post("/internal/runs/" + runId + "/finished").header("X-Internal-Token", "test-internal-token-123").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketId\":" + t.get("ticket").get("id") + ",\"status\":\"" + status + "\",\"attempt\":1,\"workflowName\":\"Major incident\",\"error\":\"" + error + "\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void tokensDecideWhatYouCanDo() throws Exception {
        mvc.perform(get("/api/tickets")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message", containsString("Bearer")));
        mvc.perform(get("/api/tickets").header("Authorization", "Bearer nope")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tickets").header("Authorization", "Bearer viewer-token-123")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/api/tickets").header("Authorization", "Bearer viewer-token-123").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(op(get("/api/session"))).andExpect(jsonPath("$.role").value("OPERATOR")).andExpect(jsonPath("$.actor").value("Asha script"));
        mvc.perform(post("/internal/runs/x/finished").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/")).andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void aTicketIsTriagedAndItsWorkflowStartedThenActioned() throws Exception {
        String key = created("{\"title\":\"Orders DB down\",\"description\":\"checkout failing\",\"reporter\":\"Ravi (support)\"}");
        JsonNode t = await(key, x -> x.get("ticket").get("status").asString().equals("IN_PROGRESS"));
        assertThat(t.get("ticket").get("priority").asString()).isEqualTo("P1");
        assertThat(t.get("ticket").get("ownerTeam").asString()).isEqualTo("db-team");
        assertThat(t.get("ticket").get("triageMatched").toString()).contains("down");
        assertThat(t.get("ticket").get("workflowId").asString()).isEqualTo("major-incident");
        assertThat(t.get("events").findValuesAsString("type")).containsSubsequence("CREATED", "TRIAGED", "WORKFLOW_STARTED");
        assertThat(t.get("events").get(0).get("actor").asString()).isEqualTo("Asha script");
        var start = fake.calls("/runs").stream().filter(c -> c.method().equals("POST") && c.body().contains(key)).toList();
        assertThat(start).hasSize(1);
        assertThat(start.getFirst().key()).startsWith("ticket-job-");
        assertThat(start.getFirst().body()).contains("\"trigger\":\"auto\"").contains("\"team\":\"db-team\"");

        finish(t, "COMPLETED", "");
        finish(t, "COMPLETED", ""); // reported twice: recorded once
        JsonNode done = ticket(key);
        assertThat(done.get("ticket").get("status").asString()).isEqualTo("ACTIONED");
        assertThat(done.get("ticket").get("actionedAt").isNull()).isFalse();
        assertThat(done.get("events").findValuesAsString("type").stream().filter("WORKFLOW_COMPLETED"::equals)).hasSize(1);
    }

    @Test
    void waitsForTriageWhileItIsDown() throws Exception {
        fake.force("/triage", 503, 503);
        String key = created("{\"title\":\"Printer jam on floor 3\"}");
        JsonNode t = await(key, x -> x.get("ticket").get("status").asString().equals("IN_PROGRESS"));
        assertThat(t.get("ticket").get("priority").asString()).isEqualTo("P3");
        assertThat(fake.calls("/triage").stream().filter(c -> c.body().contains("Printer jam"))).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void aRefusedTriageIsRecordedOnTheTicket() throws Exception {
        fake.force("/triage", 400);
        String key = created("{\"title\":\"Odd request\"}");
        JsonNode t = await(key, x -> x.get("failedJobs").size() == 1);
        assertThat(t.get("ticket").get("status").asString()).isEqualTo("NEW");
        assertThat(t.get("events").findValuesAsString("message")).anyMatch(m -> m.startsWith("Triage could not be done: triage-service answered 400"));
    }

    @Test
    void handSetPriorityAndWorkflowAreKept() throws Exception {
        String key = created("{\"title\":\"Need release 2.4\",\"priority\":\"P2\",\"workflow\":\"release-request\"}");
        JsonNode t = await(key, x -> x.get("ticket").get("status").asString().equals("IN_PROGRESS"));
        assertThat(t.get("ticket").get("priority").asString()).isEqualTo("P2");
        assertThat(t.get("ticket").get("priorityOverridden").asBoolean()).isTrue();
        assertThat(t.get("ticket").get("workflowId").asString()).isEqualTo("release-request");
        assertThat(t.get("events").findValuesAsString("message")).anyMatch(m -> m.contains("P2 (kept, set by hand)"));
    }

    @Test
    void failedRunsResolveReopenNotesAndOverrides() throws Exception {
        String key = created("{\"title\":\"API down\"}");
        JsonNode t = await(key, x -> x.get("ticket").get("status").asString().equals("IN_PROGRESS"));
        mvc.perform(op(post("/api/tickets/" + key + "/runs")).content("{}")).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("run_in_progress"));
        finish(t, "FAILED", "Step 2 (Open a tracker issue) failed: tracker-service answered 400");
        JsonNode failed = ticket(key);
        assertThat(failed.get("ticket").get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("ticket").get("runError").asString()).contains("Step 2");

        mvc.perform(op(patch("/api/tickets/" + key)).content("{\"priority\":\"P2\",\"ownerTeam\":\"network-team\"}"))
                .andExpect(jsonPath("$.priority").value("P2")).andExpect(jsonPath("$.teamOverridden").value(true));
        mvc.perform(op(patch("/api/tickets/" + key)).content("{\"ownerTeam\":\"Bad Team\"}")).andExpect(status().isBadRequest());
        mvc.perform(op(post("/api/tickets/" + key + "/notes")).content("{\"text\":\"Called the vendor\"}")).andExpect(status().isCreated());
        mvc.perform(op(post("/api/tickets/" + key + "/runs")).content("{\"workflow\":\"release-request\"}")).andExpect(status().isAccepted());
        JsonNode rerun = await(key, x -> x.get("ticket").get("workflowId").asString().equals("release-request"));
        assertThat(rerun.get("ticket").get("status").asString()).isEqualTo("IN_PROGRESS");

        mvc.perform(op(patch("/api/tickets/" + key)).content("{\"status\":\"RESOLVED\"}")).andExpect(jsonPath("$.status").value("RESOLVED"));
        mvc.perform(op(patch("/api/tickets/" + key)).content("{\"status\":\"RESOLVED\"}")).andExpect(status().isConflict());
        JsonNode closed = await(key, x -> x.get("events").findValuesAsString("type").contains("ISSUES_CLOSED"));
        assertThat(fake.calls("/issues/close-for-ticket").stream().map(FakePlatform.Call::body)).anyMatch(b -> b.contains(key));
        assertThat(closed.get("events").findValuesAsString("type")).contains("CHANGED", "NOTE", "RUN_REQUESTED", "RESOLVED");
        mvc.perform(op(post("/api/tickets/" + key + "/runs")).content("{}")).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("resolved"));
        mvc.perform(op(patch("/api/tickets/" + key)).content("{\"status\":\"REOPENED\"}")).andExpect(jsonPath("$.status").value("TRIAGED"));
    }

    @Test
    void validatesSearchesAndKeepsTheOldEndpoints() throws Exception {
        create("{\"title\":\"\",\"priority\":\"P7\",\"workflow\":\"Bad Id\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.title").value("Title is required"))
                .andExpect(jsonPath("$.fields.priority").value("Priority must be P1, P2 or P3"))
                .andExpect(jsonPath("$.fields.workflow").value("Not a workflow id"));
        String key = created("{\"title\":\"VPN 100%_flaky for Mumbai\",\"reporter\":\"Neha\"}");
        mvc.perform(op(get("/api/tickets")).param("q", "100%_")).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].key").value(key));
        mvc.perform(op(get("/api/tickets")).param("q", "neha")).andExpect(jsonPath("$.items[*].key", hasItem(key)));
        mvc.perform(op(get("/api/tickets?size=1&sort=nonsense"))).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(op(get("/api/tickets/" + key.toLowerCase()))).andExpect(jsonPath("$.ticket.key").value(key));
        mvc.perform(op(get("/api/tickets/OPS-1"))).andExpect(status().isNotFound());
        mvc.perform(op(get("/tickets"))).andExpect(jsonPath("$[*].key", hasItem(key)));
        mvc.perform(op(get("/tickets/" + key))).andExpect(jsonPath("$.title").value("VPN 100%_flaky for Mumbai"));
        mvc.perform(op(post("/tickets")).content("{\"title\":\"from an old script\"}")).andExpect(status().isCreated());
        mvc.perform(op(get("/api/stats"))).andExpect(jsonPath("$.total").isNumber()).andExpect(jsonPath("$.createdPerDay").exists())
                .andExpect(jsonPath("$.runs.COMPLETED").value(1));
        mvc.perform(op(get("/api/system"))).andExpect(jsonPath("$.services.length()").value(6)).andExpect(jsonPath("$.services[1].status").value("UP"));
        mvc.perform(op(post("/api/triage/preview")).content("{\"title\":\"db down\"}"))
                .andExpect(jsonPath("$.triage.priority").value("P1")).andExpect(jsonPath("$.workflow.id").value("major-incident"));
        assertThat(fake.calls("/triage").getLast().method()).isEqualTo("POST");
    }
}
