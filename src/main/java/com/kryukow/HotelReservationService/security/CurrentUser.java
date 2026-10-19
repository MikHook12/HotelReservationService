package com.kryukow.HotelReservationService.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {
    private Authentication authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof CustomUserDetails)) {
            throw new AuthenticationCredentialsNotFoundException("Authentication required");
        }
        return authentication;
    }

    public Long id() {
        return ((CustomUserDetails) authentication().getPrincipal()).getId();
    }

    public boolean isAdmin() {
        return authentication().getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    }

    public void requireOwnerOrAdmin(Long ownerId) {
        if (!isAdmin() && !id().equals(ownerId)) {
            throw new AccessDeniedException("Access denied");
        }
    }
}
