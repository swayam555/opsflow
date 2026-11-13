package app.opsflow.ticket;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

interface TicketRepository extends JpaRepository<Ticket, Long> {
    Optional<Ticket> findByKey(String key);

    Optional<Ticket> findByCurrentRunId(String runId);

    @Query(value = "select nextval('ticket_seq')", nativeQuery = true)
    long nextNumber();

    @Query("""
            select t from Ticket t
            where (:status is null or t.status = :status)
              and (:priority is null or t.priority = :priority)
              and (:team is null or t.ownerTeam = :team)
              and (:q is null or lower(t.title) like :q escape '!' or lower(t.description) like :q escape '!'
                   or lower(t.key) like :q escape '!' or lower(t.reporter) like :q escape '!')
            """)
    Page<Ticket> search(@Param("status") String status, @Param("priority") String priority, @Param("team") String team,
                        @Param("q") String q, Pageable page);

    @Query("select t.status, count(t) from Ticket t group by t.status")
    List<Object[]> countByStatus();

    @Query("select coalesce(t.priority, 'untriaged'), count(t) from Ticket t where t.status <> 'RESOLVED' group by coalesce(t.priority, 'untriaged')")
    List<Object[]> openByPriority();

    @Query("select coalesce(t.ownerTeam, 'untriaged'), count(t) from Ticket t where t.status <> 'RESOLVED' group by coalesce(t.ownerTeam, 'untriaged')")
    List<Object[]> openByTeam();

    List<Ticket> findByCreatedAtAfter(OffsetDateTime since);
}

interface EventRepository extends JpaRepository<TicketEvent, Long> {
    List<TicketEvent> findByTicketIdOrderByIdAsc(Long ticketId);

    List<TicketEvent> findTop20ByOrderByIdDesc();
}

interface JobRepository extends JpaRepository<Job, Long> {
    List<Job> findTop20ByStatusAndNextAtBeforeOrderByIdAsc(String status, OffsetDateTime now);

    List<Job> findByStatusOrderByIdAsc(String status);

    List<Job> findByTicketIdAndStatus(Long ticketId, String status);

    List<Job> findTop20ByStatusOrderByIdDesc(String status);
}
