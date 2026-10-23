package com.kryukow.HotelReservationService.web.v2.auth;

import com.kryukow.HotelReservationService.security.BcryptPassword;
import com.kryukow.HotelReservationService.web.v2.common.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/v2/auth")
@ConditionalOnProperty(name="app.web.v2.enabled", havingValue="true")
public class BrowserAuthController {
    public record LoginRequest(@NotBlank @Size(min=3,max=50) String username,
                               @NotBlank @Size(max=72) @BcryptPassword String password) {}
    public record RegisterRequest(@NotBlank @Size(min=3,max=50) String username,
                                  @NotBlank @Size(min=6,max=72) @BcryptPassword String password,
                                  @NotBlank @Email @Size(max=254) String email) {}
    private final BrowserIdentityStore identities;
    private final BrowserRegistrationService registrations;
    private final BrowserRateLimiter limits;
    private final PasswordEncoder passwords;
    private final BrowserCsrfRepository csrf;
    private final Clock clock;
    private final boolean testOnly;
    private final String dummyHash;
    public BrowserAuthController(BrowserIdentityStore identities, BrowserRegistrationService registrations,
            BrowserRateLimiter limits, PasswordEncoder passwords, BrowserCsrfRepository csrf, Clock clock,
            Environment environment, @Value("${app.web.v2.registration-mode:CLOSED}") String registrationMode,
            @Value("${app.web.v2.test-registration-allowed:false}") boolean testAllowed) {
        this.identities=identities; this.registrations=registrations; this.limits=limits; this.passwords=passwords;
        this.csrf=csrf; this.clock=clock;
        this.testOnly="TEST_ONLY".equals(registrationMode) && testAllowed
                && Arrays.asList(environment.getActiveProfiles()).contains("test");
        this.dummyHash=passwords.encode("dummy-account-timing-only");
    }
    private boolean testGate(HttpServletRequest req) {
        return testOnly && Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(req.getRemoteAddr());
    }
    @GetMapping("/csrf")
    public Map<String,Object> csrf(CsrfToken token, HttpServletRequest req) {
        String value=token.getToken(); // Force durable token creation before reading expiry.
        return Map.of("token",value,"headerName",token.getHeaderName(),"expiresAt",csrf.expiresAt(req.getSession()),
                "registrationMode",testGate(req)?"TEST_ONLY":"CLOSED");
    }
    @PostMapping("/login")
    public Map<String,Object> login(@Valid @RequestBody LoginRequest body, HttpServletRequest req, HttpServletResponse res) {
        limits.checkLogin(req.getRemoteAddr(),body.username());
        var found=identities.byUsername(body.username());
        boolean valid=passwords.matches(body.password(),found.map(BrowserIdentityStore.Identity::passwordHash).orElse(dummyHash));
        if (!valid || found.isEmpty()) {
            limits.loginFailed(req.getRemoteAddr(),body.username());
            throw new V2ApiException(401,"INVALID_CREDENTIALS","Неверные данные для входа.");
        }
        var user=found.get();
        HttpSession old=req.getSession(false); if(old!=null) old.invalidate();
        HttpSession session=req.getSession(true); session.setMaxInactiveInterval(1800);
        long now=clock.instant().getEpochSecond(); session.setAttribute(BrowserSessionState.LOGIN,now);
        session.setAttribute(BrowserSessionState.LAST,now);
        var principal=new V2Principal(user.id(),user.username(),user.role(),user.version());
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context,req,res);
        return session(user,session);
    }
    @GetMapping("/me")
    public Map<String,Object> me(HttpServletRequest req) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if (auth==null || !(auth.getPrincipal() instanceof V2Principal principal)) throw unauthenticated();
        var identity=identities.byId(principal.userId()).orElseThrow(BrowserAuthController::unauthenticated);
        return session(identity,req.getSession(false));
    }
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest req, HttpServletResponse res) {
        var session=req.getSession(false); if(session!=null) session.invalidate();
        SecurityContextHolder.clearContext();
        res.addHeader("Set-Cookie",ResponseCookie.from("__Host-hotel-session","").secure(true).httpOnly(true)
                .sameSite("Lax").path("/").maxAge(0).build().toString());
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest body, HttpServletRequest req) {
        if (!testGate(req)) throw new V2ApiException(403,"REGISTRATION_CLOSED","Публичная регистрация пока закрыта.");
        limits.registrationAttempt(req.getRemoteAddr());
        try {
            Long id=registrations.register(body.username(),body.email(),body.password());
            return ResponseEntity.status(201).body(profile(identities.byId(id).orElseThrow(BrowserAuthController::unauthenticated)));
        } catch (com.kryukow.HotelReservationService.exception.ResourceConflictException | org.springframework.dao.DataIntegrityViolationException ex) {
            throw new V2ApiException(409,"ACCOUNT_CONFLICT","Не удалось создать аккаунт с этими данными.");
        }
    }
    private Map<String,Object> session(BrowserIdentityStore.Identity user,HttpSession session) {
        Instant now=clock.instant(); Instant absolute=BrowserSessionState.absolute(session,now);
        Instant idle=now.plusSeconds(1800); if(idle.isAfter(absolute)) idle=absolute;
        return Map.of("user",profile(user),"idleExpiresAt",idle,"absoluteExpiresAt",absolute);
    }
    private Map<String,Object> profile(BrowserIdentityStore.Identity user) {
        return Map.of("id",user.id().toString(),"username",user.username(),"email",user.email(),
                "emailVerified",false,"roles",List.of(user.role()),"isRestrictedDemoAccount",registrations.isRestricted(user.id()));
    }
    private static V2ApiException unauthenticated() { return new V2ApiException(401,"AUTHENTICATION_REQUIRED","Войдите, чтобы продолжить."); }
}
