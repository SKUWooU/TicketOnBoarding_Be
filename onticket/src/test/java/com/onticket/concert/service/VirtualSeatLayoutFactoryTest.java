package com.onticket.concert.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VirtualSeatLayoutFactoryTest {

    private final VirtualSeatLayoutFactory factory = new VirtualSeatLayoutFactory();

    @Test
    void createsStandardTwentyFourSeatLayout() {
        VirtualSeatLayoutFactory.LayoutDefinition layout = factory.standardLayout();

        assertThat(layout.version()).isEqualTo("virtual-24-v1");
        assertThat(layout.positions()).hasSize(24);
        assertThat(layout.positions()).extracting(VirtualSeatLayoutFactory.SeatPosition::sectionCode)
                .containsOnly("GENERAL");
        assertThat(layout.positions().getFirst().seatNumber()).isEqualTo("A1");
        assertThat(layout.positions().getLast().seatNumber()).isEqualTo("C8");
    }

    @Test
    void dividesTwoThousandSeatsIntoTenSectionsOfTwoHundred() {
        VirtualSeatLayoutFactory.LayoutDefinition layout = factory.loadTestLayout(50, 40);

        assertThat(layout.positions()).hasSize(2_000);
        assertThat(layout.positions().stream()
                .map(VirtualSeatLayoutFactory.SeatPosition::sectionCode)
                .distinct())
                .hasSize(10);
        assertThat(layout.positions()).filteredOn(position -> position.sectionCode().equals("S01"))
                .hasSize(200);
        assertThat(layout.positions().getFirst().seatNumber()).isEqualTo("R001-S001");
        assertThat(layout.positions().getLast().seatNumber()).isEqualTo("R050-S040");
    }

    @Test
    void rejectsAnUnboundedFixture() {
        assertThatThrownBy(() -> factory.loadTestLayout(101, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
