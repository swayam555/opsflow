package app.opsflow.tracker;

import app.opsflow.common.ApiException;
import app.opsflow.common.PageView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@RestController
public class IssueController {
    private static final Logger log = LoggerFactory.getLogger(IssueController.class);

    public record IssueRequest(@NotBlank(message = "Summary is required") @Size(max = 255, message = "Summary can be at most 255 characters") String summary,
                               @Size(max = 4000, message = "Description can be at most 4000 characters") String description,
                               @Pattern(regexp = "^$|^P[123]$", message = "Priority must be P1, P2 or P3") String priority,
                               @Size(max = 60) String team,
                               @Size(max = 40) String ticketKey,
                               @Size(max = 36) String runId) {}

    public record StatusChange(@NotBlank @Pattern(regexp = "OPEN|DONE", message = "Status must be OPEN or DONE") String status) {}

    public record CloseForTicket(@NotBlank(message = "ticketKey is required") String ticketKey, @Size(max = 500) String note) {}

    /** The old Jira-mock form. */
    public record LegacyCreate(@NotBlank(message = "Title is required") @Size(max = 255) String title, @Size(max = 4000) String description) {}

    private final IssueRepository repo;
    private final JiraCloud jira;
    private final String projectKey;

    public IssueController(IssueRepository repo, JiraCloud jira, @Value("${opsflow.tracker.project-key:INC}") String projectKey) {
        this.repo = repo;
        this.jira = jira;
        this.projectKey = projectKey;
    }

    @PostMapping("/issues")
    public ResponseEntity<Issue> create(@Valid @RequestBody IssueRequest r, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (key != null && !key.isBlank()) {
            var existing = repo.findByIdempotencyKey(key);
            if (existing.isPresent()) return ResponseEntity.ok(existing.get());
        }
        long n = repo.nextNumber();
        Issue i = new Issue(n, projectKey + "-" + n, blank(key), r.summary().trim(), blank(r.description()),
                blank(r.priority()) == null ? "P3" : r.priority(), blank(r.team()), blank(r.ticketKey()), blank(r.runId()));
        if (jira.configured()) {
            try {
                var created = jira.create(i);
                i.linkExternal(created.key(), created.url());
            } catch (RestClientResponseException e) {
                log.warn("Jira refused the issue: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
                if (e.getStatusCode().is4xxClientError()) {
                    // Wrong project, issue type or credentials: retrying will not help.
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "jira_rejected",
                            "Jira refused the issue (" + e.getStatusCode().value() + "). Check the Jira project, issue type and API token.", null);
                }
                throw ApiException.unavailable("jira_unavailable", "Jira answered " + e.getStatusCode().value() + "; try again shortly");
            } catch (RuntimeException e) {
                log.warn("Jira unreachable: {}", e.toString());
                throw ApiException.unavailable("jira_unavailable", "Jira could not be reached; try again shortly");
            }
        }
        try {
            repo.saveAndFlush(i);
        } catch (DataIntegrityViolationException race) {
            return ResponseEntity.ok(repo.findByIdempotencyKey(key).orElseThrow(() -> race));
        }
        log.info("Issue {} created for {} ({})", i.getKey(), i.getTicketKey(), i.getExternalKey() == null ? "local" : "Jira " + i.getExternalKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(i);
    }

    @PostMapping("/jira/create")
    public ResponseEntity<Issue> legacy(@Valid @RequestBody LegacyCreate r) {
        return create(new IssueRequest(r.title(), r.description(), null, null, null, null), null);
    }

    @GetMapping("/issues")
    public PageView<Issue> list(@RequestParam(required = false) String status, @RequestParam(required = false) String team,
                                @RequestParam(required = false) String ticket, @RequestParam(required = false) String q,
                                @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        String like = q == null || q.isBlank() ? null
                : "%" + q.trim().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return PageView.of(repo.search(blank(status), blank(team), blank(ticket), like,
                PageRequest.of(PageView.page(page), PageView.size(size), Sort.by(Sort.Direction.DESC, "createdAt"))), i -> i);
    }

    @GetMapping("/issues/settings")
    public Map<String, Object> settings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectKey", projectKey);
        m.put("jiraConfigured", jira.configured());
        if (jira.configured()) m.put("jiraProject", jira.project());
        return m;
    }

    @GetMapping("/issues/{key}")
    public Issue one(@PathVariable String key) {
        return repo.findByKey(key.toUpperCase(Locale.ROOT)).orElseThrow(() -> ApiException.notFound("Issue"));
    }

    @PostMapping("/issues/{key}/status")
    public Issue status(@PathVariable String key, @Valid @RequestBody StatusChange c) {
        Issue i = one(key);
        i.setStatus(Issue.Status.valueOf(c.status()));
        return repo.save(i);
    }

    /** Marks every open issue of a ticket DONE (called when the ticket is resolved). */
    @PostMapping("/issues/close-for-ticket")
    public Map<String, Object> closeForTicket(@Valid @RequestBody CloseForTicket c) {
        var open = repo.findByTicketKeyAndStatus(c.ticketKey(), Issue.Status.OPEN.name());
        for (Issue i : open) {
            i.setStatus(Issue.Status.DONE);
            repo.save(i);
            if (i.getExternalKey() != null && jira.configured()) {
                try {
                    jira.commentDone(i.getExternalKey(), "Resolved in Opsflow (" + c.ticketKey() + ")" + (c.note() == null ? "" : ": " + c.note()));
                } catch (RuntimeException e) {
                    log.warn("Could not comment on Jira {}: {}", i.getExternalKey(), e.toString());
                }
            }
        }
        return Map.of("closed", open.stream().map(Issue::getKey).toList());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
