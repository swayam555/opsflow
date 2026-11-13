package app.opsflow.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.DatabindException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Maps every failure to {@link ApiError}, so clients see one error shape whichever service answered. */
@RestControllerAdvice
public class ErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    static ResponseEntity<ApiError> send(HttpStatus s, String code, String msg, Map<String, String> fields, HttpServletRequest req) {
        return ResponseEntity.status(s).body(ApiError.of(s.value(), code, msg, fields, req.getRequestURI()));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e, HttpServletRequest req) {
        return send(e.status(), e.code(), e.getMessage(), e.fields(), req);
    }

    @ExceptionHandler(DownstreamException.class)
    ResponseEntity<ApiError> downstream(DownstreamException e, HttpServletRequest req) {
        log.warn("{} {} failed downstream: {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        if (e.retryable()) return send(HttpStatus.SERVICE_UNAVAILABLE, e.service() + "_unavailable", e.getMessage(), null, req);
        // "No such run" or "run is not failed" downstream is the caller's problem, not a gateway fault: pass it on.
        if (e.status() == 404 || e.status() == 409) {
            String msg = e.getMessage();
            int cut = msg.indexOf(": ");
            HttpStatus s = HttpStatus.valueOf(e.status());
            return send(s, s == HttpStatus.NOT_FOUND ? "not_found" : "conflict", cut > 0 ? msg.substring(cut + 2) : msg, null, req);
        }
        return send(HttpStatus.BAD_GATEWAY, e.service() + "_refused", e.getMessage(), null, req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e, HttpServletRequest req) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        e.getBindingResult().getGlobalErrors().forEach(g -> fields.putIfAbsent(g.getObjectName(), g.getDefaultMessage()));
        return send(HttpStatus.BAD_REQUEST, "validation_failed", "Please fix the highlighted fields", fields, req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e, HttpServletRequest req) {
        for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
            if (t instanceof DatabindException d && !d.getPath().isEmpty() && d.getPath().getLast().getPropertyName() != null) {
                String f = d.getPath().getLast().getPropertyName();
                return send(HttpStatus.BAD_REQUEST, "validation_failed", "Please fix the highlighted fields", Map.of(f, "This value has the wrong type"), req);
            }
        }
        return send(HttpStatus.BAD_REQUEST, "malformed_request", "The request body is not valid JSON", null, req);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ApiError> badParam(Exception e, HttpServletRequest req) {
        String name = e instanceof MethodArgumentTypeMismatchException m ? m.getName() : ((MissingServletRequestParameterException) e).getParameterName();
        return send(HttpStatus.BAD_REQUEST, "bad_parameter", "Invalid or missing value for '" + name + "'", Map.of(name, "Invalid value"), req);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
        return send(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", e.getMethod() + " is not supported here", null, req);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest req) {
        return send(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "Send JSON with Content-Type: application/json", null, req);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noRoute(NoResourceFoundException e, HttpServletRequest req) {
        return send(HttpStatus.NOT_FOUND, "not_found", "No such endpoint", null, req);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> other(Exception e, HttpServletRequest req) {
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), e);
        return send(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Something went wrong on our side", null, req);
    }
}
