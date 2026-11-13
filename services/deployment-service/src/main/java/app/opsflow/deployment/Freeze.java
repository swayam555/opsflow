package app.opsflow.deployment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** A frozen environment refuses new deployments until it is unfrozen (change freezes, incidents in progress). */
@Entity
@Table(name = "environment_freeze")
public class Freeze {
    @Id
    private String environment;
    private String reason;
    @Column(name = "frozen_at")
    private OffsetDateTime frozenAt = OffsetDateTime.now();

    protected Freeze() {}

    Freeze(String environment, String reason) {
        this.environment = environment;
        this.reason = reason;
    }

    public String getEnvironment() { return environment; }
    public String getReason() { return reason; }
    public OffsetDateTime getFrozenAt() { return frozenAt; }
}
