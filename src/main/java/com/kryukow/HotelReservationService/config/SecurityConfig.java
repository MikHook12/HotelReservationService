package com.kryukow.HotelReservationService.config;

import com.kryukow.HotelReservationService.security.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
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
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(httpSecurityHttpBasicConfigurer -> httpSecurityHttpBasicConfigurer
                        .realmName("Hotel Reservation API"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/register").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").hasRole("ADMIN")

                        .requestMatchers(HttpMethod.GET, "/api/v1/hotels/**").authenticated()

                        .requestMatchers(HttpMethod.POST, "/api/v1/hotels/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/hotels/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/hotels/**").hasRole("ADMIN")

                        .requestMatchers(HttpMethod.GET, "/api/v1/rooms/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/rooms/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/rooms/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/rooms/**").hasRole("ADMIN")

                        .requestMatchers(HttpMethod.GET, "/api/v1/bookings").hasRole("ADMIN")

                        .requestMatchers(HttpMethod.POST, "/api/v1/bookings").authenticated()

                        .requestMatchers(HttpMethod.GET, "/api/v1/users/**").authenticated()

                        .requestMatchers(HttpMethod.PATCH, "/api/v1/users/*/role").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/hotels/*/rating").authenticated()

                )
                .userDetailsService(detailsService)
                .build();


    }


    @Bean
    public PasswordEncoder passwordEncoder() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        log.info("PasswordEncoder initialized: {}", encoder.getClass().getSimpleName());
        return encoder;
    }


}
