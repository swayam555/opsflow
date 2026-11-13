package app.opsflow.ticket;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Bearer-token access to /api and the legacy /tickets endpoints. The operator token can do everything; the
 * optional viewer token can only read. X-Actor names the person for the audit trail.
 */
@Component
public class Access extends OncePerRequestFilter {
    public enum Role { OPERATOR, VIEWER }

    static final String ROLE = "opsflow.role";
    static final String ACTOR = "opsflow.actor";

    private final byte[] operator;
    private final byte[] viewer;

    public Access(@Value("${opsflow.api.token}") String operator, @Value("${opsflow.api.viewer-token:}") String viewer) {
        if (operator == null || operator.length() < 12) {
            throw new IllegalStateException("opsflow.api.token (OPSFLOW_API_TOKEN) must be at least 12 characters");
        }
        this.operator = operator.getBytes(StandardCharsets.UTF_8);
        this.viewer = viewer == null || viewer.isBlank() ? null : viewer.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        return !(p.startsWith("/api/") || p.equals("/tickets") || p.startsWith("/tickets/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        byte[] given = h != null && h.startsWith("Bearer ") ? h.substring(7).trim().getBytes(StandardCharsets.UTF_8) : null;
        Role role = given == null ? null
                : MessageDigest.isEqual(operator, given) ? Role.OPERATOR
                : viewer != null && MessageDigest.isEqual(viewer, given) ? Role.VIEWER : null;
        if (role == null) {
            deny(res, 401, "unauthorized", given == null ? "Send your API token as 'Authorization: Bearer <token>'" : "That API token is not valid", req);
            return;
        }
        if (role == Role.VIEWER && !req.getMethod().equals("GET")) {
            deny(res, 403, "forbidden", "The viewer token is read-only", req);
            return;
        }
        req.setAttribute(ROLE, role);
        req.setAttribute(ACTOR, actor(req.getHeader("X-Actor"), role));
        chain.doFilter(req, res);
    }

    static String actor(String header, Role role) {
        if (header != null) {
            String a = header.replaceAll("[^\\p{L}\\p{N} ._@()-]", "").trim();
            if (!a.isEmpty()) return a.length() > 60 ? a.substring(0, 60) : a;
        }
        return role.name().toLowerCase();
    }

    private static void deny(HttpServletResponse res, int status, String code, String msg, HttpServletRequest req) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.getWriter().write("{\"status\":" + status + ",\"error\":\"" + code + "\",\"message\":\"" + msg + "\",\"path\":\""
                + req.getRequestURI().replaceAll("[\"\\\\]", "") + "\"}");
    }
}
