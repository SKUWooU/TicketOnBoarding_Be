export function createWeightedSeatPlan(totalSeats, hotSeatCount, hotRequestPercent, seed) {
  for (const [name, value] of Object.entries({ totalSeats, hotSeatCount, hotRequestPercent, seed })) {
    if (!Number.isSafeInteger(value)) {
      throw new Error(`${name} must be a safe integer.`);
    }
  }
  if (totalSeats < 2 || hotSeatCount < 1 || hotSeatCount > totalSeats) {
    throw new Error('hotSeatCount must be within the fixture seat count.');
  }
  if (hotRequestPercent < 0 || hotRequestPercent > 100) {
    throw new Error('hotRequestPercent must be between 0 and 100.');
  }
  if (seed < 0 || seed > 0xffffffff) {
    throw new Error('seed must be an unsigned 32-bit integer.');
  }
  if (hotSeatCount === totalSeats && hotRequestPercent !== 100) {
    throw new Error('A full-fixture hot set requires 100% hot requests.');
  }
  return { totalSeats, hotSeatCount, hotRequestPercent, seed };
}

export function selectWeightedSeat(iteration, plan) {
  if (!Number.isSafeInteger(iteration) || iteration < 0) {
    throw new Error('iteration must be a non-negative safe integer.');
  }
  const draw = mix32(plan.seed ^ iteration ^ 0x9e3779b9) % 10000;
  const isHot = draw < plan.hotRequestPercent * 100;
  const seatHash = mix32(plan.seed ^ iteration ^ 0x85ebca6b);
  const seatIndex = isHot
    ? seatHash % plan.hotSeatCount
    : plan.hotSeatCount + seatHash % (plan.totalSeats - plan.hotSeatCount);
  return { seatIndex, isHot };
}

function mix32(value) {
  let mixed = value >>> 0;
  mixed ^= mixed >>> 16;
  mixed = Math.imul(mixed, 0x7feb352d);
  mixed ^= mixed >>> 15;
  mixed = Math.imul(mixed, 0x846ca68b);
  mixed ^= mixed >>> 16;
  return mixed >>> 0;
}
