package com.onticket.loadtest;

import com.onticket.user.jwt.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    @PostMapping("/runs")
    public LoadTestFixtureService.FixtureMetadata initialize(@RequestParam String runId) {
        return fixtureService.initialize(runId);
    }

    @GetMapping("/fixture")
    public LoadTestFixtureService.FixtureMetadata fixture(@RequestParam String runId) {
        return fixtureService.metadata(runId);
    }

    @GetMapping("/snapshot")
    public LoadTestFixtureService.InventorySnapshot snapshot(@RequestParam String runId) {
        return fixtureService.snapshot(runId);
    }

    @PostMapping("/seat-holds/reset")
    public LoadTestFixtureService.SeatHoldSnapshot resetSeatHolds(@RequestParam String runId) {
        return fixtureService.resetSeatHolds(runId);
    }

    @GetMapping("/seat-holds/snapshot")
    public LoadTestFixtureService.SeatHoldSnapshot seatHoldSnapshot(@RequestParam String runId) {
        return fixtureService.seatHoldSnapshot(runId);
    }

    @GetMapping("/tokens")
    public List<LoadTestToken> tokens(
            @RequestParam String runId,
            @RequestParam(defaultValue = "100") int count
    ) {
        if (count <= 0 || count > MAX_TOKEN_COUNT) {
            throw new IllegalArgumentException("loadtest token count는 1~500 범위여야 합니다.");
        }
        return IntStream.rangeClosed(1, count)
                .mapToObj(index -> {
                    String username = (LoadTestFixtureService.usernamePrefix(runId) + "%03d").formatted(index);
                    return new LoadTestToken(username, jwtUtil.generateAccessToken(username));
                })
                .toList();
    }

    public record LoadTestToken(String username, String accessToken) {
    }
}
