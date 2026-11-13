package app.opsflow.ticket;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The original /tickets endpoints (create, list all, get one), kept for existing scripts. Same token rules as /api. */
@RestController
public class LegacyController {
    private final TicketController api;
    private final TicketRepository repo;
    private final Tickets tickets;

    public LegacyController(TicketController api, TicketRepository repo, Tickets tickets) {
        this.api = api;
        this.repo = repo;
        this.tickets = tickets;
    }

    @PostMapping("/tickets")
    public ResponseEntity<Ticket> create(@Valid @RequestBody TicketController.NewTicket r, HttpServletRequest req) {
        return api.create(r, req);
    }

    @GetMapping("/tickets")
    public List<Ticket> all() {
        return repo.findAll(org.springframework.data.domain.Sort.by("id"));
    }

    @GetMapping("/tickets/{idOrKey}")
    public Ticket one(@PathVariable String idOrKey) {
        return tickets.find(idOrKey);
    }
}
