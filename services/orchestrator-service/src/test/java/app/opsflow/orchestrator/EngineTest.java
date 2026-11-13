package app.opsflow.orchestrator;

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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"opsflow.internal.token=test-internal-token-123", "opsflow.orchestrator.retry-base-millis=20",
        "opsflow.orchestrator.deploy-poll-millis=20", "opsflow.orchestrator.report-sweep-millis=200"})
@AutoConfigureMockMvc
class EngineTest {
    static final String T = "test-internal-token-123";
    static final FakeServices fake;
    static final Path dir;

    static {
        try {
            fake = new FakeServices();
            dir = Files.createTempDirectory("opsflow-workflows");
            Files.writeString(dir.resolve("best-effort.yml"), """
                    id: best-effort
                    name: Best effort
                    order: 5
                    match: {teams: [qa-team]}
                    steps:
                      - type: deployment
                        environment: moon
                        continueOnError: true
                      - type: notify
                        message: "Done with ${key} even though the deploy was refused"
                    """);
            Files.writeString(dir.resolve("broken.yml"), "id: broken\nsteps:\n  - type: teleport\n");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        for (String s : new String[]{"ticket", "notification", "deployment", "tracker"}) r.add("opsflow.services." + s + "-url", fake::url);
        r.add("opsflow.orchestrator.workflow-dir", dir::toString);
    }

    @AfterAll
    static void stop() {
        fake.close();
    }

    @Autowired MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void reset() {
        fake.reset();
    }

    ResultActions startRun(String key, String body) throws Exception {
        var req = post("/runs").header("X-Internal-Token", T).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) req.header("Idempotency-Key", key);
        return mvc.perform(req);
    }

    String start(String body) throws Exception {
        return json.readTree(startRun(null, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    JsonNode run(String id) throws Exception {
        return json.readTree(mvc.perform(get("/runs/" + id).header("X-Internal-Token", T)).andReturn().getResponse().getContentAsString());
    }

    JsonNode await(String id, Predicate<JsonNode> done) throws Exception {
        Instant end = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(end)) {
            JsonNode r = run(id);
            if (done.test(r)) return r;
            Thread.sleep(25);
        }
        throw new AssertionError("run did not reach the expected state: " + run(id));
    }

    JsonNode finished(String id) throws Exception {
        return await(id, r -> !r.get("status").asString().equals("RUNNING") && r.get("reported").asBoolean());
    }

    @Test
    void runsTheDefaultWorkflowAndReportsBack() throws Exception {
        String id = start("{\"ticketId\":7,\"ticketKey\":\"OPS-1007\",\"title\":\"VPN flaps\",\"description\":\"since noon\",\"priority\":\"P2\",\"team\":\"network-team\",\"trigger\":\"auto\"}");
        JsonNode r = finished(id);
        assertThat(r.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(r.get("workflowId").asString()).isEqualTo("incident-basic");
        assertThat(r.get("steps")).hasSize(5).allMatch(s -> s.get("status").asString().equals("SUCCEEDED"));
        assertThat(r.get("steps").get(1).get("reference").asString()).startsWith("INC-");
        assertThat(r.get("steps").get(2).get("reference").asString()).startsWith("DEP-");
        assertThat(r.get("steps").get(3).get("detail").asString()).isEqualTo("Internal diagnostics recorded for OPS-1007");

        var notify = fake.calls("/notifications").getFirst();
        assertThat(notify.key()).isEqualTo(id + ":0:1");
        assertThat(notify.token()).isEqualTo(T);
        assertThat(notify.body()).contains("New P2 ticket OPS-1007 triaged to network-team: VPN flaps");
        assertThat(fake.calls("/issues").getFirst().body()).contains("[P2] VPN flaps").contains("since noon");
        var report = fake.calls("/internal/runs/" + id + "/finished");
        assertThat(report).hasSize(1);
        assertThat(report.getFirst().body()).contains("\"status\":\"COMPLETED\"").contains("\"ticketId\":7");
    }

    @Test
    void retriesAServiceThatIsDownThenCarriesOn() throws Exception {
        fake.force("/notifications", 503, 503);
        String id = start("{\"ticketId\":8,\"ticketKey\":\"OPS-1008\",\"title\":\"Login broken\",\"priority\":\"P3\",\"team\":\"auth-team\"}");
        JsonNode r = finished(id);
        assertThat(r.get("workflowId").asString()).isEqualTo("access-request");
        assertThat(r.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(r.get("steps").get(0).get("attempts").asInt()).isEqualTo(3);
        assertThat(fake.calls("/notifications")).hasSize(3).allMatch(c -> c.key().equals(id + ":0:1"));
    }

    @Test
    void aRefusalFailsAtOnceAndGivingUpIsRecorded() throws Exception {
        fake.force("/issues", 400);
        String id = start("{\"ticketId\":9,\"ticketKey\":\"OPS-1009\",\"title\":\"x\",\"priority\":\"P3\",\"team\":\"auth-team\"}");
        JsonNode r = finished(id);
        assertThat(r.get("status").asString()).isEqualTo("FAILED");
        assertThat(r.get("error").asString()).startsWith("Step 2 (Open a tracker issue) failed: tracker-service answered 400");
        assertThat(r.get("steps").get(1).get("attempts").asInt()).isEqualTo(1);
        assertThat(r.get("steps").get(2).get("status").asString()).isEqualTo("PENDING");

        fake.force("/notifications", 503, 503, 503, 503, 503);
        String gaveUp = start("{\"ticketId\":10,\"ticketKey\":\"OPS-1010\",\"title\":\"y\",\"priority\":\"P3\",\"team\":\"auth-team\"}");
        JsonNode g = finished(gaveUp);
        assertThat(g.get("steps").get(0).get("attempts").asInt()).isEqualTo(3);
        assertThat(g.get("steps").get(0).get("detail").asString()).contains("gave up after 3 attempts");
    }

    @Test
    void aFailedDeploymentCanBeRetriedFromWhereItStopped() throws Exception {
        fake.failDeploys.set(true);
        String id = start("{\"ticketId\":11,\"ticketKey\":\"OPS-1011\",\"title\":\"Ship 2.4\",\"priority\":\"P3\",\"team\":\"devops-team\"}");
        JsonNode r = finished(id);
        assertThat(r.get("workflowId").asString()).isEqualTo("release-request");
        assertThat(r.get("status").asString()).isEqualTo("FAILED");
        assertThat(r.get("error").asString()).contains("Deploy to staging").contains("rolled back");
        int notifications = fake.calls("/notifications").size();

        fake.failDeploys.set(false);
        mvc.perform(post("/runs/" + id + "/retry").header("X-Internal-Token", T)).andExpect(status().isOk()).andExpect(jsonPath("$.attempt").value(2));
        JsonNode again = finished(id);
        assertThat(again.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(fake.calls("/notifications")).hasSize(notifications); // step 1 was not repeated
        assertThat(fake.calls("/deployments").stream().filter(c -> c.method().equals("POST")).map(FakeServices.Call::key))
                .contains(id + ":1:1", id + ":1:2", id + ":2:2");
        mvc.perform(post("/runs/" + id + "/retry").header("X-Internal-Token", T)).andExpect(status().isConflict());
    }

    @Test
    void continueOnErrorLetsTheRunFinishWithAWarning() throws Exception {
        fake.force("/deployments", 400);
        String id = start("{\"ticketId\":12,\"ticketKey\":\"OPS-1012\",\"title\":\"flaky test\",\"priority\":\"P3\",\"team\":\"qa-team\"}");
        JsonNode r = finished(id);
        assertThat(r.get("workflowId").asString()).isEqualTo("best-effort");
        assertThat(r.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(r.get("warnings").asInt()).isEqualTo(1);
        assertThat(r.get("steps").get(0).get("status").asString()).isEqualTo("FAILED");
    }

    @Test
    void keepsReportingUntilTicketServiceAccepts() throws Exception {
        fake.force("/internal/runs", 503, 503);
        String id = start("{\"ticketId\":13,\"ticketKey\":\"OPS-1013\",\"title\":\"z\",\"priority\":\"P3\",\"team\":\"auth-team\"}");
        finished(id);
        assertThat(fake.calls("/internal/runs/" + id).size()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void startsAreIdempotentAndValidated() throws Exception {
        String body = "{\"ticketId\":14,\"ticketKey\":\"OPS-1014\",\"title\":\"q\",\"priority\":\"P1\",\"team\":\"db-team\"}";
        String first = json.readTree(startRun("job-14", body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        startRun("job-14", body).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(first)).andExpect(jsonPath("$.workflowId").value("major-incident"));
        startRun(null, "{\"workflow\":\"nope\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.workflow").exists());
        startRun(null, "{\"workflow\":\"broken\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("workflow_invalid"));
        mvc.perform(post("/orchestrate/start?workflow=incident-basic").header("X-Internal-Token", T)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticketId").doesNotExist());
        mvc.perform(get("/runs?ticketId=14").header("X-Internal-Token", T)).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].steps.length()").value(5));
        mvc.perform(get("/workflows").header("X-Internal-Token", T)).andExpect(jsonPath("$[?(@.id=='broken')].valid").value(false));
        mvc.perform(get("/workflows/select?priority=P2&team=db-team").header("X-Internal-Token", T)).andExpect(jsonPath("$.id").value("db-incident"));
        mvc.perform(get("/runs")).andExpect(status().isUnauthorized());
        finished(first);
    }
}
