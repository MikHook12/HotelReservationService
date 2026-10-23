package com.kryukow.HotelReservationService.web.v2.auth;

import com.kryukow.HotelReservationService.web.v2.common.V2Errors;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;

public final class BrowserOriginFilter extends OncePerRequestFilter {
    private final V2Errors errors;
    public BrowserOriginFilter(V2Errors errors) { this.errors = errors; }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        String origin = req.getHeader("Origin");
        if (CsrfFilter.DEFAULT_CSRF_MATCHER.matches(req) && origin != null) {
            boolean same = false;
            try {
                URI uri = URI.create(origin);
                int expected = req.getServerPort();
                int actual = uri.getPort() >= 0 ? uri.getPort() : "https".equals(uri.getScheme()) ? 443 : 80;
                same = uri.getScheme() != null && uri.getScheme().equals(req.getScheme())
                        && req.getServerName().equalsIgnoreCase(uri.getHost()) && actual == expected
                        && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                        && (uri.getRawPath() == null || uri.getRawPath().isEmpty());
            } catch (IllegalArgumentException ignored) { }
            if (!same) { errors.write(req,res,403,"CSRF_INVALID","Недопустимый источник запроса."); return; }
        }
        chain.doFilter(req,res);
    }
}
