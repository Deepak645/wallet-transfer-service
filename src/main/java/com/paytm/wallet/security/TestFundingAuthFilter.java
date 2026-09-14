package com.paytm.wallet.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.wallet.exception.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Guards the TEST-ONLY funding endpoint
 * ({@code com.paytm.wallet.controller.TestFundingController}). Only
 * registered at all when {@code test.funding.enabled=true} (see
 * {@code FilterConfig}), and mapped only to {@code /test/*} - it has no
 * effect on any other endpoint's authentication.
 *
 * Requires {@code Authorization: Bearer <the configured TEST_FUNDING_TOKEN>}
 * exactly; missing, malformed, or wrong values are all rejected the same
 * way (401). The token is compared in constant time and is never logged,
 * including in error responses.
 */
public class TestFundingAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final String expectedToken;
    private final ObjectMapper objectMapper;

    public TestFundingAuthFilter(String expectedToken, ObjectMapper objectMapper) {
        this.expectedToken = expectedToken;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String provided = (header != null && header.startsWith(BEARER_PREFIX))
                ? header.substring(BEARER_PREFIX.length()).trim()
                : "";

        if (!isValidToken(provided)) {
            writeUnauthorized(request, response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isValidToken(String provided) {
        if (provided.isEmpty() || expectedToken.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expectedToken.getBytes(StandardCharsets.UTF_8));
    }

    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError error = new ApiError(
                Instant.now(),
                HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                "Missing or invalid test funding token",
                request.getRequestURI(),
                MDC.get("correlationId")
        );
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}
