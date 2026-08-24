package com.onticket.concert.service;

import com.onticket.concert.dto.ReservRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

final class ReservationRequestCanonicalizer {

    private ReservationRequestCanonicalizer() {
    }

    static List<String> canonicalSeatNumbers(ReservRequest reservRequest) {
        if (reservRequest == null) {
            throw new IllegalArgumentException("예약 요청이 필요합니다.");
        }

        List<String> seatNumbers = reservRequest.getSeatNumberList();
        if (seatNumbers == null || seatNumbers.isEmpty()) {
            throw new IllegalArgumentException("좌석을 한 개 이상 선택해야 합니다.");
        }
        if (seatNumbers.stream().anyMatch(seatNumber -> seatNumber == null || seatNumber.isBlank())) {
            throw new IllegalArgumentException("좌석 번호는 비어 있을 수 없습니다.");
        }
        if (new HashSet<>(seatNumbers).size() != seatNumbers.size()) {
            throw new IllegalArgumentException("중복된 좌석을 선택할 수 없습니다.");
        }

        return seatNumbers.stream()
                .sorted()
                .toList();
    }

    static String fingerprint(String concertId, ReservRequest reservRequest) {
        if (concertId == null || concertId.isBlank()) {
            throw new IllegalArgumentException("공연 ID가 필요합니다.");
        }
        if (reservRequest == null || reservRequest.getConcertTimeId() == null) {
            throw new IllegalArgumentException("공연 회차가 필요합니다.");
        }

        List<String> seatNumbers = canonicalSeatNumbers(reservRequest);
        StringBuilder canonicalRequest = new StringBuilder()
                .append(concertId.length()).append(':').append(concertId)
                .append('|').append(reservRequest.getConcertTimeId());
        seatNumbers.forEach(seatNumber -> canonicalRequest
                .append('|').append(seatNumber.length()).append(':').append(seatNumber));

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRequest.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
