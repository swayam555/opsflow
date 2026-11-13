package app.opsflow.ticket;

import app.opsflow.common.DownstreamException;
import app.opsflow.common.ServiceClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/** Workflows, triage preview, activity in the action services, stats and the System page. */
@RestController
@RequestMapping("/api")
public class PlatformController {
    public record Preview(@Size(max = 200) String title, @Size(max = 5000) String description) {
        @AssertTrue(message = "Give a title or a description")
        public boolean isTitleOrDescription() {
            return (title != null && !title.isBlank()) || (description != null && !description.isBlank());
        }
    }

    public record Chaos(@Min(value = 0, message = "Use 0 to 10") @Max(value = 10, message = "Use 0 to 10") int failNext) {}

    public record Freeze(@NotBlank(message = "Say why the environment is frozen") @Size(max = 300) String reason) {}

    private final Services services;
    private final TicketRepository tickets;
    private final JobRepository jobs;
    private final Dispatcher dispatcher;
    private final ZoneId zone;

    public PlatformController(Services services, TicketRepository tickets, JobRepository jobs, Dispatcher dispatcher,
                              @Value("${opsflow.timezone:Asia/Kolkata}") String zone) {
        this.services = services;
        this.tickets = tickets;
        this.jobs = jobs;
        this.dispatcher = dispatcher;
        this.zone = ZoneId.of(zone);
    }

    @GetMapping("/workflows")
    public JsonNode workflows() {
        return services.orchestrator.get("/workflows", JsonNode.class);
    }

    @GetMapping("/triage/rules")
    public JsonNode rules() {
        return services.triage.get("/triage/rules", JsonNode.class);
    }

    /** What triage and workflow selection would do with this text, without creating anything (rules only, so it is instant). */
    @PostMapping("/triage/preview")
    public Map<String, Object> preview(@Valid @RequestBody Preview p) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", p.title());
        body.put("description", p.description());
        JsonNode v = services.triage.post("/triage?mode=rules", body, JsonNode.class, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("triage", v);
        try {
            out.put("workflow", services.orchestrator.get("/workflows/select?priority={p}&team={t}", JsonNode.class,
                    v.path("priority").asString(), v.path("ownerTeam").asString()));
        } catch (DownstreamException e) {
            out.put("workflow", null);
        }
        return out;
    }

    @GetMapping("/activity/notifications")
    public JsonNode notifications(@RequestParam(defaultValue = "") String team, @RequestParam(defaultValue = "") String status,
                                  @RequestParam(defaultValue = "") String ticket, @RequestParam(defaultValue = "") String q,
                                  @RequestParam(defaultValue = "0") int page) {
        return services.notifications.get("/notifications?team={a}&status={b}&ticket={c}&q={d}&page={e}&size=20", JsonNode.class, team, status, ticket, q, page);
    }

    @GetMapping("/activity/deployments")
    public JsonNode deployments(@RequestParam(defaultValue = "") String environment, @RequestParam(defaultValue = "") String status,
                                @RequestParam(defaultValue = "") String ticket, @RequestParam(defaultValue = "0") int page) {
        return services.deployments.get("/deployments?environment={a}&status={b}&ticket={c}&page={d}&size=20", JsonNode.class, environment, status, ticket, page);
    }

    @GetMapping("/activity/deployments/{id}")
    public JsonNode deployment(@PathVariable String id) {
        return services.deployments.get("/deployments/{id}", JsonNode.class, id);
    }

    @GetMapping("/activity/issues")
    public JsonNode issues(@RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "") String team,
                           @RequestParam(defaultValue = "") String ticket, @RequestParam(defaultValue = "") String q,
                           @RequestParam(defaultValue = "0") int page) {
        return services.tracker.get("/issues?status={a}&team={b}&ticket={c}&q={d}&page={e}&size=20", JsonNode.class, status, team, ticket, q, page);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(defaultValue = "14") int days) {
        int d = Math.max(7, Math.min(days, 90));
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Ticket.Status s : Ticket.Status.values()) byStatus.put(s.name(), 0L);
        tickets.countByStatus().forEach(r -> byStatus.put(String.valueOf(r[0]), (Long) r[1]));
        m.put("byStatus", byStatus);
        m.put("total", byStatus.values().stream().mapToLong(Long::longValue).sum());
        m.put("open", byStatus.entrySet().stream().filter(e -> !e.getKey().equals("RESOLVED")).mapToLong(Map.Entry::getValue).sum());
        m.put("openByPriority", rows(tickets.openByPriority()));
        m.put("openByTeam", rows(tickets.openByTeam()));

        LocalDate today = LocalDate.now(zone);
        LocalDate from = today.minusDays(d - 1L);
        Map<String, Long> perDay = new LinkedHashMap<>();
        for (LocalDate x = from; !x.isAfter(today); x = x.plusDays(1)) perDay.put(x.toString(), 0L);
        List<Long> minutesToAction = new ArrayList<>();
        for (Ticket t : tickets.findByCreatedAtAfter(from.atStartOfDay(zone).toOffsetDateTime())) {
            perDay.merge(t.getCreatedAt().atZoneSameInstant(zone).toLocalDate().toString(), 1L, Long::sum);
            if (t.getActionedAt() != null) minutesToAction.add(java.time.Duration.between(t.getCreatedAt(), t.getActionedAt()).toSeconds());
        }
        m.put("createdPerDay", perDay);
        m.put("days", d);
        m.put("timezone", zone.getId());
        m.put("avgSecondsToAction", minutesToAction.isEmpty() ? null : minutesToAction.stream().mapToLong(Long::longValue).average().orElse(0));
        m.put("pendingJobs", jobs.findByStatusOrderByIdAsc(Job.Status.PENDING.name()).size());
        try {
            m.put("runs", services.orchestrator.get("/runs/stats", JsonNode.class));
        } catch (DownstreamException e) {
            m.put("runs", null);
        }
        return m;
    }

    private static Map<String, Long> rows(List<Object[]> rows) {
        Map<String, Long> m = new LinkedHashMap<>();
        rows.stream().sorted((a, b) -> String.valueOf(a[0]).compareTo(String.valueOf(b[0]))).forEach(r -> m.put(String.valueOf(r[0]), (Long) r[1]));
        return m;
    }

    /** Health of every service, waiting jobs and the integration settings, for the System page. */
    @GetMapping("/system")
    public Map<String, Object> system() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> health = new ArrayList<>();
        health.add(Map.of("service", "ticket-service", "status", "UP", "latencyMs", 0));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, CompletableFuture<Map<String, Object>>> checks = new LinkedHashMap<>();
            services.all().forEach((name, client) -> checks.put(name, CompletableFuture.supplyAsync(() -> ping(name, client), pool)));
            checks.values().forEach(f -> health.add(f.join()));
        }
        m.put("services", health);
        m.put("pendingJobs", jobs.findByStatusOrderByIdAsc(Job.Status.PENDING.name()));
        m.put("failedJobs", jobs.findTop20ByStatusOrderByIdDesc(Job.Status.FAILED.name()));
        m.put("deployments", safe(() -> services.deployments.get("/deployments/settings", JsonNode.class)));
        m.put("notifications", safe(() -> services.notifications.get("/notifications/settings", JsonNode.class)));
        m.put("tracker", safe(() -> services.tracker.get("/issues/settings", JsonNode.class)));
        m.put("triage", safe(() -> services.triage.get("/triage/rules", JsonNode.class).path("llm")));
        return m;
    }

    private static Map<String, Object> ping(String name, ServiceClient c) {
        long t0 = System.nanoTime();
        try {
            JsonNode h = c.get("/actuator/health", JsonNode.class);
            return Map.of("service", name, "status", h.path("status").asString("UNKNOWN"), "latencyMs", (System.nanoTime() - t0) / 1_000_000);
        } catch (DownstreamException e) {
            return Map.of("service", name, "status", "DOWN", "detail", e.getMessage());
        }
    }

    private static Object safe(java.util.function.Supplier<Object> s) {
        try {
            return s.get();
        } catch (DownstreamException e) {
            return null;
        }
    }

    /** Makes waiting jobs due now (after you fixed whatever was down). */
    @PostMapping("/system/jobs/retry")
    public Map<String, Object> retryJobs() {
        var pending = jobs.findByStatusOrderByIdAsc(Job.Status.PENDING.name());
        pending.forEach(j -> {
            j.dueNow();
            jobs.save(j);
        });
        dispatcher.kick();
        return Map.of("scheduled", pending.size());
    }

    @PostMapping("/system/chaos")
    public JsonNode chaos(@Valid @RequestBody Chaos c) {
        return services.deployments.post("/deployments/chaos", Map.of("failNext", c.failNext()), JsonNode.class, null);
    }

    @PutMapping("/system/freezes/{environment}")
    public JsonNode freeze(@PathVariable String environment, @Valid @RequestBody Freeze f) {
        return services.deployments.put("/deployments/freezes/{e}", Map.of("reason", f.reason().trim()), JsonNode.class, environment);
    }

    @DeleteMapping("/system/freezes/{environment}")
    public JsonNode unfreeze(@PathVariable String environment) {
        return services.deployments.delete("/deployments/freezes/{e}", JsonNode.class, environment);
    }
}
