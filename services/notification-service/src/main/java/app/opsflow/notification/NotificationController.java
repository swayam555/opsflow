package app.opsflow.notification;

import app.opsflow.common.ApiException;
import app.opsflow.common.PageView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.util.Locale;
import java.util.Map;

@RestController
public class NotificationController {
    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    public record NotifyRequest(@NotBlank(message = "Team is required") @Size(max = 60) String team,
                                @Size(max = 80) String channel,
                                @NotBlank(message = "Message is required") @Size(max = 2000, message = "Message can be at most 2000 characters") String message,
                                @Size(max = 40) String ticketKey,
                                @Size(max = 36) String runId) {}

    /** The old single-field form: {"message": "..."} goes to the general channel. */
    public record LegacyNotify(@NotBlank(message = "Message is required") @Size(max = 2000) String message) {}

    private final NotificationRepository repo;
    private final Slack slack;

    public NotificationController(NotificationRepository repo, Slack slack) {
        this.repo = repo;
        this.slack = slack;
    }

    /**
     * Sends (or records) a notification. Repeating a request with the same Idempotency-Key returns the first
     * result instead of notifying twice; if the first attempt FAILED, the repeat tries Slack again.
     */
    @PostMapping("/notifications")
    public ResponseEntity<Notification> notify(@Valid @RequestBody NotifyRequest r,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (key != null && key.length() > 120) throw ApiException.invalid("Idempotency-Key", "Idempotency-Key can be at most 120 characters");
        if (key != null && !key.isBlank()) {
            var existing = repo.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                Notification n = existing.get();
                if (Notification.Status.FAILED.name().equals(n.getStatus())) {
                    n.retried();
                    deliver(n);
                    repo.save(n);
                    return respond(n, HttpStatus.OK);
                }
                return ResponseEntity.ok(n);
            }
        }
        String channel = r.channel() == null || r.channel().isBlank() ? "#" + r.team() : r.channel().trim();
        Notification n = new Notification(blankToNull(key), r.team().trim(), channel, r.message().trim(), blankToNull(r.ticketKey()), blankToNull(r.runId()));
        deliver(n);
        try {
            repo.saveAndFlush(n);
        } catch (DataIntegrityViolationException race) {
            // the same key arrived twice at once: the other request won
            return ResponseEntity.ok(repo.findByIdempotencyKey(key).orElseThrow(() -> race));
        }
        log.info("Notification {} to {} ({}): {}", n.getStatus(), n.getChannel(), n.getTicketKey(), n.getMessage());
        return respond(n, HttpStatus.CREATED);
    }

    @PostMapping("/notify")
    public ResponseEntity<Notification> legacy(@Valid @RequestBody LegacyNotify r) {
        return notify(new NotifyRequest("general-support", "#general", r.message(), null, null), null);
    }

    private ResponseEntity<Notification> respond(Notification n, HttpStatus ok) {
        // A failed delivery is reported as 503 so the caller retries with the same key.
        return ResponseEntity.status(Notification.Status.FAILED.name().equals(n.getStatus()) ? HttpStatus.SERVICE_UNAVAILABLE : ok).body(n);
    }

    private void deliver(Notification n) {
        if (!slack.configured()) {
            n.delivered(Notification.Status.LOGGED, "Slack is not configured, so the message was recorded only");
            return;
        }
        try {
            slack.post(n.getChannel(), n.getMessage());
            n.delivered(Notification.Status.SENT, "Posted to Slack");
        } catch (RuntimeException e) {
            log.warn("Slack post failed: {}", e.toString());
            n.delivered(Notification.Status.FAILED, "Slack did not accept the message (" + e.getClass().getSimpleName() + ")");
        }
    }

    @GetMapping("/notifications")
    public PageView<Notification> list(@RequestParam(required = false) String team, @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String ticket, @RequestParam(required = false) String q,
                                       @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        String like = q == null || q.isBlank() ? null
                : "%" + q.trim().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var p = repo.search(blankToNull(team), blankToNull(status), blankToNull(ticket), like,
                PageRequest.of(PageView.page(page), PageView.size(size), Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageView.of(p, n -> n);
    }

    @GetMapping("/notifications/{id}")
    public Notification one(@PathVariable String id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("Notification"));
    }

    @GetMapping("/notifications/settings")
    public Map<String, Object> settings() {
        return Map.of("slackConfigured", slack.configured(), "total", repo.count());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
