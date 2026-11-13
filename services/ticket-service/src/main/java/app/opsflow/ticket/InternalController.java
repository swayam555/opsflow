package app.opsflow.ticket;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Called by the orchestrator (with the internal token) when a run ends. Safe to call more than once. */
@RestController
public class InternalController {
    private final Tickets tickets;

    public InternalController(Tickets tickets) {
        this.tickets = tickets;
    }

    @PostMapping("/internal/runs/{runId}/finished")
    public ResponseEntity<Void> finished(@PathVariable String runId, @RequestBody Map<String, Object> body) {
        tickets.runFinished(runId, body);
        return ResponseEntity.noContent().build();
    }
}
