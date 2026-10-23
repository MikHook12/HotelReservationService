package com.kryukow.HotelReservationService.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BrowserClockConfig {
    @Bean
    public Clock browserClock() { return Clock.systemUTC(); }
}
