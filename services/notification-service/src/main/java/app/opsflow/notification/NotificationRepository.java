package app.opsflow.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, String> {
    Optional<Notification> findByIdempotencyKey(String key);

    @Query("""
            select n from Notification n
            where (:team is null or n.team = :team)
              and (:status is null or n.status = :status)
              and (:ticket is null or n.ticketKey = :ticket)
              and (:q is null or lower(n.message) like :q escape '!' or lower(n.channel) like :q escape '!')
            """)
    Page<Notification> search(@Param("team") String team, @Param("status") String status, @Param("ticket") String ticket,
                              @Param("q") String q, Pageable page);
}
