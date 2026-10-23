package com.kryukow.HotelReservationService.local;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Reject a misdirected local profile before Flyway, Hibernate or any database beans start. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class LocalEnvironmentConfiguration {
    @Bean
    static BeanFactoryPostProcessor localEnvironmentGuard(Environment environment) {
        return beanFactory -> LocalEnvironmentGuard.verify(environment);
    }
}
