package com.kryukow.HotelReservationService.web.v2.common;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/** Detached session identity: never serializes a JPA entity or a credential hash. */
public record V2Principal(Long userId, String username, String role, long securityVersion)
        implements UserDetails, Serializable {
    @Serial private static final long serialVersionUID = 1L;
    public Long getUserId() { return userId; }
    @Override public String getUsername() { return username; }
    @Override public String getPassword() { return null; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
