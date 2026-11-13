package app.opsflow.ticket;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Browser hardening for the console, and no caching of API answers. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeaders extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        res.setHeader("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Referrer-Policy", "no-referrer");
        String p = req.getRequestURI();
        if (p.startsWith("/api/") || p.startsWith("/tickets") || p.startsWith("/internal/")) res.setHeader("Cache-Control", "no-store");
        else res.setHeader("Cache-Control", "no-cache");
        chain.doFilter(req, res);
    }
}
