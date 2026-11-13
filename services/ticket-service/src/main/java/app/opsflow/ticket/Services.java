package app.opsflow.ticket;

import app.opsflow.common.ServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Clients for every other Opsflow service. */
@Component
public class Services {
    final ServiceClient triage;
    final ServiceClient orchestrator;
    final ServiceClient notifications;
    final ServiceClient deployments;
    final ServiceClient tracker;

    public Services(@Value("${opsflow.internal.token}") String token,
                    @Value("${opsflow.services.triage-url}") String triage,
                    @Value("${opsflow.services.orchestrator-url}") String orchestrator,
                    @Value("${opsflow.services.notification-url}") String notifications,
                    @Value("${opsflow.services.deployment-url}") String deployments,
                    @Value("${opsflow.services.tracker-url}") String tracker) {
        Duration t = Duration.ofSeconds(20); // triage may ask an LLM
        this.triage = new ServiceClient("triage-service", triage, token, t);
        this.orchestrator = new ServiceClient("orchestrator-service", orchestrator, token, Duration.ofSeconds(8));
        this.notifications = new ServiceClient("notification-service", notifications, token, Duration.ofSeconds(8));
        this.deployments = new ServiceClient("deployment-service", deployments, token, Duration.ofSeconds(8));
        this.tracker = new ServiceClient("tracker-service", tracker, token, Duration.ofSeconds(12));
    }

    Map<String, ServiceClient> all() {
        Map<String, ServiceClient> m = new LinkedHashMap<>();
        m.put("triage-service", triage);
        m.put("orchestrator-service", orchestrator);
        m.put("notification-service", notifications);
        m.put("deployment-service", deployments);
        m.put("tracker-service", tracker);
        return m;
    }
}
