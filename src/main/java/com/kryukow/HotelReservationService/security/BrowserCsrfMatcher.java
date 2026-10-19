package com.kryukow.HotelReservationService.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** Keep CSRF for browser requests, including HTTP Basic's ambient browser credentials.
 * Non-browser JSON requests and explicit Basic requests with non-form content may omit the token.
 * CORS remains disabled: cross-origin JavaScript cannot use this exemption.
 */
public final class BrowserCsrfMatcher implements RequestMatcher {
    @Override
    public boolean matches(HttpServletRequest request) {
        if (!CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)) {
            return false;
        }
        if (request.getHeader("Origin") != null || request.getHeader("Referer") != null
                || request.getHeader("Sec-Fetch-Site") != null) {
            return true;
        }
        String authorization = request.getHeader("Authorization");
        boolean explicitBasic = authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6);
        String contentType = request.getContentType();
        if (contentType == null) {
            return !explicitBasic;
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            if (MediaType.APPLICATION_JSON.isCompatibleWith(type)) {
                return false; // Includes anonymous JSON registration from REST clients.
            }
            return !explicitBasic || MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(type)
                    || MediaType.MULTIPART_FORM_DATA.isCompatibleWith(type)
                    || MediaType.TEXT_PLAIN.isCompatibleWith(type);
        } catch (IllegalArgumentException ex) {
            return true;
        }
    }
}
