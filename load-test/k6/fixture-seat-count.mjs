export function expectedFixtureSeatCount(raw) {
  if (raw === undefined || raw === '') return 2000;
  if (raw !== '20' && raw !== '2000') {
    throw new Error('EXPECTED_TOTAL_SEATS must be 20 or 2000.');
  }
  return Number(raw);
}
