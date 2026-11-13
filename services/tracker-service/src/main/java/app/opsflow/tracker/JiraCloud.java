package app.opsflow.tracker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Creates and resolves issues in Jira Cloud (REST API v3) when base URL, email, API token and project are all set. */
@Component
public class JiraCloud {
    public record Created(String key, String url) {}

    private final String baseUrl;
    private final String project;
    private final String issueType;
    private final RestClient rest;
    private final boolean configured;

    public JiraCloud(@Value("${opsflow.jira.base-url:}") String baseUrl, @Value("${opsflow.jira.email:}") String email,
                     @Value("${opsflow.jira.api-token:}") String token, @Value("${opsflow.jira.project:}") String project,
                     @Value("${opsflow.jira.issue-type:Task}") String issueType) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.project = project == null ? "" : project.trim();
        this.issueType = issueType;
        this.configured = !this.baseUrl.isEmpty() && !this.project.isEmpty() && !email.isBlank() && !token.isBlank();
        var f = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build());
        f.setReadTimeout(Duration.ofSeconds(10));
        String basic = Base64.getEncoder().encodeToString((email.trim() + ":" + token.trim()).getBytes(StandardCharsets.UTF_8));
        this.rest = RestClient.builder().baseUrl(this.baseUrl.isEmpty() ? "http://localhost" : this.baseUrl).requestFactory(f)
                .defaultHeader("Authorization", "Basic " + basic).defaultHeader("Accept", "application/json").build();
    }

    public boolean configured() {
        return configured;
    }

    public String project() {
        return project;
    }

    public Created create(Issue i) {
        String text = (i.getDescription() == null ? "" : i.getDescription())
                + "\n\nOpsflow ticket " + i.getTicketKey() + " · priority " + i.getPriority() + " · team " + i.getTeam() + " · local issue " + i.getKey();
        Map<String, Object> doc = Map.of("type", "doc", "version", 1, "content",
                List.of(Map.of("type", "paragraph", "content", List.of(Map.of("type", "text", "text", text)))));
        List<String> labels = new java.util.ArrayList<>(List.of("opsflow", i.getPriority().toLowerCase(Locale.ROOT)));
        if (i.getTeam() != null) labels.add(i.getTeam().replaceAll("[^A-Za-z0-9_-]", "-"));
        Map<String, Object> body = Map.of("fields", Map.of(
                "project", Map.of("key", project),
                "summary", i.getSummary(),
                "issuetype", Map.of("name", issueType),
                "description", doc,
                "labels", labels));
        JsonNode res = rest.post().uri("/rest/api/3/issue").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
        String key = res.path("key").asString();
        return new Created(key, baseUrl + "/browse/" + key);
    }

    /** Adds a comment saying the work is done; transitions differ per Jira workflow, so the status itself is left to Jira. */
    public void commentDone(String externalKey, String text) {
        Map<String, Object> doc = Map.of("type", "doc", "version", 1, "content",
                List.of(Map.of("type", "paragraph", "content", List.of(Map.of("type", "text", "text", text)))));
        rest.post().uri("/rest/api/3/issue/{key}/comment", externalKey).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("body", doc)).retrieve().toBodilessEntity();
    }
}
