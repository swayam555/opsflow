package app.opsflow.it;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs against the compose stack: OPSFLOW_URL (default http://localhost:8090) and OPSFLOW_API_TOKEN. */
class OpsflowEndToEndTest {
    static final String BASE = System.getenv().getOrDefault("OPSFLOW_URL", "http://localhost:8090");
    static final String TOKEN = System.getenv().getOrDefault("OPSFLOW_API_TOKEN", "opsflow-dev-token");
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final ObjectMapper json = new ObjectMapper();
    final String tag = Long.toString(System.nanoTime(), 36);

    record Res(int status, JsonNode body) {}

    Res call(String method, String path, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + TOKEN).header("X-Actor", "e2e");
        b = body == null ? b.method(method, HttpRequest.BodyPublishers.noBody())
                : b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body().isBlank() ? null : json.readTree(r.body()));
    }

    JsonNode await(String what, String path, Predicate<JsonNode> ok, int seconds) throws Exception {
        Instant end = Instant.now().plusSeconds(seconds);
        JsonNode last = null;
        while (Instant.now().isBefore(end)) {
            last = call("GET", path, null).body();
            if (ok.test(last)) return last;
            Thread.sleep(400);
        }
        throw new AssertionError(what + " did not happen in " + seconds + " s; last: " + last);
    }

    @AfterEach
    void cleanUp() throws Exception {
        call("POST", "/api/system/chaos", "{\"failNext\":0}");
        call("DELETE", "/api/system/freezes/staging", null);
    }

    @Test
    void aP1TicketGoesThroughEveryService() throws Exception {
        Res created = call("POST", "/api/tickets", "{\"title\":\"Orders database down " + tag + "\",\"description\":\"Postgres not responding, checkout fails\",\"reporter\":\"e2e\"}");
        assertEquals(201, created.status());
        String key = created.body().get("key").asString();

        JsonNode done = await("major incident workflow", "/api/tickets/" + key, t -> t.get("ticket").get("status").asString().equals("ACTIONED"), 60);
        JsonNode t = done.get("ticket");
        assertEquals("P1", t.get("priority").asString());
        assertEquals("db-team", t.get("ownerTeam").asString());
        assertEquals("major-incident", t.get("workflowId").asString());

        JsonNode run = call("GET", "/api/tickets/" + key + "/runs", null).body().get("items").get(0);
        assertEquals("COMPLETED", run.get("status").asString());
        run.get("steps").forEach(s -> assertEquals("SUCCEEDED", s.get("status").asString(), s.get("label").asString()));

        JsonNode notes = call("GET", "/api/activity/notifications?ticket=" + key, null).body();
        assertEquals(2, notes.get("total").asInt(), "team + incident bridge");
        JsonNode issues = call("GET", "/api/activity/issues?ticket=" + key, null).body();
        assertEquals(1, issues.get("total").asInt());
        String issue = issues.get("items").get(0).get("key").asString();

        assertEquals(200, call("PATCH", "/api/tickets/" + key, "{\"status\":\"RESOLVED\"}").status());
        await("tracker issue closed", "/api/activity/issues?ticket=" + key, n -> n.get("items").get(0).get("status").asString().equals("DONE"), 20);
        JsonNode events = call("GET", "/api/tickets/" + key, null).body().get("events");
        assertTrue(events.toString().contains("Closed tracker issue(s) " + issue), events.toString());
    }

    @Test
    void aFailedDeploymentFailsTheWorkflowAndARetryFinishesIt() throws Exception {
        assertEquals(200, call("POST", "/api/system/chaos", "{\"failNext\":1}").status());
        String key = call("POST", "/api/tickets", "{\"title\":\"Release checkout 2.4 " + tag + "\",\"description\":\"ship the new build\",\"priority\":\"P3\",\"workflow\":\"release-request\"}")
                .body().get("key").asString();
        JsonNode failed = await("workflow failure", "/api/tickets/" + key, t -> t.get("ticket").get("status").asString().equals("FAILED"), 60);
        assertTrue(failed.get("ticket").get("runError").asString().contains("rolled back"), failed.toString());
        String runId = failed.get("ticket").get("currentRunId").asString();

        assertEquals(200, call("POST", "/api/runs/" + runId + "/retry", null).status());
        JsonNode ok = await("retry to finish", "/api/tickets/" + key, t -> t.get("ticket").get("status").asString().equals("ACTIONED"), 60);
        assertEquals(runId, ok.get("ticket").get("currentRunId").asString());
        JsonNode run = call("GET", "/api/runs/" + runId, null).body();
        assertEquals(2, run.get("attempt").asInt());
        JsonNode deploys = call("GET", "/api/activity/deployments?ticket=" + key, null).body();
        assertEquals(3, deploys.get("total").asInt(), "staging failed, staging again, production");
    }

    @Test
    void aFrozenEnvironmentStopsTheWorkflowWithoutRetrying() throws Exception {
        assertEquals(200, call("PUT", "/api/system/freezes/staging", "{\"reason\":\"e2e freeze\"}").status());
        String key = call("POST", "/api/tickets", "{\"title\":\"Network blip " + tag + "\",\"description\":\"vpn errors\",\"workflow\":\"incident-basic\"}").body().get("key").asString();
        JsonNode failed = await("frozen deploy", "/api/tickets/" + key, t -> t.get("ticket").get("status").asString().equals("FAILED"), 60);
        assertTrue(failed.get("ticket").get("runError").asString().contains("frozen"), failed.toString());
        JsonNode run = call("GET", "/api/tickets/" + key + "/runs", null).body().get("items").get(0);
        assertEquals(1, run.get("steps").get(2).get("attempts").asInt(), "a refusal is not retried");
        assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(BASE + "/api/tickets")).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
    }
}
