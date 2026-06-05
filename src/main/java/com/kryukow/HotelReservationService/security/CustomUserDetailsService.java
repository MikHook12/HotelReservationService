package com.kryukow.HotelReservationService.security;

import com.kryukow.HotelReservationService.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        log.info("=== loadUserByUsername START: '{}' ===", username);

        return userRepository.findByName(username)
                .map(user -> {
                    log.info("User found: id={}, name={}, role={}, password={}",
                            user.getId(), user.getName(), user.getRole(), user.getPassword());
                    return new CustomUserDetails(user);
                })
                .orElseThrow(() -> {
                    log.error("User NOT found: {}", username);
                    return new UsernameNotFoundException("User not found with name: " + username);
                });
    }
}