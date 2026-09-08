package com.onticket.concert.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class VirtualSeatLayoutFactory {

    public static final String STANDARD_VERSION = "virtual-24-v1";
    public static final String LOAD_TEST_VERSION = "loadtest-sectioned-v1";
    private static final int LOAD_TEST_ROWS_PER_SECTION = 5;

    public LayoutDefinition standardLayout() {
        List<SeatPosition> positions = new ArrayList<>(24);
        for (int row = 1; row <= 3; row++) {
            String rowLabel = String.valueOf((char) ('A' + row - 1));
            for (int seatIndex = 1; seatIndex <= 8; seatIndex++) {
                positions.add(new SeatPosition(
                        rowLabel + seatIndex,
                        "GENERAL",
                        "일반석",
                        1,
                        rowLabel,
                        row,
                        seatIndex
                ));
            }
        }
        return new LayoutDefinition(STANDARD_VERSION, positions);
    }

    public LayoutDefinition loadTestLayout(int rows, int seatsPerRow) {
        int totalSeats = Math.multiplyExact(rows, seatsPerRow);
        if (rows <= 0 || seatsPerRow <= 0 || totalSeats > 10_000) {
            throw new IllegalArgumentException("loadtest fixture 좌석 수는 1~10,000 범위여야 합니다.");
        }

        List<SeatPosition> positions = new ArrayList<>(totalSeats);
        for (int row = 1; row <= rows; row++) {
            int sectionOrder = ((row - 1) / LOAD_TEST_ROWS_PER_SECTION) + 1;
            String sectionCode = "S%02d".formatted(sectionOrder);
            String rowLabel = "R%03d".formatted(row);
            for (int seatIndex = 1; seatIndex <= seatsPerRow; seatIndex++) {
                positions.add(new SeatPosition(
                        loadTestSeatNumber(row, seatIndex),
                        sectionCode,
                        sectionOrder + "구역",
                        sectionOrder,
                        rowLabel,
                        row,
                        seatIndex
                ));
            }
        }
        return new LayoutDefinition(LOAD_TEST_VERSION, positions);
    }

    public static String loadTestSeatNumber(int row, int seatIndex) {
        return "R%03d-S%03d".formatted(row, seatIndex);
    }

    public record LayoutDefinition(String version, List<SeatPosition> positions) {
        public LayoutDefinition {
            positions = List.copyOf(positions);
        }
    }

    public record SeatPosition(
            String seatNumber,
            String sectionCode,
            String sectionName,
            int sectionOrder,
            String rowLabel,
            int rowOrder,
            int seatIndex
    ) {
    }
}
