package app.opsflow.triage;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** A tiny OpenAI-compatible server that answers whatever {@link #reply} holds. */
final class FakeLlm implements AutoCloseable {
    final AtomicReference<String> reply = new AtomicReference<>("{}");
    final AtomicReference<String> lastRequest = new AtomicReference<>();
    final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final HttpServer server;

    FakeLlm() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            lastRequest.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String content = reply.get().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
            byte[] body = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"}}]}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status.get(), body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
