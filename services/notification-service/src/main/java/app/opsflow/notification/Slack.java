package app.opsflow.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/** Posts to a Slack incoming webhook when one is configured. */
@Component
public class Slack {
    private final String webhook;
    private final RestClient rest;

    public Slack(@Value("${opsflow.slack.webhook-url:}") String webhook) {
        this.webhook = webhook == null ? "" : webhook.trim();
        var f = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        f.setReadTimeout(Duration.ofSeconds(6));
        this.rest = RestClient.builder().requestFactory(f).build();
    }

    public boolean configured() {
        return !webhook.isEmpty();
    }

    /** Throws when Slack does not accept the message. */
    public void post(String channel, String text) {
        rest.post().uri(webhook).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("text", "*[" + channel + "]* " + text)).retrieve().toBodilessEntity();
    }
}
