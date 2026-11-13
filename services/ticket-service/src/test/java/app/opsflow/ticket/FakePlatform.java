package app.opsflow.ticket;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Fake triage, orchestrator, tracker, deployment and notification services for ticket-service tests. */
final class FakePlatform implements AutoCloseable {
    record Call(String method, String path, String key, String body) {}

    final List<Call> calls = new CopyOnWriteArrayList<>();
    final Map<String, Deque<Integer>> forced = new ConcurrentHashMap<>();
    private final HttpServer server;

    FakePlatform() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void force(String path, Integer... statuses) {
        forced.computeIfAbsent(path, p -> new ArrayDeque<>()).addAll(List.of(statuses));
    }

    List<Call> calls(String path) {
        return calls.stream().filter(c -> c.path().equals(path)).toList();
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        calls.add(new Call(ex.getRequestMethod(), path, ex.getRequestHeaders().getFirst("Idempotency-Key"), body));
        if (!"test-internal-token-123".equals(ex.getRequestHeaders().getFirst("X-Internal-Token"))) {
            send(ex, 401, "{\"message\":\"no token\"}");
            return;
        }
        Deque<Integer> f = forced.get(path);
        Integer status = f == null ? null : f.poll();
        if (status != null) {
            send(ex, status, "{\"status\":" + status + ",\"message\":\"forced " + status + "\"}");
            return;
        }
        String lower = body.toLowerCase();
        switch (path) {
            case "/triage" -> send(ex, 200, lower.contains("down")
                    ? "{\"priority\":\"P1\",\"ownerTeam\":\"db-team\",\"reason\":\"P1: outage wording\",\"confidence\":\"high\",\"source\":\"rules\",\"matched\":[\"down\",\"db\"]}"
                    : "{\"priority\":\"P3\",\"ownerTeam\":\"general-support\",\"reason\":\"routine\",\"confidence\":\"low\",\"source\":\"rules\",\"matched\":[]}");
            case "/runs" -> {
                if (ex.getRequestMethod().equals("POST")) {
                    String wf = lower.contains("\"workflow\":\"release-request\"") ? "release-request" : lower.contains("p1") ? "major-incident" : "incident-basic";
                    send(ex, 201, "{\"id\":\"" + UUID.randomUUID() + "\",\"workflowId\":\"" + wf + "\",\"workflowName\":\"" + wf + " name\",\"status\":\"RUNNING\",\"steps\":[{},{},{}]}");
                } else {
                    send(ex, 200, "{\"items\":[],\"page\":0,\"size\":20,\"total\":0,\"totalPages\":0}");
                }
            }
            case "/runs/stats" -> send(ex, 200, "{\"RUNNING\":0,\"COMPLETED\":1,\"FAILED\":0}");
            case "/workflows" -> send(ex, 200, "[{\"id\":\"incident-basic\"}]");
            case "/workflows/select" -> send(ex, 200, "{\"id\":\"major-incident\",\"name\":\"Major incident (P1)\"}");
            case "/issues/close-for-ticket" -> send(ex, 200, "{\"closed\":[\"INC-101\"]}");
            case "/actuator/health" -> send(ex, 200, "{\"status\":\"UP\"}");
            case "/deployments/settings" -> send(ex, 200, "{\"environments\":[\"dev\"],\"frozen\":[],\"failNext\":0}");
            case "/notifications/settings" -> send(ex, 200, "{\"slackConfigured\":false}");
            case "/issues/settings" -> send(ex, 200, "{\"jiraConfigured\":false}");
            case "/triage/rules" -> send(ex, 200, "{\"llm\":{\"enabled\":false}}");
            default -> send(ex, 404, "{\"message\":\"fake: no " + path + "\"}");
        }
    }

    private static void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
