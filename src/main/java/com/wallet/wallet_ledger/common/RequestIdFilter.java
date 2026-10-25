package com.wallet.wallet_ledger.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Gives every request a unique id: stored on the request, added to logs, returned as a header. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = "requestId";
    public static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        MDC.put(ATTRIBUTE, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(ATTRIBUTE);
        }
    }
}