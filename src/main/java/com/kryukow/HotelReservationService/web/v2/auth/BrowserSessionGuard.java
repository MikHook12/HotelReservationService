package com.kryukow.HotelReservationService.web.v2.auth;

import com.kryukow.HotelReservationService.web.v2.common.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Clock;

public final class BrowserSessionGuard extends OncePerRequestFilter {
    private final BrowserIdentityStore identities;
    private final Clock clock;
    private final V2Errors errors;
    public BrowserSessionGuard(BrowserIdentityStore identities, Clock clock, V2Errors errors) {
        this.identities = identities; this.clock = clock; this.errors = errors;
    }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        HttpSession session = req.getSession(false);
        long now = clock.instant().getEpochSecond();
        boolean invalid = false;
        if (auth != null && auth.getPrincipal() instanceof V2Principal principal) {
            var identity = identities.byId(principal.userId());
            invalid = session == null || identity.isEmpty()
                    || identity.get().version() != principal.securityVersion()
                    || !identity.get().username().equals(principal.username())
                    || !identity.get().role().equals(principal.role());
            if (!invalid) {
                long login = BrowserSessionState.epoch(session, BrowserSessionState.LOGIN, -1);
                long last = BrowserSessionState.epoch(session, BrowserSessionState.LAST, -1);
                invalid = login < 0 || last < 0 || now >= login + 43200 || now >= last + 1800;
            }
            if (!invalid) session.setAttribute(BrowserSessionState.LAST, now);
        } else if (session != null) {
            long anonymous = BrowserSessionState.epoch(session, BrowserSessionState.ANONYMOUS, -1);
            invalid = anonymous >= 0 && now >= anonymous + 1800;
        }
        if (invalid) {
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
        }
        boolean publicPath = req.getRequestURI().matches(".*/api/v2/(cities|hotels(?:/.*)?|rooms/[^/]+|media/[^/]+|auth/(csrf|login|logout|register))");
        auth = SecurityContextHolder.getContext().getAuthentication();
        if (!publicPath && (auth == null || !(auth.getPrincipal() instanceof V2Principal))) {
            errors.write(req, res, 401, "AUTHENTICATION_REQUIRED", "Войдите, чтобы продолжить.");
            return;
        }
        chain.doFilter(req, res);
    }
}
