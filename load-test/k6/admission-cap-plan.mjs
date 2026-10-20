import { validPollAssignment } from './admission-poll-plan.mjs';
export const CAP_EXPERIMENT = 'active-cap-v1';

// Each limit occurs once in every position; traffic, batch, worker and polling remain fixed.
export function capComparisonPlan() {
  return [[2, 4, 8], [4, 8, 2], [8, 2, 4]].flatMap((limits, round) =>
    limits.map((active, position) => ({
      sequence: round * 3 + position + 1, round: round + 1, active, rate: 5, mode: 'on',
    })));
}

export function validCapAssignment(m) {
  if (m.experiment !== CAP_EXPERIMENT || !Number.isInteger(m.sequence) || m.sequence < 1 || m.sequence > 9) return false;
  const stage = capComparisonPlan()[m.sequence - 1];
  return m.round === stage.round && m.expectedActive === stage.active && m.mode === 'on' && m.sessionsPerSecond === 5;
}

// Matches Java 21 AdmissionPolicy.toString(), whose SHA-256 is stored by the trusted bootstrap CLI.
export function admissionPolicyDescription(m) {
  if (!(validCapAssignment(m) || validPollAssignment(m)) || !Number.isSafeInteger(m.concertTimeId) || m.concertTimeId < 1
      || !/^[A-Za-z0-9-]{1,32}$/.test(m.generation || '') || m.opensAt !== '2020-01-01T00:00:00Z') {
    throw new Error('Invalid comparison policy identity');
  }
  return `AdmissionPolicy[concertTimeId=${m.concertTimeId}, generation=${m.generation}, opensAt=${m.opensAt}, maxActive=${m.expectedActive}, maxWaiting=30, waitingTtl=PT2M, admissionTtl=PT2M, batchSize=2]`;
}

export { admissionPolicyDescription as capPolicyDescription };
