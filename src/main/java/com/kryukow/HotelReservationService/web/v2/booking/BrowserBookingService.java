package com.kryukow.HotelReservationService.web.v2.booking;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.kryukow.HotelReservationService.model.*;
import com.kryukow.HotelReservationService.repository.*;
import com.kryukow.HotelReservationService.service.DomainEventPublisher;
import com.kryukow.HotelReservationService.web.v2.common.V2ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.*;
import java.math.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Room locking and durable operation receipts protect creation across HTTP retries and restarts. */
@Service
@ConditionalOnProperty(name="app.web.v2.enabled",havingValue="true")
public class BrowserBookingService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate write;
    private final TransactionTemplate read;
    private final RoomRepository rooms;
    private final UserRepository users;
    private final BookingRepository bookings;
    private final DomainEventPublisher events;
    private final ObjectMapper json;
    private final Clock clock;
    private final QuoteCipher cipher;
    private final com.kryukow.HotelReservationService.web.v2.auth.BrowserRateLimiter limits;

    public BrowserBookingService(JdbcTemplate jdbc, PlatformTransactionManager transactions, RoomRepository rooms,
            UserRepository users, BookingRepository bookings, DomainEventPublisher events, ObjectMapper json,
            Clock clock, com.kryukow.HotelReservationService.web.v2.auth.BrowserRateLimiter limits, @Value("${app.web.v2.quote-encryption-key:}") String key) {
        this.jdbc=jdbc; this.rooms=rooms; this.users=users; this.bookings=bookings; this.events=events; this.json=json; this.clock=clock;
        this.cipher=new QuoteCipher(key); this.limits=limits;
        write=new TransactionTemplate(transactions); write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read=new TransactionTemplate(transactions); read.setReadOnly(true); read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public record Reply(Map<String,Object> body, boolean replayed) {}
    private record Outcome(Map<String,Object> body, V2ApiException failure, boolean replayed) {}
    private record Terms(Room room, Map<String,Object> snapshot, Map<String,Object> pricing, Instant deadline, String version) {}

    public Reply quote(BookingSelection selection, UUID key) {
        long owner=owner(); limits.bookingAttempt(owner,"quote");
        String hash=fingerprint(selection,null);
        Outcome result=write.execute(tx -> {
            cleanupQuotes();
            Instant now=clock.instant();
            jdbc.update("INSERT INTO browser_booking_quotes(user_id,request_key,payload_hash,created_at,expires_at,retain_until) VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                    owner,key,hash,ts(now),ts(now.plusSeconds(600)),ts(now.plusSeconds(86400)));
            Map<String,Object> row=quoteRow(owner,key);
            if (!hash.equals(row.get("payload_hash"))) return fail(409,"IDEMPOTENCY_CONFLICT","Ключ уже связан с другим расчётом");
            if (row.get("quote_cipher")!=null) {
                if (!clock.instant().isBefore(instant(row,"expires_at"))) return fail(409,"QUOTE_EXPIRED","Расчёт истёк. Получите новый");
                return new Outcome(decodeProtected(row.get("quote_cipher")),null,true);
            }
            if (row.get("error_code")!=null) return new Outcome(null,storedError(row),true);
            try {
                Terms terms=terms(selection);
                Map<String,Object> q=saveQuote(owner,key,selection,terms);
                return new Outcome(q,null,false);
            } catch(V2ApiException e) {
                jdbc.update("UPDATE browser_booking_quotes SET error_status=?,error_code=?,error_cipher=? WHERE user_id=? AND request_key=?",
                        e.getStatus(),e.getCode(),cipher.encrypt(encode(error(e))),owner,key);
                return new Outcome(null,e,false);
            }
        });
        return reply(result);
    }

    public Reply create(BookingSelection selection, String quoteToken, UUID key) {
        long owner=owner(); limits.bookingAttempt(owner,"create");
        String hash=selection==null ? null : fingerprint(selection,quoteToken);
        UUID attempt=UUID.randomUUID();
        Map<String,Object> initial=write.execute(tx -> {
            if (selection!=null) {
                Instant now=clock.instant();
                jdbc.update("INSERT INTO browser_booking_operations(user_id,request_key,payload_hash,state,attempt_token,lease_until,created_at) VALUES(?,?,?,'PROCESSING',?,?,?) ON CONFLICT DO NOTHING",
                        owner,key,hash,attempt,ts(now.plusSeconds(30)),ts(now));
            }
            Map<String,Object> row=operation(owner,key,false);
            if(row==null) throw ex(400,"BODY_REQUIRED","Для новой операции нужны данные брони");
            compare(row,hash); return row;
        });
        if(!"PROCESSING".equals(initial.get("state")) || !attempt.equals(initial.get("attempt_token"))) {
            recoverIfExpired(owner,key);
            return reply(replay(operationRead(owner,key),hash));
        }
        try {
            return reply(write.execute(tx -> {
                Map<String,Object> op=operation(owner,key,true); compare(op,hash);
                if(!"PROCESSING".equals(op.get("state"))) return replay(op,hash);
                if(!attempt.equals(op.get("attempt_token")) || !clock.instant().isBefore(instant(op,"lease_until"))) {
                    abort(owner,key); return replay(operation(owner,key,false),hash);
                }
                try {
                    Terms t=terms(selection);
                    Map<String,Object> q=validateQuote(owner,selection,quoteToken);
                    if(!Objects.equals(t.version(),q.get("termsVersion"))) {
                        Map<String,Object> replacement=saveQuote(owner,UUID.randomUUID(),selection,t);
                        boolean price=!Objects.equals(t.pricing(),q.get("pricing"));
                        throw new V2ApiException(409,price?"PRICE_CHANGED":"TERMS_CHANGED",
                                "Условия изменились. Проверьте новый расчёт и подтвердите его",Map.of("replacementQuote",replacement));
                    }
                    Instant now=clock.instant();
                    // Availability and quote expiry were evaluated after the room lock above.
                    Long id=jdbc.queryForObject("""
                      INSERT INTO bookings(user_id,room_id,check_in_date,check_out_date,status,origin,guests,nightly_price,total_price,currency,nights,snapshot_json,time_zone,cancellation_deadline_at,created_at,timezone_provenance)
                      VALUES(?,?,?,?,'CONFIRMED','V2',?,?,?,'RUB',?,?,?,?,?,'CAPTURED_AT_CREATION') RETURNING id
                      """,Long.class,owner,selection.roomId(),selection.checkInDate(),selection.checkOutDate(),selection.guests(),
                            new BigDecimal((String)t.pricing().get("nightlyPrice")),new BigDecimal((String)t.pricing().get("totalPrice")),t.pricing().get("nights"),
                            encode(t.snapshot()),t.snapshot().get("timeZone"),ts(t.deadline()),ts(now));
                    events.publishBookingCreated(owner,selection.roomId(),selection.checkInDate(),selection.checkOutDate());
                    Map<String,Object> receipt=map("bookingId",id.toString(),"statusAtCreation","CONFIRMED","createdAt",now.toString(),
                            "stay",selection.stay(),"pricing",t.pricing(),"snapshot",t.snapshot(),"cancellationDeadlineAt",t.deadline().toString(),"bookingUrl","/api/v2/me/bookings/"+id);
                    jdbc.update("UPDATE browser_booking_operations SET state='SUCCEEDED',completed_at=?,booking_id=?,receipt_json=? WHERE user_id=? AND request_key=? AND state='PROCESSING' AND attempt_token=?",
                            ts(now),id,encode(receipt),owner,key,attempt);
                    return new Outcome(receipt,null,false);
                } catch(V2ApiException e) {
                    failed(owner,key,e); return new Outcome(null,e,false);
                }
            }));
        } catch (V2ApiException e) { throw e; }
        catch (RuntimeException unexpected) {
            // Resolve the actual database outcome; a lost commit acknowledgement is never a second create.
            try {
                Outcome outcome=write.execute(tx -> {
                    Map<String,Object> row=operation(owner,key,true);
                    if("PROCESSING".equals(row.get("state"))) abort(owner,key);
                    return replay(operation(owner,key,false),hash);
                });
                return reply(outcome);
            } catch(V2ApiException e) { throw e; }
            catch(RuntimeException unresolved) { throw ex(503,"SERVICE_UNAVAILABLE","Результат пока неизвестен. Проверьте исходную операцию"); }
        }
    }

    public Map<String,Object> result(UUID key) {
        long owner=owner(); limits.bookingAttempt(owner,"result"); recoverIfExpired(owner,key);
        Map<String,Object> op=operationRead(owner,key);
        if(op==null) throw ex(404,"NOT_FOUND","Операция не найдена");
        return operationDto(op,key);
    }
    public Map<String,Object> fence(UUID key) {
        long owner=owner(); limits.bookingAttempt(owner,"fence");
        write.executeWithoutResult(tx -> {
            Instant now=clock.instant();
            jdbc.update("""
                INSERT INTO browser_booking_operations(user_id,request_key,state,attempt_token,lease_until,created_at,completed_at,error_status,error_code)
                VALUES(?,?,'FAILED',?,?,?,?,503,'REQUEST_ABORTED') ON CONFLICT DO NOTHING
                """,owner,key,UUID.randomUUID(),ts(now),ts(now),ts(now));
        });
        recoverIfExpired(owner,key);
        return operationDto(operationRead(owner,key),key);
    }
    private void recoverIfExpired(long owner, UUID key) {
        Map<String,Object> observed=operationRead(owner,key);
        if(observed==null || !"PROCESSING".equals(observed.get("state")) || clock.instant().isBefore(instant(observed,"lease_until"))) return;
        write.executeWithoutResult(tx -> {
            // SKIP LOCKED leaves an active transaction unknown instead of timing it out as failure.
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM browser_booking_operations WHERE user_id=? AND request_key=? FOR UPDATE SKIP LOCKED",owner,key);
            if(!rows.isEmpty() && "PROCESSING".equals(rows.getFirst().get("state")) && !clock.instant().isBefore(instant(rows.getFirst(),"lease_until"))) abort(owner,key);
        });
    }
    private void abort(long owner,UUID key) { failed(owner,key,ex(503,"REQUEST_ABORTED","Операция завершена без создания брони. Проверьте выбор заново")); }
    private void failed(long owner,UUID key,V2ApiException e) {
        jdbc.update("UPDATE browser_booking_operations SET state='FAILED',completed_at=?,error_status=?,error_code=?,error_cipher=? WHERE user_id=? AND request_key=? AND state='PROCESSING'",
                ts(clock.instant()),e.getStatus(),e.getCode(),cipher.encrypt(encode(error(e))),owner,key);
    }
    private Outcome replay(Map<String,Object> op,String hash) {
        if(op==null) return fail(404,"NOT_FOUND","Операция не найдена");
        compare(op,hash);
        return switch((String)op.get("state")) {
            case "SUCCEEDED" -> new Outcome(decode((String)op.get("receipt_json")),null,true);
            case "FAILED" -> new Outcome(null,storedError(op),true);
            default -> new Outcome(null,new V2ApiException(409,"OPERATION_IN_PROGRESS","Подтверждение ещё обрабатывается",
                    Map.of("retryAfterSeconds",2,"operationUrl","/api/v2/me/booking-operations/result")),true);
        };
    }
    private void compare(Map<String,Object> op,String hash) {
        // A fence with null fingerprint permanently closes the key for every delayed body.
        if(hash!=null && op.get("payload_hash")!=null && !hash.equals(op.get("payload_hash")))
            throw ex(409,"IDEMPOTENCY_CONFLICT","Ключ уже связан с другим запросом");
    }
    private Map<String,Object> operationDto(Map<String,Object> op,UUID key) {
        Map<String,Object> result=map("key",key.toString(),"operation","CREATE_BOOKING","createdAt",instant(op,"created_at").toString(),"state",op.get("state"));
        if("PROCESSING".equals(op.get("state"))) result.put("nextCheckAfterSeconds",2);
        else {
            result.put("completedAt",instant(op,"completed_at").toString());
            if("SUCCEEDED".equals(op.get("state"))) result.put("receipt",decode((String)op.get("receipt_json")));
            else { result.put("errorCode",op.get("error_code")); result.put("errorStatus",op.get("error_status"));
                result.put("error",op.get("error_cipher")==null?null:error(storedError(op))); }
        }
        return result;
    }

    private Map<String,Object> validateQuote(long owner,BookingSelection selection,String token) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM browser_booking_quotes WHERE user_id=? AND token_hash=? AND retain_until>?",owner,QuoteCipher.hash(token),ts(clock.instant()));
        if(rows.isEmpty()) throw ex(409,"QUOTE_INVALID","Расчёт недействителен. Получите новый");
        Map<String,Object> row=rows.getFirst();
        if(!clock.instant().isBefore(instant(row,"expires_at"))) throw ex(409,"QUOTE_EXPIRED","Расчёт истёк. Получите новый");
        Map<String,Object> quote=decodeProtected(row.get("quote_cipher"));
        if(!selection.stay().equals(quote.get("stay"))) throw ex(409,"QUOTE_SELECTION_MISMATCH","Расчёт относится к другому выбору");
        return quote;
    }
    private Map<String,Object> saveQuote(long owner,UUID key,BookingSelection s,Terms t) {
        String token=cipher.token(); Instant now=clock.instant();
        Map<String,Object> q=map("quoteToken",token,"stay",s.stay(),"pricing",t.pricing(),"snapshot",t.snapshot(),"termsVersion",t.version(),
                "createdAt",now.toString(),"expiresAt",now.plusSeconds(600).toString(),"cancellationDeadlineAt",t.deadline().toString(),"holdsRoom",false);
        jdbc.update("""
            INSERT INTO browser_booking_quotes(user_id,request_key,payload_hash,token_hash,quote_cipher,created_at,expires_at,retain_until)
            VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(user_id,request_key) DO UPDATE SET token_hash=excluded.token_hash,quote_cipher=excluded.quote_cipher,created_at=excluded.created_at,expires_at=excluded.expires_at,retain_until=excluded.retain_until
            """,owner,key,fingerprint(s,null),QuoteCipher.hash(token),cipher.encrypt(encode(q)),ts(now),ts(now.plusSeconds(600)),ts(now.plusSeconds(86400)));
        return q;
    }
    private Terms terms(BookingSelection s) {
        Room r=rooms.findByIdForUpdate(s.roomId()).orElseThrow(()->ex(404,"NOT_FOUND","Комната не найдена"));
        Hotel h=r.getHotel();
        if(!Boolean.TRUE.equals(r.getPublished()) || !Boolean.TRUE.equals(h.getPublished()) || !Boolean.TRUE.equals(h.getDemo())
                || h.getTimeZone()==null || h.getSlug()==null) throw ex(409,"ROOM_NOT_BOOKABLE","Комната пока не доступна для бронирования");
        ZoneId zone;
        try { zone=ZoneId.of(h.getTimeZone()); } catch(DateTimeException e) { throw ex(409,"ROOM_NOT_BOOKABLE","Не настроен часовой пояс отеля"); }
        LocalDate today=LocalDate.now(clock.withZone(zone)); long n=ChronoUnit.DAYS.between(s.checkInDate(),s.checkOutDate());
        if(!s.checkInDate().isAfter(today) || s.checkOutDate().isAfter(today.plusDays(365)) || n<1 || n>30)
            throw ex(400,"DATE_RANGE_INVALID","Заезд от завтра, от 1 до 30 ночей, выезд в пределах 365 дней");
        if(s.guests()>r.getCapacity()) throw ex(409,"CAPACITY_EXCEEDED","В комнате недостаточно мест");
        BigDecimal nightly;
        try { nightly=r.getPrice().setScale(2,RoundingMode.UNNECESSARY); }
        catch(ArithmeticException e) { throw ex(409,"ROOM_NOT_BOOKABLE","Тариф комнаты требует проверки"); }
        if(nightly.signum()<=0 || nightly.compareTo(new BigDecimal("9999999999.99"))>0) throw ex(409,"ROOM_NOT_BOOKABLE","Тариф комнаты требует проверки");
        if(bookings.existsOverlappingBooking(r.getId(),s.checkInDate(),s.checkOutDate())) throw ex(409,"ROOM_UNAVAILABLE","Комната занята на выбранные даты");
        Map<String,Object> pricing=map("nightlyPrice",nightly.toPlainString(),"nights",(int)n,"totalPrice",nightly.multiply(BigDecimal.valueOf(n)).toPlainString(),"currency","RUB","feesIncluded",true);
        Map<String,Object> snapshot=map("hotelId",h.getId().toString(),"hotelName",h.getName(),"hotelSlug",h.getSlug(),"roomId",r.getId().toString(),"roomName",r.getName(),
                "hotelAddress",h.getAddress(),"cityName",h.getCity(),"timeZone",zone.getId(),"isDemo",true);
        Instant deadline=s.checkInDate().atStartOfDay(zone).toInstant();
        String version=QuoteCipher.hash(canonical(map("pricing",pricing,"snapshot",snapshot,"capacity",r.getCapacity(),"deadline",deadline.toString())));
        return new Terms(r,snapshot,pricing,deadline,version);
    }

    public Map<String,Object> get(String id,boolean admin) {
        long owner=owner(); if(admin) requireAdmin(); long bookingId=BookingSelection.id(id);
        return read.execute(tx -> bookingRow(bookingId,owner,admin));
    }
    public Map<String,Object> list(int page,int size,String status,String sort,boolean admin) {
        long owner=owner(); if(admin) requireAdmin();
        if(page<0 || size<1 || size>50 || (!Set.of("createdDesc","checkInAsc").contains(sort)) || (status!=null && !Set.of("CONFIRMED","CANCELLED").contains(status)))
            throw ex(400,"VALIDATION_FAILED","Проверьте параметры списка");
        return read.execute(tx -> {
            String where=" WHERE "+(admin?"true":"b.user_id=?")+(status==null?"":" AND b.status=?");
            List<Object> args=new ArrayList<>(); if(!admin) args.add(owner); if(status!=null) args.add(status);
            long count=jdbc.queryForObject("SELECT count(*) FROM bookings b"+where,Long.class,args.toArray());
            String order=sort.equals("checkInAsc")?"b.check_in_date ASC,b.id ASC":"b.created_at DESC NULLS LAST,b.id DESC";
            List<Object> paged=new ArrayList<>(args); paged.add(size); paged.add((long)page*size);
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT b.*,h.name current_hotel_name,r.name current_room_name FROM bookings b JOIN rooms r ON r.id=b.room_id JOIN hotels h ON h.id=r.hotel_id"+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",paged.toArray());
            return map("items",rows.stream().map(this::bookingDto).toList(),"page",page,"size",size,"totalElements",count,"totalPages",(count+size-1)/size,"sort",sort,"evaluatedAt",clock.instant().toString());
        });
    }
    public Map<String,Object> cancel(String id) {
        long owner=owner(); long bookingId=BookingSelection.id(id);
        return write.execute(tx -> {
            List<Map<String,Object>> initial=jdbc.queryForList("SELECT room_id FROM bookings WHERE id=? AND user_id=?",bookingId,owner);
            if(initial.isEmpty()) throw ex(404,"NOT_FOUND","Бронь не найдена");
            rooms.findByIdForUpdate(((Number)initial.getFirst().get("room_id")).longValue()).orElseThrow(()->ex(404,"NOT_FOUND","Бронь не найдена"));
            Map<String,Object> row=rawBooking(bookingId,owner,false);
            if("CANCELLED".equals(row.get("status"))) return bookingDto(row);
            if(row.get("time_zone")==null || row.get("cancellation_deadline_at")==null || !("CAPTURED_AT_CREATION".equals(row.get("timezone_provenance")) || "MIGRATION_VERIFIED".equals(row.get("timezone_provenance"))))
                throw ex(409,"LEGACY_TIMEZONE_UNKNOWN","Для старой брони требуется проверка часового пояса");
            if(!clock.instant().isBefore(instant(row,"cancellation_deadline_at"))) throw ex(409,"CANCELLATION_CLOSED","Срок отмены истёк");
            jdbc.update("UPDATE bookings SET status='CANCELLED',cancelled_at=? WHERE id=?",ts(clock.instant()),bookingId);
            events.publishBookingCancelled(bookingId,owner,((Number)row.get("room_id")).longValue(),date(row,"check_in_date"),date(row,"check_out_date"));
            return bookingRow(bookingId,owner,false);
        });
    }
    private Map<String,Object> bookingRow(long id,long owner,boolean admin) { return bookingDto(rawBooking(id,owner,admin)); }
    private Map<String,Object> rawBooking(long id,long owner,boolean admin) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT b.*,h.name current_hotel_name,r.name current_room_name FROM bookings b JOIN rooms r ON r.id=b.room_id JOIN hotels h ON h.id=r.hotel_id WHERE b.id=?"+(admin?"":" AND b.user_id=?"),admin?new Object[]{id}:new Object[]{id,owner});
        if(rows.isEmpty()) throw ex(404,"NOT_FOUND","Бронь не найдена"); return rows.getFirst();
    }
    private Map<String,Object> bookingDto(Map<String,Object> row) {
        boolean cancelled="CANCELLED".equals(row.get("status"));
        Instant deadline=instant(row,"cancellation_deadline_at"); String zone=(String)row.get("time_zone");
        boolean verified=Set.of("CAPTURED_AT_CREATION","MIGRATION_VERIFIED").contains(Objects.toString(row.get("timezone_provenance"),""));
        boolean can=!cancelled && verified && deadline!=null && zone!=null && clock.instant().isBefore(deadline);
        String reason=cancelled?"ALREADY_CANCELLED":(!verified || deadline==null || zone==null)?"LEGACY_TIMEZONE_UNKNOWN":can?null:"DEADLINE_PASSED";
        Map<String,Object> out=map("id",row.get("id").toString(),"roomId",row.get("room_id").toString(),"checkInDate",date(row,"check_in_date").toString(),"checkOutDate",date(row,"check_out_date").toString(),
                "status",row.get("status"),"cancelledAt",textInstant(row,"cancelled_at"),"cancellation",map("canCancel",can,"deadlineAt",verified?textInstant(row,"cancellation_deadline_at"):null,"timeZone",verified?zone:null,"reason",reason),"origin",row.get("origin"));
        if("V2".equals(row.get("origin"))) {
            out.put("guests",row.get("guests")); out.put("snapshot",decode((String)row.get("snapshot_json")));out.put("createdAt",textInstant(row,"created_at"));
            out.put("pricing",map("nightlyPrice",((BigDecimal)row.get("nightly_price")).setScale(2).toPlainString(),"nights",row.get("nights"),"totalPrice",((BigDecimal)row.get("total_price")).setScale(2).toPlainString(),"currency","RUB","feesIncluded",true));
        } else {
            out.put("guests",null);out.put("pricing",null);out.put("snapshot",null);out.put("createdAt",null);
            out.put("currentCatalogDisplay",map("hotelName",row.get("current_hotel_name"),"roomName",row.get("current_room_name"),"source","CURRENT_CATALOG"));
        }
        return out;
    }
    private void requireAdmin() {
        var a=SecurityContextHolder.getContext().getAuthentication();
        if(a==null || a.getAuthorities().stream().noneMatch(v->v.getAuthority().equals("ROLE_ADMIN"))) throw ex(403,"FORBIDDEN","Недостаточно прав");
    }
    private long owner() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) throw ex(401,"AUTHENTICATION_REQUIRED","Войдите в аккаунт");
        return users.findByName(auth.getName()).orElseThrow(()->ex(401,"AUTHENTICATION_REQUIRED","Войдите в аккаунт")).getId();
    }
    private Map<String,Object> quoteRow(long owner,UUID key) { return jdbc.queryForMap("SELECT * FROM browser_booking_quotes WHERE user_id=? AND request_key=? FOR UPDATE",owner,key); }
    private Map<String,Object> operation(long owner,UUID key,boolean lock) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM browser_booking_operations WHERE user_id=? AND request_key=?"+(lock?" FOR UPDATE":""),owner,key); return rows.isEmpty()?null:rows.getFirst();
    }
    private Map<String,Object> operationRead(long owner,UUID key) { return read.execute(tx -> operation(owner,key,false)); }
    private V2ApiException storedError(Map<String,Object> row) {
        if(row.get("error_cipher")!=null) {
            Map<String,Object> e=decodeProtected(row.get("error_cipher"));
            return new V2ApiException(((Number)e.get("status")).intValue(),(String)e.get("code"),(String)e.get("message"),castMap(e.get("details")));
        }
        return ex(((Number)row.get("error_status")).intValue(),(String)row.get("error_code"),"Операция завершена без создания брони. Проверьте выбор заново");
    }
    private Map<String,Object> error(V2ApiException e) {
        String trace=null; RequestAttributes attrs=RequestContextHolder.getRequestAttributes();
        if(attrs instanceof ServletRequestAttributes a) trace=(String)a.getRequest().getAttribute("v2.traceId");
        return map("status",e.getStatus(),"code",e.getCode(),"message",e.getMessage(),"fieldErrors",List.of(),"traceId",trace==null?UUID.randomUUID().toString().replace("-",""):trace,"details",e.getDetails());
    }
    private Reply reply(Outcome outcome) { if(outcome.failure()!=null) throw outcome.failure(); return new Reply(outcome.body(),outcome.replayed()); }
    private Outcome fail(int status,String code,String message) { return new Outcome(null,ex(status,code,message),false); }
    private V2ApiException ex(int status,String code,String message) { return new V2ApiException(status,code,message); }
    private String fingerprint(BookingSelection s,String token) { Map<String,Object> body=new TreeMap<>(s.stay()); if(token!=null)body.put("quoteToken",token); return QuoteCipher.hash(canonical(body)); }
    private String canonical(Object value) {
        if(value instanceof Map<?,?> m) { Map<String,Object> sorted=new TreeMap<>(); m.forEach((k,v)->sorted.put(k.toString(),normalize(v)));return encode(sorted); }
        return encode(value);
    }
    private Object normalize(Object value) { if(value instanceof Map<?,?> m){Map<String,Object> sorted=new TreeMap<>();m.forEach((k,v)->sorted.put(k.toString(),normalize(v)));return sorted;}return value; }
    private String encode(Object value) { try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot encode browser record");} }
    private Map<String,Object> decode(String value) { try{return json.readValue(value,new TypeReference<Map<String,Object>>(){});}catch(Exception e){throw new IllegalStateException("Cannot decode browser record");} }
    private Map<String,Object> decodeProtected(Object value) { return decode(cipher.decrypt((String)value)); }
    @SuppressWarnings("unchecked") private Map<String,Object> castMap(Object value){return value instanceof Map?(Map<String,Object>)value:Map.of();}
    private static Timestamp ts(Instant i){return Timestamp.from(i);}
    private static Instant instant(Map<String,Object> m,String key){Object v=m.get(key);return v==null?null:((Timestamp)v).toInstant();}
    private static String textInstant(Map<String,Object> m,String key){Instant i=instant(m,key);return i==null?null:i.toString();}
    private static LocalDate date(Map<String,Object> m,String key){return ((java.sql.Date)m.get(key)).toLocalDate();}
    private static Map<String,Object> map(Object... values){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)m.put((String)values[i],values[i+1]);return m;}
    private void cleanupQuotes(){jdbc.update("DELETE FROM browser_booking_quotes WHERE retain_until<=?",ts(clock.instant()));}
    @Scheduled(fixedDelayString="${app.web.v2.recovery-interval-ms:10000}")
    public void recover() {
        Instant now=clock.instant();
        // Bounded batch; row locking in recoverIfExpired never interrupts live workers.
        for(Map<String,Object> row:jdbc.queryForList("SELECT user_id,request_key FROM browser_booking_operations WHERE state='PROCESSING' AND lease_until<=? ORDER BY lease_until LIMIT 50",ts(now)))
            recoverIfExpired(((Number)row.get("user_id")).longValue(),(UUID)row.get("request_key"));
        write.executeWithoutResult(tx->{cleanupQuotes();jdbc.update("UPDATE browser_booking_operations SET error_cipher=NULL WHERE state='FAILED' AND completed_at<? AND error_cipher IS NOT NULL",ts(now.minusSeconds(7*86400)));});
    }
}
