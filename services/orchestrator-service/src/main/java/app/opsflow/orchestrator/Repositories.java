package app.opsflow.orchestrator;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

interface RunRepository extends JpaRepository<Run, String> {
    Optional<Run> findByIdempotencyKey(String key);

    List<Run> findByStatus(String status);

    List<Run> findTop50ByReportedFalseAndStatusInOrderByFinishedAtAsc(Collection<String> statuses);

    @Query("""
            select r from Run r
            where (:ticketId is null or r.ticketId = :ticketId)
              and (:status is null or r.status = :status)
              and (:workflow is null or r.workflowId = :workflow)
            """)
    Page<Run> search(@Param("ticketId") Long ticketId, @Param("status") String status, @Param("workflow") String workflow, Pageable page);

    @Query("select r.status, count(r) from Run r group by r.status")
    List<Object[]> countByStatus();
}

interface StepRepository extends JpaRepository<RunStep, String> {
    List<RunStep> findByRunIdOrderByIdx(String runId);

    List<RunStep> findByRunIdInOrderByIdx(Collection<String> runIds);
}
