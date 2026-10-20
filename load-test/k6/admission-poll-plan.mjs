export const POLL_EXPERIMENT = 'client-poll-v1';

// Backend recommendation remains 5s. Only this opt-in synthetic client's interval changes.
export function pollComparisonPlan() {
  return [[5000, 2000, 1000], [2000, 1000, 5000], [1000, 5000, 2000]].flatMap((intervals, round) =>
    intervals.map((pollIntervalMs, position) => ({
      sequence: round * 3 + position + 1, round: round + 1, active: 4, rate: 5, mode: 'on', pollIntervalMs,
    })));
}

export function validPollAssignment(m) {
  if (m.experiment !== POLL_EXPERIMENT || !Number.isInteger(m.sequence) || m.sequence < 1 || m.sequence > 9) return false;
  const stage = pollComparisonPlan()[m.sequence - 1];
  return m.round === stage.round && m.expectedActive === 4 && m.mode === 'on' && m.sessionsPerSecond === 5
    && m.pollIntervalMs === stage.pollIntervalMs && m.policyPollIntervalMs === 5000;
}
