package app.opsflow.tracker;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface IssueRepository extends JpaRepository<Issue, Long> {
    Optional<Issue> findByIdempotencyKey(String key);

    Optional<Issue> findByKey(String key);

    List<Issue> findByTicketKeyAndStatus(String ticketKey, String status);

    @Query(value = "select nextval('issue_seq')", nativeQuery = true)
    long nextNumber();

    @Query("""
            select i from Issue i
            where (:status is null or i.status = :status)
              and (:team is null or i.team = :team)
              and (:ticket is null or i.ticketKey = :ticket)
              and (:q is null or lower(i.summary) like :q escape '!' or lower(i.key) like :q escape '!')
            """)
    Page<Issue> search(@Param("status") String status, @Param("team") String team, @Param("ticket") String ticket,
                       @Param("q") String q, Pageable page);
}
