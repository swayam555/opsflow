package app.opsflow.ticket;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** One line of a ticket's audit trail. Never changed after it is written. */
@Entity
@Table(name = "ticket_event")
public class TicketEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "ticket_id")
    @JsonIgnore
    private Long ticketId;
    private String type;
    private String message;
    private String actor;
    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected TicketEvent() {}

    TicketEvent(Long ticketId, String type, String message, String actor) {
        this.ticketId = ticketId;
        this.type = type;
        this.message = message.length() > 1500 ? message.substring(0, 1500) : message;
        this.actor = actor;
    }

    public Long getId() { return id; }
    public Long getTicketId() { return ticketId; }
    public String getType() { return type; }
    public String getMessage() { return message; }
    public String getActor() { return actor; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
