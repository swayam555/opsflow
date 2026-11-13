package app.opsflow.orchestrator;

import app.opsflow.common.ServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** HTTP clients for the services a workflow talks to. */
@Component
public class Clients {
    final ServiceClient tickets;
    final ServiceClient notifications;
    final ServiceClient deployments;
    final ServiceClient tracker;

    public Clients(@Value("${opsflow.internal.token}") String token,
                   @Value("${opsflow.services.ticket-url}") String ticketUrl,
                   @Value("${opsflow.services.notification-url}") String notificationUrl,
                   @Value("${opsflow.services.deployment-url}") String deploymentUrl,
                   @Value("${opsflow.services.tracker-url}") String trackerUrl) {
        Duration t = Duration.ofSeconds(15);
        tickets = new ServiceClient("ticket-service", ticketUrl, token, t);
        notifications = new ServiceClient("notification-service", notificationUrl, token, t);
        deployments = new ServiceClient("deployment-service", deploymentUrl, token, t);
        tracker = new ServiceClient("tracker-service", trackerUrl, token, t);
    }
}
