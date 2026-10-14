package com.onticket.admission;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import java.time.Duration;

final class AdmissionRedisConnections {
    private AdmissionRedisConnections() { }
    static LettuceConnectionFactory loopback(int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid admission Redis port");
        var options = ClientOptions.builder().socketOptions(SocketOptions.builder()
                .connectTimeout(Duration.ofSeconds(1)).build()).build();
        return new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port),
                LettuceClientConfiguration.builder().clientOptions(options).commandTimeout(Duration.ofSeconds(1))
                        .shutdownTimeout(Duration.ofMillis(100)).build());
    }
}
