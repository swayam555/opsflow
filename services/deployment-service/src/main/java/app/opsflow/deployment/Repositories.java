package app.opsflow.deployment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface DeploymentRepository extends JpaRepository<Deployment, String> {
    Optional<Deployment> findByIdempotencyKey(String key);

    Optional<Deployment> findByKey(String key);

    List<Deployment> findByStatusIn(List<String> statuses);

    @Query(value = "select nextval('deployment_seq')", nativeQuery = true)
    long nextNumber();

    @Query("""
            select d from Deployment d
            where (:env is null or d.environment = :env)
              and (:status is null or d.status = :status)
              and (:ticket is null or d.ticketKey = :ticket)
            """)
    Page<Deployment> search(@Param("env") String env, @Param("status") String status, @Param("ticket") String ticket, Pageable page);
}

interface FreezeRepository extends JpaRepository<Freeze, String> {}
