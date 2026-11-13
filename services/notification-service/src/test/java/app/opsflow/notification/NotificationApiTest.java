package app.opsflow.notification;

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
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "opsflow.internal.token=test-internal-token-123")
@AutoConfigureMockMvc
class NotificationApiTest {
    static final String T = "test-internal-token-123";
    static final List<String> posted = new CopyOnWriteArrayList<>();
    static final AtomicInteger slackStatus = new AtomicInteger(200);
    static final HttpServer slack;

    static {
        try {
            slack = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        slack.createContext("/hook", ex -> {
            posted.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(slackStatus.get(), -1);
            ex.close();
        });
        slack.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("opsflow.slack.webhook-url", () -> "http://127.0.0.1:" + slack.getAddress().getPort() + "/hook");
    }

    @AfterAll
    static void stop() {
        slack.stop(0);
    }

    @Autowired MockMvc mvc;

    ResultActions send(String key, String body) throws Exception {
        var req = post("/notifications").header("X-Internal-Token", T).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) req.header("Idempotency-Key", key);
        return mvc.perform(req);
    }

    @Test
    void postsToSlackOncePerKey() throws Exception {
        slackStatus.set(200);
        int before = posted.size();
        String body = "{\"team\":\"db-team\",\"message\":\"OPS-1001 P1: Orders DB down\",\"ticketKey\":\"OPS-1001\"}";
        send("run-1:0", body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.channel").value("#db-team"));
        send("run-1:0", body).andExpect(status().isOk()).andExpect(jsonPath("$.attempts").value(1));
        assertThat(posted.size() - before).isEqualTo(1);
        assertThat(posted.getLast()).contains("[#db-team]").contains("Orders DB down");
    }

    @Test
    void aFailedPostIs503AndTheRetryTriesAgain() throws Exception {
        slackStatus.set(500);
        String body = "{\"team\":\"auth-team\",\"channel\":\"#oncall\",\"message\":\"Login errors\"}";
        send("run-2:0", body).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value("FAILED"));
        slackStatus.set(200);
        send("run-2:0", body).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SENT")).andExpect(jsonPath("$.attempts").value(2));
    }

    @Test
    void validatesAndProtects() throws Exception {
        send(null, "{\"team\":\"\",\"message\":\"\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.team").value("Team is required"))
                .andExpect(jsonPath("$.fields.message").value("Message is required"));
        mvc.perform(post("/notifications").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/notifications/nope").header("X-Internal-Token", T)).andExpect(status().isNotFound());
    }

    @Test
    void listsWithFilters() throws Exception {
        slackStatus.set(200);
        send(null, "{\"team\":\"network-team\",\"message\":\"VPN flapping 100%_ok\",\"ticketKey\":\"OPS-77\"}").andExpect(status().isCreated());
        mvc.perform(get("/notifications?team=network-team").header("X-Internal-Token", T)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/notifications?q=vpn").header("X-Internal-Token", T)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/notifications").param("q", "100%_").header("X-Internal-Token", T))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].ticketKey").value("OPS-77"));
        mvc.perform(get("/notifications?ticket=OPS-77&size=500").header("X-Internal-Token", T))
                .andExpect(jsonPath("$.size").value(100));
        mvc.perform(get("/notifications/settings").header("X-Internal-Token", T)).andExpect(jsonPath("$.slackConfigured").value(true));
    }

    @Test
    void legacyNotifyStillWorks() throws Exception {
        slackStatus.set(200);
        mvc.perform(post("/notify").header("X-Internal-Token", T).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hello\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.team").value("general-support"));
    }
}
