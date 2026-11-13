package app.opsflow.triage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Optional AI triage through any OpenAI-compatible chat API (Groq by default). The model may only answer with
 * the priorities and teams the rules know; anything else, or any failure, falls back to the rules.
 */
@Component
public class LlmTriage {
    private static final Logger log = LoggerFactory.getLogger(LlmTriage.class);
    private final String apiKey;
    private final String model;
    private final RestClient rest;
    private final ObjectMapper json = new ObjectMapper();

    public LlmTriage(@Value("${opsflow.llm.api-key:}") String apiKey,
                     @Value("${opsflow.llm.base-url}") String baseUrl,
                     @Value("${opsflow.llm.model}") String model,
                     @Value("${opsflow.llm.timeout-seconds:12}") int timeoutSeconds) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build());
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.rest = RestClient.builder().baseUrl(baseUrl.replaceAll("/+$", "")).requestFactory(factory).build();
    }

    public boolean enabled() {
        return !apiKey.isEmpty();
    }

    public String model() {
        return model;
    }

    /** Returns the model's verdict, or empty with {@code failure[0]} set to the reason it was not usable. */
    public Optional<Verdict> classify(String ticketId, String title, String description, TriageRules rules, String[] failure) {
        try {
            String system = "You triage IT support tickets. Reply with JSON only: {\"priority\": one of " + rules.priorityLevels()
                    + ", \"ownerTeam\": one of " + rules.teamNames() + ", \"reason\": one short sentence}. "
                    + "P1 means an outage or urgent business impact, P2 errors or degraded service, P3 routine requests.";
            String user = "Title: " + title + "\nDescription: " + (description == null ? "" : description);
            Map<String, Object> body = Map.of("model", model, "temperature", 0,
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
            String raw = rest.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey).body(body).retrieve().body(String.class);
            String content = json.readTree(raw).path("choices").path(0).path("message").path("content").asString("");
            content = content.replaceAll("(?s)^\\s*```(?:json)?|```\\s*$", "").trim();
            JsonNode v = json.readTree(content);
            String p = v.path("priority").asString("").trim().toUpperCase();
            String t = v.path("ownerTeam").asString("").trim().toLowerCase();
            String reason = v.path("reason").asString("").trim();
            if (!rules.priorityLevels().contains(p)) {
                failure[0] = "the model answered an unknown priority '" + p + "'";
                return Optional.empty();
            }
            if (!rules.teamNames().contains(t)) {
                failure[0] = "the model answered an unknown team '" + t + "'";
                return Optional.empty();
            }
            if (reason.isEmpty()) reason = "Chosen by " + model;
            if (reason.length() > 300) reason = reason.substring(0, 300);
            return Optional.of(new Verdict(ticketId, p, t, reason, "medium", "llm", List.of(), null));
        } catch (Exception e) {
            log.warn("LLM triage failed, using rules: {}", e.toString());
            failure[0] = "the AI service failed (" + e.getClass().getSimpleName() + ")";
            return Optional.empty();
        }
    }
}
