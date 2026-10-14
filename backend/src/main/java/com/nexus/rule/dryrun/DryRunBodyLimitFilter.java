package com.nexus.rule.dryrun;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;

/**
 * An EARLY refusal for a request that declares an oversized Content-Length, so the body is not even read. It is an optimization and a second
 * line of defence only: the authoritative bound is the bounded read inside {@link RuleDryRunController}, which holds for every URI spelling,
 * with or without a Content-Length. The path is normalized the way Spring MVC routes it (path parameters removed, percent-decoding, duplicate
 * and dot segments collapsed) so that equivalent spellings are treated alike.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DryRunBodyLimitFilter extends OncePerRequestFilter {
    private static final UrlPathHelper PATHS = new UrlPathHelper();
    static { PATHS.setRemoveSemicolonContent(true); PATHS.setUrlDecode(true); }

    static String normalized(HttpServletRequest request) {
        String path = PATHS.getRequestUri(request).replaceAll("/{2,}", "/");
        var segments = new java.util.ArrayDeque<String>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) { if (!segments.isEmpty()) segments.removeLast(); continue; }
            segments.addLast(segment);
        }
        return "/" + String.join("/", segments);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || !"/api/v1/rules/dry-run".equals(normalized(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (request.getContentLengthLong() > DryRunService.MAX_INPUT_BYTES) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":413,\"error\":\"Payload Too Large\",\"code\":\"INPUT_TOO_LARGE\",\"message\":\"Dry-run input is limited to "
                + DryRunService.MAX_INPUT_BYTES + " bytes; nothing was evaluated.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
