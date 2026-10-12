package com.researchdesk.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Metadata only: request paths, query strings, bodies and credentials are never logged. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestAuditFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE=RequestAuditFilter.class.getName()+".requestId";
    private static final Logger LOG=LoggerFactory.getLogger(RequestAuditFilter.class);

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String supplied=request.getHeader("X-Request-Id");
        // The private API accepts only the edge-generated, bounded identifier format.
        String requestId=supplied!=null && supplied.matches("(?:[0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")
            ? supplied : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE,requestId);
        response.setHeader("X-Request-Id",requestId);
        response.setHeader("X-Content-Type-Options","nosniff");
        String previous=MDC.get("requestId");
        MDC.put("requestId",requestId);
        long started=System.nanoTime();
        boolean failed=false;
        try { chain.doFilter(request,response); }
        catch (ServletException | IOException | RuntimeException failure) { failed=true;throw failure; }
        finally {
            String method=request.getMethod();
            // Do not permit arbitrary method tokens to inject content into logs.
            if (!java.util.Set.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS","TRACE","CONNECT").contains(method)) method="OTHER";
            LOG.info("HTTP_REQUEST requestId={} method={} status={} durationMs={}",requestId,method,
                failed ? 500 : response.getStatus(),(System.nanoTime()-started)/1_000_000);
            if(previous==null) MDC.remove("requestId"); else MDC.put("requestId",previous);
        }
    }
}
