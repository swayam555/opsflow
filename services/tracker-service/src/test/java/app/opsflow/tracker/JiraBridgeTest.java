package app.opsflow.tracker;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"opsflow.internal.token=test-internal-token-123", "opsflow.jira.email=bot@example.com",
        "opsflow.jira.api-token=jira-token", "opsflow.jira.project=OPS"})
@AutoConfigureMockMvc
class JiraBridgeTest {
    static final List<String> requests = new CopyOnWriteArrayList<>();
    static final List<String> auth = new CopyOnWriteArrayList<>();
    static final AtomicInteger answer = new AtomicInteger(201);
    static final HttpServer jira;

    static {
        try {
            jira = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        jira.createContext("/rest/api/3/issue", ex -> {
            requests.add(ex.getRequestURI().getPath() + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.add(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] body = (answer.get() == 201 ? "{\"id\":\"10001\",\"key\":\"OPS-7\"}" : "{\"errorMessages\":[\"project is required\"]}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(ex.getRequestURI().getPath().endsWith("/comment") ? 201 : answer.get(), body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        jira.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("opsflow.jira.base-url", () -> "http://127.0.0.1:" + jira.getAddress().getPort() + "/");
    }

    @AfterAll
    static void stop() {
        jira.stop(0);
    }

    @Autowired MockMvc mvc;

    @Test
    void createsInJiraAndLinksIt() throws Exception {
        answer.set(201);
        mvc.perform(post("/issues").header("X-Internal-Token", "test-internal-token-123").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"VPN down\",\"priority\":\"P1\",\"team\":\"network-team\",\"ticketKey\":\"OPS-9\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.externalKey").value("OPS-7"))
                .andExpect(jsonPath("$.externalUrl", endsWith("/browse/OPS-7")));
        assertThat(requests.getLast()).contains("\"key\":\"OPS\"").contains("VPN down").contains("network-team").contains("\"name\":\"Task\"");
        assertThat(auth.getLast()).isEqualTo("Basic " + java.util.Base64.getEncoder().encodeToString("bot@example.com:jira-token".getBytes()));
        mvc.perform(post("/issues/close-for-ticket").header("X-Internal-Token", "test-internal-token-123").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ticketKey\":\"OPS-9\"}")).andExpect(jsonPath("$.closed.length()").value(1));
        assertThat(requests.getLast()).startsWith("/rest/api/3/issue/OPS-7/comment").contains("Resolved in Opsflow");
    }

    @Test
    void aJiraConfigErrorIsNotRetryableButAnOutageIs() throws Exception {
        answer.set(400);
        mvc.perform(post("/issues").header("X-Internal-Token", "test-internal-token-123").contentType(MediaType.APPLICATION_JSON)
                .content("{\"summary\":\"x\"}")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("jira_rejected"));
        answer.set(503);
        mvc.perform(post("/issues").header("X-Internal-Token", "test-internal-token-123").contentType(MediaType.APPLICATION_JSON)
                .content("{\"summary\":\"x\"}")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("jira_unavailable"));
    }
}
