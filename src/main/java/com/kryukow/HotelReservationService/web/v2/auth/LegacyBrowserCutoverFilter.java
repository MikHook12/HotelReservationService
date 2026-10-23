package com.kryukow.HotelReservationService.web.v2.auth;

import com.kryukow.HotelReservationService.web.v2.common.V2Errors;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Arrays;

public final class LegacyBrowserCutoverFilter extends OncePerRequestFilter {
    private final V2Errors errors;
    public LegacyBrowserCutoverFilter(V2Errors errors) { this.errors=errors; }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)
            throws ServletException,IOException {
        String path=req.getRequestURI();
        if ("POST".equals(req.getMethod()) && (path.matches("/api/v1/auth/register/?") || path.matches("/api/v1/bookings/?"))) {
            errors.write(req,res,410,"LEGACY_WRITE_CLOSED","Используйте браузерный API /api/v2 с новым расчётом и подтверждением."); return;
        }
        boolean cookie=req.getCookies()!=null && Arrays.stream(req.getCookies()).anyMatch(c->c.getName().equals("__Host-hotel-session"));
        if (CsrfFilter.DEFAULT_CSRF_MATCHER.matches(req) && (cookie || req.getHeader("Origin")!=null
                || req.getHeader("Referer")!=null || req.getHeader("Sec-Fetch-Site")!=null)) {
            errors.write(req,res,403,"FORBIDDEN","Браузерные изменения доступны только через /api/v2."); return;
        }
        chain.doFilter(req,res);
    }
}
