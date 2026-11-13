package app.opsflow.orchestrator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Stands in for notification, tracker, deployment and ticket services in one HTTP server. */
final class FakeServices implements AutoCloseable {
    record Call(String method, String path, String key, String token, String body) {}

    final List<Call> calls = new CopyOnWriteArrayList<>();
    /** Status codes to answer next on a path (e.g. "/notifications" -> 503, 503); afterwards the normal answer. */
    final Map<String, Deque<Integer>> forced = new ConcurrentHashMap<>();
    final AtomicBoolean failDeploys = new AtomicBoolean();
    private final Map<String, String> deployState = new ConcurrentHashMap<>();
    private final Map<String, String> byKey = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger(1000);
    private final HttpServer server;

    FakeServices() throws IOException {
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

    List<Call> calls(String pathPrefix) {
        return calls.stream().filter(c -> c.path().startsWith(pathPrefix)).toList();
    }

    void reset() {
        calls.clear();
        forced.clear();
        failDeploys.set(false);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
        calls.add(new Call(ex.getRequestMethod(), path, key, ex.getRequestHeaders().getFirst("X-Internal-Token"), body));
        String forcedKey = path.startsWith("/internal/runs/") ? "/internal/runs" : path;
        Deque<Integer> f = forced.get(forcedKey);
        Integer status = f == null ? null : f.poll();
        if (status != null) {
            send(ex, status, "{\"status\":" + status + ",\"error\":\"forced\",\"message\":\"forced " + status + "\"}");
            return;
        }
        int n = seq.incrementAndGet();
        switch (path) {
            case "/notifications" -> send(ex, 201, "{\"id\":\"n-" + n + "\",\"channel\":\"#team\",\"status\":\"LOGGED\"}");
            case "/issues" -> send(ex, 201, "{\"key\":\"INC-" + n + "\",\"status\":\"OPEN\"}");
            case "/deployments" -> {
                String id = key != null && byKey.containsKey(key) ? byKey.get(key) : "d-" + n;
                if (key != null) byKey.putIfAbsent(key, id);
                deployState.putIfAbsent(id, failDeploys.get() ? "FAILED" : "SUCCEEDED");
                send(ex, 202, "{\"id\":\"" + id + "\",\"key\":\"DEP-" + id.substring(2) + "\",\"environment\":\"staging\",\"status\":\"QUEUED\"}");
            }
            default -> {
                if (path.startsWith("/deployments/")) {
                    String id = path.substring("/deployments/".length());
                    String s = deployState.getOrDefault(id, "SUCCEEDED");
                    send(ex, 200, "{\"id\":\"" + id + "\",\"key\":\"DEP-" + id.substring(2) + "\",\"environment\":\"staging\",\"status\":\"" + s
                            + "\",\"failureReason\":\"Health check failed on instance 2 of 3; rolled back\"}");
                } else if (path.startsWith("/internal/runs/")) {
                    ex.sendResponseHeaders(204, -1);
                    ex.close();
                } else {
                    send(ex, 404, "{\"message\":\"no such fake endpoint\"}");
                }
            }
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
