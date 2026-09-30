export function isExpectedSeatContention(response, contentionScenario) {
  return contentionScenario
    && response.status === 409
    && response.headers['X-Reservation-Conflict'] === 'SEAT_UNAVAILABLE';
}
