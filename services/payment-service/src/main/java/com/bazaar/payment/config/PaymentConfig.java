package com.bazaar.payment.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
