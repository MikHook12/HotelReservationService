package com.kryukow.HotelReservationService.config;

import com.kryukow.HotelReservationService.security.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;


@Configuration
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    private final CustomUserDetailsService detailsService;

    @Bean
    @org.springframework.core.annotation.Order(1)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="app.web.v2.enabled", havingValue="false", matchIfMissing=true)
    public SecurityFilterChain disabledBrowserChain(HttpSecurity http, com.fasterxml.jackson.databind.ObjectMapper mapper) throws Exception {
        var errors=new com.kryukow.HotelReservationService.web.v2.common.V2Errors(mapper);
        return http.securityMatcher("/api/v2/**")
                .securityContext(context -> context.securityContextRepository(new org.springframework.security.web.context.NullSecurityContextRepository()))
                .csrf(org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer::disable)
                .requestCache(org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer::disable)
                .logout(org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().denyAll())
                .exceptionHandling(ex -> ex.authenticationEntryPoint((req,res,cause)->errors.write(req,res,404,"NOT_FOUND","Объект не найден."))
                        .accessDeniedHandler((req,res,cause)->errors.write(req,res,404,"NOT_FOUND","Объект не найден.")))
                .build();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="app.web.v2.enabled", havingValue="true")
    public com.kryukow.HotelReservationService.web.v2.auth.BrowserCsrfRepository browserCsrfRepository(java.time.Clock clock) {
        return new com.kryukow.HotelReservationService.web.v2.auth.BrowserCsrfRepository(clock);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="app.web.v2.enabled", havingValue="true")
    public org.springframework.session.web.http.CookieSerializer browserCookieSerializer() {
        var cookie=new org.springframework.session.web.http.DefaultCookieSerializer();
        cookie.setCookieName("__Host-hotel-session"); cookie.setCookiePath("/");
        cookie.setUseSecureCookie(true); cookie.setUseHttpOnlyCookie(true); cookie.setSameSite("Lax");
        return cookie;
    }

    @Bean
    @org.springframework.core.annotation.Order(1)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="app.web.v2.enabled", havingValue="true")
    public SecurityFilterChain browserChain(HttpSecurity http,
            com.kryukow.HotelReservationService.web.v2.common.V2Errors errors,
            com.kryukow.HotelReservationService.web.v2.auth.BrowserCsrfRepository csrf,
            com.kryukow.HotelReservationService.web.v2.auth.BrowserIdentityStore identities,
            java.time.Clock clock) throws Exception {
        var contexts=new org.springframework.security.web.context.HttpSessionSecurityContextRepository();
        return http.securityMatcher("/api/v2/**")
                .securityContext(context->context.securityContextRepository(contexts).requireExplicitSave(true))
                .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .requestCache(org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer::disable)
                .logout(org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer::disable)
                .csrf(config->config.csrfTokenRepository(csrf))
                .authorizeHttpRequests(auth->auth
                        .requestMatchers(HttpMethod.GET,"/api/v2/auth/csrf","/api/v2/cities","/api/v2/hotels/**","/api/v2/rooms/*","/api/v2/media/*").permitAll()
                        .requestMatchers(HttpMethod.POST,"/api/v2/auth/login","/api/v2/auth/register","/api/v2/auth/logout").permitAll()
                        .requestMatchers("/api/v2/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(ex->ex
                        .authenticationEntryPoint((req,res,cause)->errors.write(req,res,401,"AUTHENTICATION_REQUIRED","Войдите, чтобы продолжить."))
                        .accessDeniedHandler((req,res,cause)->errors.write(req,res,403,
                                cause instanceof org.springframework.security.web.csrf.CsrfException ? "CSRF_INVALID" : "FORBIDDEN",
                                "Доступ запрещён.")))
                .addFilterBefore(new com.kryukow.HotelReservationService.web.v2.common.V2HttpFilter(errors),
                        org.springframework.security.web.context.SecurityContextHolderFilter.class)
                .addFilterAfter(new com.kryukow.HotelReservationService.web.v2.auth.BrowserSessionGuard(identities,clock,errors),
                        org.springframework.security.web.context.SecurityContextHolderFilter.class)
                .addFilterBefore(new com.kryukow.HotelReservationService.web.v2.auth.BrowserOriginFilter(errors),
                        org.springframework.security.web.csrf.CsrfFilter.class)
                .build();
    }

    @Bean
    @org.springframework.core.annotation.Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            @org.springframework.beans.factory.annotation.Value("${app.web.v2.enabled:false}") boolean browserEnabled,
            org.springframework.beans.factory.ObjectProvider<com.kryukow.HotelReservationService.web.v2.common.V2Errors> errors) throws Exception {
        if (browserEnabled) http.addFilterBefore(
                new com.kryukow.HotelReservationService.web.v2.auth.LegacyBrowserCutoverFilter(errors.getObject()),
                org.springframework.security.web.csrf.CsrfFilter.class);
        return http
                .securityMatcher("/api/v1/**", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                .securityContext(context -> context.securityContextRepository(new org.springframework.security.web.context.NullSecurityContextRepository()))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(new org.springframework.security.web.csrf.CookieCsrfTokenRepository())
                        .requireCsrfProtectionMatcher(new com.kryukow.HotelReservationService.security.BrowserCsrfMatcher()))
                .httpBasic(httpSecurityHttpBasicConfigurer -> httpSecurityHttpBasicConfigurer
                        .realmName("Hotel Reservation API"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers("/api/v1/admin/statistics/export").hasRole("ADMIN")
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").hasRole("ADMIN")

                        .requestMatchers(HttpMethod.GET, "/api/v1/hotels/**").authenticated()

                        .requestMatchers(HttpMethod.POST, "/api/v1/hotels/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/hotels/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/hotels/**").hasRole("ADMIN")

                        .requestMatchers(HttpMethod.GET, "/api/v1/rooms/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/rooms/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/rooms/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/rooms/**").hasRole("ADMIN")

                        .requestMatchers(HttpMethod.GET, "/api/v1/bookings").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/bookings/{id}").authenticated()

                        .requestMatchers(HttpMethod.POST, "/api/v1/bookings").authenticated()

                        .requestMatchers(HttpMethod.GET, "/api/v1/users", "/api/v1/users/by-name").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/{id}").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/users").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/users/{id}").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/users/{id}").hasRole("ADMIN")

                        .requestMatchers(HttpMethod.PATCH, "/api/v1/users/*/role").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/hotels/*/rating").authenticated()
                        .anyRequest().denyAll()

                )
                .userDetailsService(detailsService)
                .build();


    }


    @Bean
    @org.springframework.core.annotation.Order(99)
    public SecurityFilterChain unmatchedChain(HttpSecurity http) throws Exception {
        return http.securityContext(context->context.securityContextRepository(new org.springframework.security.web.context.NullSecurityContextRepository()))
                .csrf(org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer::disable)
                .logout(org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth->auth.anyRequest().denyAll()).build();
    }


    @Bean
    public PasswordEncoder passwordEncoder() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        log.info("PasswordEncoder initialized: {}", encoder.getClass().getSimpleName());
        return encoder;
    }


}
