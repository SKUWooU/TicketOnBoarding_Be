package com.onticket.loadtest;

import com.onticket.user.jwt.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.IntStream;

@RestController
@RequestMapping("/loadtest")
@RequiredArgsConstructor
@Profile("loadtest")
public class LoadTestController {

    private static final int MAX_TOKEN_COUNT = 500;

    private final LoadTestFixtureService fixtureService;
    private final JwtUtil jwtUtil;

    @GetMapping("/fixture")
    public LoadTestFixtureService.FixtureMetadata fixture() {
        return fixtureService.metadata();
    }

    @GetMapping("/snapshot")
    public LoadTestFixtureService.InventorySnapshot snapshot() {
        return fixtureService.snapshot();
    }

    @GetMapping("/tokens")
    public List<LoadTestToken> tokens(@RequestParam(defaultValue = "100") int count) {
        if (count <= 0 || count > MAX_TOKEN_COUNT) {
            throw new IllegalArgumentException("loadtest token count는 1~500 범위여야 합니다.");
        }
        return IntStream.rangeClosed(1, count)
                .mapToObj(index -> {
                    String username = (LoadTestFixtureService.USERNAME_PREFIX + "%03d").formatted(index);
                    return new LoadTestToken(username, jwtUtil.generateAccessToken(username));
                })
                .toList();
    }

    public record LoadTestToken(String username, String accessToken) {
    }
}
