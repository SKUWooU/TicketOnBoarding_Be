package com.onticket.loadtest;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("loadtest")
public class LoadTestStartupConfiguration {

    @Bean
    @ConditionalOnProperty(
            name = "onticket.loadtest.fixture.enabled",
            havingValue = "true"
    )
    ApplicationRunner loadTestFixtureInitializer(LoadTestFixtureService fixtureService) {
        return arguments -> fixtureService.initialize();
    }
}
