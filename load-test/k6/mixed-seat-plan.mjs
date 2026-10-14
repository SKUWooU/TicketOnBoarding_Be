export function createMixedSeatPlan(totalSeats, seatsPerRow, largeSeats, overlap) {
  for (const value of [totalSeats, seatsPerRow, largeSeats]) {
    if (!Number.isSafeInteger(value) || value < 1) throw new Error('Seat dimensions must be positive integers.');
  }
  if (typeof overlap !== 'boolean' || totalSeats < 40 + largeSeats || largeSeats > 200) {
    throw new Error('Plan requires 40 small seats, 1..200 large seats and explicit overlap.');
  }
  const number = (index) => `R${String(Math.floor(index / seatsPerRow) + 1).padStart(3, '0')}-S${String(index % seatsPerRow + 1).padStart(3, '0')}`;
  return {
    small: Array.from({ length: 40 }, (_, i) => number(i)),
    large: Array.from({ length: largeSeats }, (_, i) => number((overlap ? 0 : 40) + i)),
  };
}
