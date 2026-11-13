package app.opsflow.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Database errors, only for services that have a database (loaded when spring-tx is on the classpath). */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DataErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(DataErrorHandler.class);

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiError> stale(OptimisticLockingFailureException e, HttpServletRequest req) {
        return ErrorHandler.send(HttpStatus.CONFLICT, "edit_conflict", "Someone else changed this at the same time. Reload and try again.", null, req);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integrity(DataIntegrityViolationException e, HttpServletRequest req) {
        log.warn("Integrity violation on {}: {}", req.getRequestURI(), e.getMostSpecificCause().getMessage());
        return ErrorHandler.send(HttpStatus.CONFLICT, "conflict", "That change conflicts with existing data. Refresh and try again.", null, req);
    }
}
