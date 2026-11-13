package app.opsflow.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Performs one workflow step against the right service. Calls carry an idempotency key, so repeating a step is safe. */
@Component
public class StepRunner {
    private static final Logger log = LoggerFactory.getLogger(StepRunner.class);
    private static final Pattern VAR = Pattern.compile("\\$\\{([a-zA-Z]+)}");

    public record Outcome(String detail, String reference, String link) {}

    private final Clients clients;
    private final long pollMillis;
    private final Duration deployTimeout;

    public StepRunner(Clients clients, @Value("${opsflow.orchestrator.deploy-poll-millis:500}") long pollMillis,
                      @Value("${opsflow.orchestrator.deploy-timeout-seconds:120}") long deployTimeoutSeconds) {
        this.clients = clients;
        this.pollMillis = pollMillis;
        this.deployTimeout = Duration.ofSeconds(deployTimeoutSeconds);
    }

    Outcome run(Run run, RunStep step, Map<String, Object> cfg, Consumer<String> progress) throws InterruptedException {
        String key = run.getId() + ":" + step.getIdx() + ":" + run.getAttempt();
        Map<String, String> vars = vars(run);
        String team = str(cfg.get("team")) != null ? fill(str(cfg.get("team")), vars) : run.getTeam() == null ? "general-support" : run.getTeam();
        return switch (step.getType()) {
            case "notify" -> {
                Map<String, Object> body = new HashMap<>();
                body.put("team", team);
                body.put("channel", str(cfg.get("channel")) == null ? null : fill(str(cfg.get("channel")), vars));
                body.put("message", fill(str(cfg.get("message")), vars));
                body.put("ticketKey", run.getTicketKey());
                body.put("runId", run.getId());
                JsonNode n = clients.notifications.post("/notifications", body, JsonNode.class, Map.of("Idempotency-Key", key));
                String how = "SENT".equals(n.path("status").asString()) ? "posted to Slack" : "recorded (Slack not configured)";
                yield new Outcome("Notified " + n.path("channel").asString() + ", " + how, n.path("id").asString(null), null);
            }
            case "jira", "issue" -> {
                String extra = str(cfg.get("message")) == null ? "" : fill(str(cfg.get("message")), vars);
                String desc = (extra + (run.getDescription() == null || run.getDescription().isBlank() ? "" : "\n\n" + run.getDescription())).trim();
                Map<String, Object> body = new HashMap<>();
                body.put("summary", cut(fill(str(cfg.get("summary")) == null ? "[${priority}] ${title}" : str(cfg.get("summary")), vars), 255));
                body.put("description", cut(desc, 4000));
                body.put("priority", run.getPriority() != null && run.getPriority().matches("P[123]") ? run.getPriority() : "P3");
                body.put("team", team);
                body.put("ticketKey", run.getTicketKey());
                body.put("runId", run.getId());
                JsonNode i = clients.tracker.post("/issues", body, JsonNode.class, Map.of("Idempotency-Key", key));
                String ext = i.path("externalKey").asString(null);
                yield new Outcome("Opened " + i.path("key").asString() + (ext == null ? "" : " (Jira " + ext + ")"),
                        i.path("key").asString(), i.path("externalUrl").asString(null));
            }
            case "deployment", "deploy" -> deploy(run, cfg, vars, key, progress);
            case "log" -> {
                String msg = fill(str(cfg.get("message")), vars);
                log.info("[{} {}] {}", run.getTicketKey(), run.getWorkflowId(), msg);
                yield new Outcome(msg, null, null);
            }
            case "complete" -> new Outcome(str(cfg.get("message")) == null ? "Workflow complete" : fill(str(cfg.get("message")), vars), null, null);
            default -> throw new StepFailed("Unknown step type " + step.getType());
        };
    }

    private Outcome deploy(Run run, Map<String, Object> cfg, Map<String, String> vars, String key, Consumer<String> progress) throws InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("environment", fill(str(cfg.get("environment")), vars));
        body.put("service", str(cfg.get("service")) == null ? null : fill(str(cfg.get("service")), vars));
        body.put("version", str(cfg.get("version")) == null ? null : fill(str(cfg.get("version")), vars));
        body.put("reason", cut(fill(str(cfg.get("reason")) == null ? "Opsflow ${workflow} for ${key}" : str(cfg.get("reason")), vars), 500));
        body.put("ticketKey", run.getTicketKey());
        body.put("runId", run.getId());
        JsonNode d = clients.deployments.post("/deployments", body, JsonNode.class, Map.of("Idempotency-Key", key));
        String id = d.path("id").asString();
        String dep = d.path("key").asString();
        String env = d.path("environment").asString();
        progress.accept(dep + " to " + env + " is " + d.path("status").asString().toLowerCase());
        Instant end = Instant.now().plus(deployTimeout);
        while (Instant.now().isBefore(end)) {
            String s = d.path("status").asString();
            if (s.equals("SUCCEEDED")) return new Outcome(dep + " to " + env + " succeeded", dep, null);
            if (s.equals("FAILED")) throw new StepFailed(dep + " to " + env + " failed: " + d.path("failureReason").asString("no reason given"));
            Thread.sleep(pollMillis);
            d = clients.deployments.get("/deployments/{id}", JsonNode.class, id);
            progress.accept(dep + " to " + env + " is " + d.path("status").asString().toLowerCase());
        }
        throw new StepFailed(dep + " did not finish within " + deployTimeout.toSeconds() + " s");
    }

    static Map<String, String> vars(Run run) {
        Map<String, String> v = new HashMap<>();
        v.put("key", run.getTicketKey() == null ? "(no ticket)" : run.getTicketKey());
        v.put("ticketId", run.getTicketId() == null ? "" : String.valueOf(run.getTicketId()));
        v.put("title", run.getTitle() == null ? "" : run.getTitle());
        v.put("description", run.getDescription() == null ? "" : run.getDescription());
        v.put("priority", run.getPriority() == null ? "P3" : run.getPriority());
        v.put("team", run.getTeam() == null ? "general-support" : run.getTeam());
        v.put("workflow", run.getWorkflowId());
        v.put("runId", run.getId());
        return v;
    }

    /** Replaces ${name} with the run's values; unknown names are left as they are. */
    static String fill(String template, Map<String, String> vars) {
        if (template == null) return null;
        Matcher m = VAR.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(vars.getOrDefault(m.group(1), m.group())));
        m.appendTail(out);
        return out.toString();
    }

    private static String str(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
