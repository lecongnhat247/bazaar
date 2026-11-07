package com.bazaar.inventory.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class InventoryConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
