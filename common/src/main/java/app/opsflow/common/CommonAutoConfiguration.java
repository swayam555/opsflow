package app.opsflow.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * Gives every service the shared error format and the internal-token check.
 * {@code opsflow.internal.paths} lists the protected paths (all of them by default; ticket-service narrows it to /internal/**).
 */
@AutoConfiguration
@Import(ErrorHandler.class)
public class CommonAutoConfiguration {
    @Bean
    FilterRegistrationBean<InternalToken> internalTokenFilter(@Value("${opsflow.internal.token:opsflow-internal-dev}") String token,
                                                             @Value("${opsflow.internal.paths:/**}") List<String> paths) {
        if (token.length() < 16) throw new IllegalStateException("opsflow.internal.token (OPSFLOW_INTERNAL_TOKEN) must be at least 16 characters");
        var reg = new FilterRegistrationBean<>(new InternalToken(token, paths));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return reg;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.dao.DataIntegrityViolationException")
    @Import(DataErrorHandler.class)
    static class DataErrors {}
}
