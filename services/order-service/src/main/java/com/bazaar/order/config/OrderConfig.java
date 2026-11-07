package com.bazaar.order.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrderConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
