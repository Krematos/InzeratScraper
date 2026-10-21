package hanzner.inzeratscraper.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

@Configuration
public class SecurityConfig {

    private static final String HEADER_API_KEY = "X-API-Key";
    private static final Set<String> LOOPBACK_ADDRESSES = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

    private final String migrationApiKey;
    private final boolean h2ConsoleEnabled;

    public SecurityConfig(
            @Value("${migration.api.key:}") String migrationApiKey,
            @Value("${spring.h2.console.enabled:false}") boolean h2ConsoleEnabled) {
        this.migrationApiKey = migrationApiKey != null ? migrationApiKey.trim() : "";
        this.h2ConsoleEnabled = h2ConsoleEnabled;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin))
                .addFilterBefore(apiKeyOrLoopbackFilter(), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> {
                    if (h2ConsoleEnabled) {
                        auth.requestMatchers("/h2-console/**").access((authentication, context) ->
                                new AuthorizationDecision(isLoopbackRequest(context.getRequest())));
                    } else {
                        auth.requestMatchers("/h2-console/**").denyAll();
                    }
                    auth.requestMatchers("/api/migration/**").access((authentication, context) ->
                            new AuthorizationDecision(isAuthorizedMigrationRequest(context.getRequest())));
                    auth.anyRequest().denyAll();
                });
        return http.build();
    }

    private OncePerRequestFilter apiKeyOrLoopbackFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(
                    HttpServletRequest request,
                    HttpServletResponse response,
                    FilterChain filterChain) throws ServletException, IOException {
                String path = request.getRequestURI();
                if (path != null && path.startsWith("/api/migration/") && !isAuthorizedMigrationRequest(request)) {
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.getWriter().write("{\"error\":\"Unauthorized: invalid or missing X-API-Key or non-local request.\"}");
                    return;
                }
                filterChain.doFilter(request, response);
            }
        };
    }

    public boolean isAuthorizedMigrationRequest(HttpServletRequest request) {
        // Pokud je nastaven migration.api.key, vyžadujeme platnou hlavičku X-API-Key
        if (!migrationApiKey.isEmpty()) {
            String providedKey = request.getHeader(HEADER_API_KEY);
            if (providedKey == null || providedKey.isBlank()) {
                return false;
            }
            return MessageDigest.isEqual(
                    migrationApiKey.getBytes(StandardCharsets.UTF_8),
                    providedKey.trim().getBytes(StandardCharsets.UTF_8)
            );
        }
        // Jinak povolíme přístup výhradně z loopback rozhraní (127.0.0.1 / ::1)
        return isLoopbackRequest(request);
    }

    public boolean isLoopbackRequest(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr != null && LOOPBACK_ADDRESSES.contains(remoteAddr);
    }
}
