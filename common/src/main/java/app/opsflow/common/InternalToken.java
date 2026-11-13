package app.opsflow.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Service-to-service calls carry {@code X-Internal-Token}. Requests to protected paths without the right token
 * get 401, so the action services cannot be driven by anything that only reached them over the network.
 */
public class InternalToken extends OncePerRequestFilter {
    public static final String HEADER = "X-Internal-Token";
    private static final AntPathMatcher PATHS = new AntPathMatcher();

    private final byte[] token;
    private final List<String> protectedPaths;

    public InternalToken(String token, List<String> protectedPaths) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.protectedPaths = protectedPaths;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getRequestURI();
        if (path.startsWith("/actuator/health")) return true;
        return protectedPaths.stream().noneMatch(p -> PATHS.match(p, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String given = req.getHeader(HEADER);
        if (given != null && MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(req, res);
            return;
        }
        res.setStatus(401);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.getWriter().write("{\"status\":401,\"error\":\"unauthorized\",\"message\":\"Internal endpoint: missing or wrong X-Internal-Token\",\"path\":\""
                + req.getRequestURI().replace("\"", "") + "\"}");
    }
}
