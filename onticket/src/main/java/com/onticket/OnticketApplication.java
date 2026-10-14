package com.onticket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Admission connections will be wired explicitly when its API is opt-in enabled.
// Adding the core must not make existing health checks depend on localhost Redis.
@SpringBootApplication(exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
@EnableScheduling
public class OnticketApplication {

    public static void main(String[] args) {
        SpringApplication.run(OnticketApplication.class, args);
    }

}
