package app.opsflow.ticket;

import app.opsflow.common.ApiException;
import app.opsflow.common.PageView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class TicketController {
    public record NewTicket(@NotBlank(message = "Title is required") @Size(max = 200, message = "Title can be at most 200 characters") String title,
                            @Size(max = 5000, message = "Description can be at most 5000 characters") String description,
                            @Size(max = 100, message = "Reporter can be at most 100 characters") String reporter,
                            @Pattern(regexp = "^$|^P[123]$", message = "Priority must be P1, P2 or P3") String priority,
                            @Size(max = 60) @Pattern(regexp = "^$|^[a-z0-9-]+$", message = "Not a workflow id") String workflow) {}

    public record Change(@Pattern(regexp = "^P[123]$", message = "Priority must be P1, P2 or P3") String priority,
                         @Size(min = 2, max = 60, message = "Team must be 2 to 60 characters") @Pattern(regexp = "^[a-z0-9-]+$", message = "Team names use lower-case letters, digits and dashes") String ownerTeam,
                         @Pattern(regexp = "^(RESOLVED|REOPENED)$", message = "Status can be RESOLVED or REOPENED") String status) {}

    public record Note(@NotBlank(message = "Write something") @Size(max = 1500, message = "Notes can be at most 1500 characters") String text) {}

    public record Retriage(Boolean rerun) {}

    public record RunRequest(@Size(max = 60) @Pattern(regexp = "^$|^[a-z0-9-]+$", message = "Not a workflow id") String workflow) {}

    private static final Set<String> SORTS = Set.of("createdAt", "updatedAt", "priority", "status");

    private final Tickets tickets;
    private final TicketRepository repo;
    private final EventRepository events;
    private final JobRepository jobs;
    private final Dispatcher dispatcher;
    private final Services services;

    public TicketController(Tickets tickets, TicketRepository repo, EventRepository events, JobRepository jobs, Dispatcher dispatcher, Services services) {
        this.tickets = tickets;
        this.repo = repo;
        this.events = events;
        this.jobs = jobs;
        this.dispatcher = dispatcher;
        this.services = services;
    }

    static String actor(HttpServletRequest req) {
        return String.valueOf(req.getAttribute(Access.ACTOR));
    }

    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest req) {
        return Map.of("role", req.getAttribute(Access.ROLE), "actor", actor(req));
    }

    /** Creates a ticket. Triage and the workflow follow in the background; the ticket starts as NEW. */
    @PostMapping("/tickets")
    public ResponseEntity<Ticket> create(@Valid @RequestBody NewTicket r, HttpServletRequest req) {
        Ticket t = tickets.create(r.title().trim(), blank(r.description()), blank(r.reporter()), blank(r.priority()), blank(r.workflow()), actor(req));
        dispatcher.kick();
        return ResponseEntity.status(HttpStatus.CREATED).body(t);
    }

    @GetMapping("/tickets")
    public PageView<Ticket> list(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
                                 @RequestParam(required = false) String priority, @RequestParam(required = false) String team,
                                 @RequestParam(defaultValue = "createdAt") String sort, @RequestParam(defaultValue = "desc") String dir,
                                 @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        String like = q == null || q.isBlank() ? null
                : "%" + q.trim().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        Sort.Direction d = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Sort s = Sort.by(d, SORTS.contains(sort) ? sort : "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));
        return PageView.of(repo.search(blank(status), blank(priority), blank(team), like,
                PageRequest.of(PageView.page(page), PageView.size(size), s)), t -> t);
    }

    @GetMapping("/tickets/{idOrKey}")
    public Map<String, Object> one(@PathVariable String idOrKey) {
        Ticket t = tickets.find(idOrKey);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ticket", t);
        m.put("events", events.findByTicketIdOrderByIdAsc(t.getId()));
        m.put("pendingJobs", jobs.findByTicketIdAndStatus(t.getId(), Job.Status.PENDING.name()));
        m.put("failedJobs", jobs.findByTicketIdAndStatus(t.getId(), Job.Status.FAILED.name()));
        return m;
    }

    @PatchMapping("/tickets/{idOrKey}")
    public Ticket change(@PathVariable String idOrKey, @Valid @RequestBody Change c, HttpServletRequest req) {
        if (c.priority() == null && c.ownerTeam() == null && c.status() == null) throw ApiException.invalid(null, "Nothing to change");
        Ticket t = tickets.update(idOrKey, c.priority(), c.ownerTeam(), c.status(), actor(req));
        dispatcher.kick();
        return t;
    }

    @PostMapping("/tickets/{idOrKey}/notes")
    public ResponseEntity<TicketEvent> note(@PathVariable String idOrKey, @Valid @RequestBody Note n, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tickets.note(idOrKey, n.text().trim(), actor(req)));
    }

    @PostMapping("/tickets/{idOrKey}/retriage")
    public ResponseEntity<Job> retriage(@PathVariable String idOrKey, @RequestBody(required = false) Retriage r, HttpServletRequest req) {
        Job j = tickets.retriage(idOrKey, r == null || r.rerun() == null || r.rerun(), actor(req));
        dispatcher.kick();
        return ResponseEntity.accepted().body(j);
    }

    @PostMapping("/tickets/{idOrKey}/runs")
    public ResponseEntity<Job> run(@PathVariable String idOrKey, @Valid @RequestBody(required = false) RunRequest r, HttpServletRequest req) {
        Job j = tickets.runWorkflow(idOrKey, r == null ? null : blank(r.workflow()), actor(req));
        dispatcher.kick();
        return ResponseEntity.accepted().body(j);
    }

    /** The workflow runs of one ticket, newest first, with their steps (from the orchestrator). */
    @GetMapping("/tickets/{idOrKey}/runs")
    public JsonNode runs(@PathVariable String idOrKey) {
        Ticket t = tickets.find(idOrKey);
        return services.orchestrator.get("/runs?ticketId={id}&size=20", JsonNode.class, t.getId());
    }

    @PostMapping("/runs/{runId}/retry")
    public JsonNode retryRun(@PathVariable String runId, HttpServletRequest req) {
        if (!runId.matches("[0-9a-f-]{36}")) throw ApiException.notFound("Run");
        JsonNode run = services.orchestrator.post("/runs/{id}/retry", null, JsonNode.class, null, runId);
        tickets.runRetried(runId, actor(req));
        return run;
    }

    @GetMapping("/runs")
    public JsonNode allRuns(@RequestParam(required = false) String status, @RequestParam(required = false) String workflow,
                            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return services.orchestrator.get("/runs?status={s}&workflow={w}&page={p}&size={z}", JsonNode.class,
                status == null ? "" : status, workflow == null ? "" : workflow, PageView.page(page), PageView.size(size));
    }

    @GetMapping("/runs/{runId}")
    public JsonNode oneRun(@PathVariable String runId) {
        if (!runId.matches("[0-9a-f-]{36}")) throw ApiException.notFound("Run");
        return services.orchestrator.get("/runs/{id}", JsonNode.class, runId);
    }

    @GetMapping("/events/recent")
    public List<Map<String, Object>> recent() {
        return events.findTop20ByOrderByIdDesc().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("ticketId", e.getTicketId());
            m.put("ticketKey", "OPS-" + e.getTicketId());
            m.put("type", e.getType());
            m.put("message", e.getMessage());
            m.put("actor", e.getActor());
            m.put("createdAt", e.getCreatedAt());
            return m;
        }).toList();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
