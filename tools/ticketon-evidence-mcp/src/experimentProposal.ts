import { z } from "zod";
import type { EvidenceRepository } from "./evidence.js";

type Report = Awaited<ReturnType<EvidenceRepository["assessSamplingContrast"]>>;
const metrics = ["completed", "holdAndRelease", "expectedSeatConflicts", "holdSuccessP95Ms",
  "seatConflictP95Ms", "globalDbRowLockWaits", "globalDbRowLockTimeMs", "collectionSamples",
  "collectionTotalWallClockMs", "collectionMaxWallClockMs"] as const;
const METHODS = {
  HOST_OR_COLLECTOR_INTERFERENCE: { method: "ISOLATE_COLLECTOR", variable: "collectorPlacement" },
  VARIABLE_DB_WAIT: { method: "ATTRIBUTE_WAIT", variable: "waitAttribution" }
} as const;
const proposalSchema = z.object({
  version: z.literal(1), batchId: z.string(), conclusion: z.literal("NO_CAUSAL_CONCLUSION"),
  facts: z.array(z.object({ sequence: z.number().int().min(1), metric: z.enum(metrics),
    value: z.number().finite().nonnegative() }).strict()).min(2).max(16),
  hypotheses: z.array(z.object({ kind: z.enum(["HOST_OR_COLLECTOR_INTERFERENCE", "VARIABLE_DB_WAIT"]),
    certainty: z.literal("HYPOTHESIS"), factIndexes: z.array(z.number().int().nonnegative()).min(2).max(16)
  }).strict()).min(1).max(4),
  experiments: z.array(z.object({ hypothesisIndex: z.number().int().nonnegative(),
    method: z.enum(["ISOLATE_COLLECTOR", "ATTRIBUTE_WAIT"]),
    changedVariable: z.enum(["collectorPlacement", "waitAttribution"]),
    fixedConditions: z.record(z.unknown()), approvalRequired: z.literal(true), execute: z.literal(false)
  }).strict()).min(1).max(4)
}).strict();
const LIMITATIONS = [
  "Offline structured-output contract only; not LLM accuracy, natural-language quality or analyst time saved.",
  "Citations must originate from a validated MCP report; this checker does not authenticate files or rerun measurements.",
  "A supported hypothesis is not a confirmed cause. Proposed experiments still require human design and approval.",
  "Collector relocation or extra attribution can change observation cost and environment; new results need a separate baseline."
];

// Internal offline evaluation only. Not an MCP tool and never launches an experiment.
export function evaluateExperimentProposal(report: Report, candidate: unknown) {
  if (!("observations" in report)) {
    return { status: "INSUFFICIENT_EVIDENCE" as const, reasons: ["Validated measured observations are unavailable."], limitations: LIMITATIONS };
  }
  const parsed = proposalSchema.safeParse(candidate);
  const reject = (reason: string) => ({ status: "CANDIDATE_REJECTED" as const, reasons: [reason], limitations: LIMITATIONS });
  if (!parsed.success) return reject("The structured proposal violates the fact/hypothesis/manual-experiment schema.");
  const data = parsed.data;
  if (data.batchId !== report.batchId) return reject("The cited batch does not match the validated report.");
  for (const fact of data.facts) {
    const source = report.observations.find(item => item.sequence === fact.sequence);
    if (!source || source[fact.metric] !== fact.value) return reject("A fact cites an unavailable stage or mismatched metric value.");
  }
  for (const hypothesis of data.hypotheses) {
    const facts = hypothesis.factIndexes.map(index => data.facts[index]);
    if (facts.some(fact => !fact) || new Set(hypothesis.factIndexes).size !== facts.length) {
      return reject("A hypothesis requires distinct, valid fact references.");
    }
    if (hypothesis.kind === "HOST_OR_COLLECTOR_INTERFERENCE") {
      const costs = facts.filter(fact => fact.metric === "collectionTotalWallClockMs");
      const intervals = costs.map(fact => report.observations.find(item => item.sequence === fact.sequence)!.samplingIntervalMilliseconds);
      if (report.status !== "RESOURCE_REVIEW_REQUIRED" || costs.length < 2 || new Set(intervals).size !== 2) {
        return reject("Collector interference requires a resource-review signal and observed cost in both sampling conditions.");
      }
    } else {
      const waits = facts.filter(fact => fact.metric === "globalDbRowLockWaits");
      if (!waits.some(fact => fact.value > 0) || !waits.some(fact => fact.value === 0)) {
        return reject("Variable DB wait requires both positive and zero observed wait counters, not a bottleneck claim.");
      }
    }
  }
  for (const experiment of data.experiments) {
    const hypothesis = data.hypotheses[experiment.hypothesisIndex];
    if (!hypothesis) return reject("An experiment has no supported hypothesis.");
    const expected = METHODS[hypothesis.kind];
    if (experiment.method !== expected.method || experiment.changedVariable !== expected.variable) {
      return reject("The experiment does not discriminate its stated hypothesis using one declared variable.");
    }
    const controls = Object.entries(report.controlledConditions);
    if (Object.keys(experiment.fixedConditions).length !== controls.length ||
        controls.some(([key, value]) => JSON.stringify(experiment.fixedConditions[key]) !== JSON.stringify(value))) {
      return reject("The workload, warm-up and sampling controls must match the validated baseline exactly.");
    }
  }
  if (data.hypotheses.some((_, index) => !data.experiments.some(item => item.hypothesisIndex === index))) {
    return reject("Every hypothesis needs a corresponding manual experiment proposal.");
  }
  return { status: "CONTRACT_ACCEPTED" as const, citedBatchId: report.batchId,
    checkedFacts: data.facts.length, checkedHypotheses: data.hypotheses.length,
    checkedExperiments: data.experiments.length, executionAuthorized: false as const, limitations: LIMITATIONS };
}
