package com.kryukow.HotelReservationService.web.v2.common;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.util.*;

/** Security-chain-local filter: never registered globally or applied to legacy requests. */
public final class V2HttpFilter extends OncePerRequestFilter {
    public static final String TRACE_ID_ATTRIBUTE = "v2.traceId";
    private final V2Errors errors;
    public V2HttpFilter(V2Errors errors) { this.errors = errors; }
    private Set<String> queries(String path) {
        if (path.equals("/api/v2/admin/hotels") || path.matches("/api/v2/admin/hotels/[1-9][0-9]*/rooms")) return Set.of("page","size");
        if (path.equals("/api/v2/hotels")) return Set.of("city","checkInDate","checkOutDate","guests","priceMin","priceMax","page","size","sort");
        if (path.matches("/api/v2/hotels/[^/]+/rooms")) return Set.of("checkInDate","checkOutDate","guests","priceMin","priceMax","page","size","sort");
        if (path.matches("/api/v2/rooms/[^/]+")) return Set.of("checkInDate","checkOutDate","guests");
        if (path.equals("/api/v2/me/bookings") || path.equals("/api/v2/admin/bookings")) return Set.of("page","size","sort","status");
        return Set.of();
    }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        V2Errors.headers(req, res);
        try {
            boolean upload = req.getRequestURI().equals("/api/v2/admin/media") && req.getMethod().equals("POST");
            if (upload) {
                if (req.getQueryString() != null && !req.getQueryString().isEmpty()) throw new V2ApiException(400,"VALIDATION_FAILED","Параметры загрузки не допускаются.");
                if (req.getContentType() == null || !MediaType.MULTIPART_FORM_DATA.isCompatibleWith(MediaType.parseMediaType(req.getContentType())))
                    throw new V2ApiException(415,"UNSUPPORTED_MEDIA_TYPE","Требуется multipart-загрузка.");
                for (String header : List.of("Origin","X-CSRF-TOKEN")) if (Collections.list(req.getHeaders(header)).size() > 1)
                    throw new V2ApiException(400,"VALIDATION_FAILED","Повтор заголовка недопустим.");
                if (req.getContentLengthLong() > 6 * 1024 * 1024) throw new V2ApiException(413,"PAYLOAD_TOO_LARGE","Файл слишком большой.");
                // Leave the stream to the servlet multipart resolver; CSRF is supplied in a header.
                chain.doFilter(req, res);
                return;
            }
            Set<String> allowed = queries(req.getRequestURI());
            // JSON APIs never accept form parameters; query decoding is therefore safe before reading body.
            String type = req.getContentType();
            if (type != null) {
                MediaType parsed = MediaType.parseMediaType(type);
                if (!MediaType.APPLICATION_JSON.isCompatibleWith(parsed)
                        || parsed.getCharset() != null && !parsed.getCharset().equals(java.nio.charset.StandardCharsets.UTF_8))
                    throw new V2ApiException(415, "UNSUPPORTED_MEDIA_TYPE", "Требуется JSON UTF-8.");
            }
            for (var entry : req.getParameterMap().entrySet())
                if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1)
                    throw new V2ApiException(400, "VALIDATION_FAILED", "Проверьте параметры запроса.");
            for (String header : List.of("Origin","X-CSRF-TOKEN","Idempotency-Key"))
                if (Collections.list(req.getHeaders(header)).size() > 1)
                    throw new V2ApiException(400,"VALIDATION_FAILED","Повтор заголовка недопустим.");
            int limit = req.getRequestURI().startsWith("/api/v2/admin/") ? 131072 : 16384;
            byte[] body = req.getInputStream().readNBytes(limit + 1);
            if (body.length > limit) throw new V2ApiException(413,"PAYLOAD_TOO_LARGE","Слишком большой запрос.");
            if (body.length > 0 && type == null) throw new V2ApiException(415,"UNSUPPORTED_MEDIA_TYPE","Требуется JSON.");
            try { java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(body));
            } catch (java.nio.charset.CharacterCodingException ex) {
                throw new V2ApiException(400,"MALFORMED_JSON","Требуется JSON UTF-8.");
            }
            String path = req.getRequestURI();
            if (body.length > 0 && (req.getMethod().equals("GET") || req.getMethod().equals("HEAD")
                    || path.endsWith("/cancel") || path.endsWith("/logout") || path.endsWith("/fence")))
                throw new V2ApiException(400,"VALIDATION_FAILED","Тело запроса не допускается.");
            chain.doFilter(new Cached(req, body), res);
        } catch (V2ApiException ex) {
            if (!res.isCommitted()) errors.write(req,res,ex.getStatus(),ex.getCode(),ex.getMessage(),ex.getFieldErrors(),ex.getDetails());
        } catch (org.springframework.dao.DataAccessException ex) {
            if (!res.isCommitted()) errors.write(req,res,503,"SERVICE_UNAVAILABLE","Сервис временно недоступен.");
        } catch (IllegalArgumentException ex) {
            if (!res.isCommitted()) errors.write(req,res,400,"VALIDATION_FAILED","Некорректный запрос.");
        } catch (Exception ex) {
            if (!res.isCommitted()) errors.write(req,res,500,"INTERNAL_ERROR","Не удалось выполнить запрос.");
        }
    }
    private static final class Cached extends HttpServletRequestWrapper {
        private final byte[] body;
        private Cached(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream stream = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                public int read() { return stream.read(); }
                public boolean isFinished() { return stream.available() == 0; }
                public boolean isReady() { return true; }
                public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            };
        }
        @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8)); }
    }
}
