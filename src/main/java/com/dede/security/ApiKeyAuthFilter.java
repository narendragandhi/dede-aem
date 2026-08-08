package com.dede.security;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Requires an API key on every request to the REST/GraphQL API surface. Before this
 * existed, every endpoint (including the /api/graph/scan endpoint added earlier this
 * session) was reachable by any caller who could reach the port -- confirmed directly
 * against a running server, not assumed. This closes that gap for good rather than
 * per-endpoint.
 *
 * Fails closed always: unlike DEDE_SCAN_ALLOWED_ROOT (which disables one specific
 * endpoint until configured), there is no "unauthenticated by default" state here. If
 * DEDE_API_KEY isn't set, a random key is generated at startup and logged once --
 * Jenkins'/Home Assistant's first-run pattern -- so the server is never silently wide
 * open, but nobody is hard-blocked from local/dev use either.
 *
 * /actuator/health stays exempt: the Dockerfile's HEALTHCHECK calls it with plain
 * curl and has no way to supply a key.
 *
 * @ConditionalOnWebApplication(SERVLET) scopes this bean to contexts that actually
 * serve HTTP. Without it, the Maven plugin's headless Spring context (see
 * MojoBootstrapConfig, WebApplicationType.NONE) still component-scans com.dede and
 * would construct this bean anyway -- generating and logging a one-time API key that
 * means nothing, since there's no HTTP server in that context for anyone to present
 * it to. Confirmed by actually running the plugin before adding this guard: every
 * Maven build printed an irrelevant key warning.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);
    private static final String API_KEY_HEADER = "X-Dede-Api-Key";

    @Value("${dede.api.key:}")
    private String configuredKey;

    private volatile String effectiveKey;

    @PostConstruct
    void init() {
        if (configuredKey != null && !configuredKey.isBlank()) {
            effectiveKey = configuredKey;
            log.info("API key authentication enabled (DEDE_API_KEY configured).");
            return;
        }

        effectiveKey = generateKey();
        log.warn("");
        log.warn("=================================================================");
        log.warn(" No DEDE_API_KEY configured. Generated a one-time key for this run:");
        log.warn("   {}", effectiveKey);
        log.warn(" Set DEDE_API_KEY to use a fixed key across restarts. Include it on");
        log.warn(" every request as the '{}' header.", API_KEY_HEADER);
        log.warn("=================================================================");
        log.warn("");
    }

    private String generateKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().equals("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String provided = request.getHeader(API_KEY_HEADER);
        if (provided == null) {
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
                provided = authHeader.substring(7);
            }
        }

        if (provided != null && constantTimeEquals(provided, effectiveKey)) {
            filterChain.doFilter(request, response);
            return;
        }

        log.warn("Rejected unauthenticated request to {} {}", request.getMethod(), request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
            "{\"status\":401,\"error\":\"Unauthorized\","
            + "\"message\":\"Missing or invalid API key. Supply it via the "
            + API_KEY_HEADER + " header.\"}"
        );
    }

    /**
     * Plain String.equals() short-circuits on the first differing character, which
     * makes response time leak information about how many leading characters of a
     * guess are correct -- a real timing side-channel for the one thing standing
     * between this API and anyone who can reach it. MessageDigest.isEqual() is
     * documented to run in time independent of where strings first differ.
     */
    private boolean constantTimeEquals(String provided, String expected) {
        if (provided == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
            provided.getBytes(StandardCharsets.UTF_8),
            expected.getBytes(StandardCharsets.UTF_8)
        );
    }

    /** Test-only: avoids depending on @Value injection or @PostConstruct in unit tests. */
    void setEffectiveKeyForTest(String key) {
        this.effectiveKey = key;
    }
}
