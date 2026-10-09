import path from "node:path";
import { fileURLToPath } from "node:url";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { createInvariantReport, EvidenceError, EvidenceRepository } from "./evidence.js";
import { preflightScenario, scenarios } from "./preflight.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const defaultResultsRoot = path.resolve(here, "../../../load-test/results");
const repository = new EvidenceRepository({ resultsRoot: defaultResultsRoot });
const server = new McpServer(
  { name: "ticketon-evidence-mcp", version: "0.1.0" },
  {
    instructions: "로컬 가상 좌석 고경합 fixture의 허용된 summary·manifest만 조회합니다. 목록→요약→불변식 또는 통제 배치 판정 순서로 확인하고, PASS를 운영 성능이나 실제 공연장 보증으로 해석하지 마세요."
  }
);

server.tool("list_evidence_runs", "로컬 고경합 측정 실행과 허용된 요약 파일 목록을 조회합니다.", {
  limit: z.number().int().min(1).max(50).optional()
}, { readOnlyHint: true, openWorldHint: false }, async ({ limit }) => success(await repository.listRuns(limit)));

server.tool("get_run_summary", "JWT·쿠키·요청 본문을 제외한 단일 k6 측정 요약을 조회합니다.", {
  runId: z.string(),
  artifact: z.string().optional()
}, { readOnlyHint: true, openWorldHint: false }, async ({ runId, artifact }) => withEvidenceError(async () => {
  const result = await repository.readSummary(runId, artifact);
  return success(result);
}));

server.tool("verify_domain_invariants", "측정 종료 후 좌석 수와 예약 상태 불변식을 검증합니다.", {
  runId: z.string(),
  artifact: z.string().optional()
}, { readOnlyHint: true, openWorldHint: false }, async ({ runId, artifact }) => withEvidenceError(async () => {
  const result = await repository.readSummary(runId, artifact);
  return success({ artifact: result.artifact, report: createInvariantReport(result.summary) });
}));

server.tool("compare_controlled_hotspot_runs", "같은 가상 좌석 배치의 두 summary가 비교 가능한지 검증하고, 통과할 때만 성공·좌석 충돌 관측 차이를 반환합니다. 부하 실행이나 성능 개선 판정은 하지 않습니다.", {
  batchId: z.string(),
  firstArtifact: z.string(),
  secondArtifact: z.string()
}, { readOnlyHint: true, openWorldHint: false }, async ({ batchId, firstArtifact, secondArtifact }) => withEvidenceError(async () => {
  return success(await repository.compareControlledHotspot(batchId, firstArtifact, secondArtifact));
}));

server.tool("assess_seat_hold_churn_batch", "인기 좌석 Hold→Release 6-run 배치의 manifest·상태 불변식·측정 조건을 검증합니다. 같은 RPS 배치의 관측값만 제공하며 개선 효과는 판정하지 않습니다.", {
  batchId: z.string()
}, { readOnlyHint: true, openWorldHint: false }, async ({ batchId }) => withEvidenceError(async () => {
  return success(await repository.assessSeatHoldChurn(batchId));
}));

server.tool("list_controlled_experiments", "실행 권한 없이 고정된 로컬 Hold→Release 실험 조건만 조회합니다.", {},
  { readOnlyHint: true, openWorldHint: false }, async () => success(scenarios));

server.tool("preflight_controlled_experiment", "고정 실험의 전용 로컬 DB·Backend 연결·빈 테이블·결과 경로를 읽기 전용으로 점검합니다. 부하·fixture는 실행하지 않습니다.", {
  scenarioId: z.enum(["hold-churn-50", "hold-churn-100"]),
  batchId: z.string().regex(/^[A-Za-z0-9-]{1,16}$/),
  composeProject: z.string().regex(/^ticketon-controlled172(-[a-z0-9]{1,12})?$/)
}, { readOnlyHint: true, openWorldHint: false }, async ({ scenarioId, batchId, composeProject }) =>
  success(await preflightScenario(scenarioId, batchId, composeProject)));

server.tool("assess_controlled_hold_evidence", "Read-only assessment of a fixed 20-seat repeat or 2,000-seat churn batch. Reports are scenario-specific; never compare their p95 as an improvement.", {
  scenarioId: z.enum(["small-seat-repeat", "large-seat-churn"]),
  batchId: z.string().regex(/^[A-Za-z0-9-]{1,64}$/)
}, { readOnlyHint: true, openWorldHint: false }, async ({ scenarioId, batchId }) => withEvidenceError(async () => {
  return success(await repository.assessControlledHold(scenarioId, batchId));
}));

server.tool("assess_small_seat_probe", "가상 20석 20→30 RPS 단일 탐색의 안전 게이트를 확인하고 다음 재측정 필요성을 판정합니다. 안정 처리량·p95 개선을 주장하거나 부하를 실행하지 않습니다.", {
  batchId: z.string().regex(/^[A-Za-z0-9-]{1,64}$/)
}, { readOnlyHint: true, openWorldHint: false }, async ({ batchId }) => withEvidenceError(async () =>
  success(await repository.assessSmallSeatProbe(batchId))));

server.tool("assess_small_seat_repeat_30", "가상 20석 20/30 RPS 6단계 반복의 재고·자원 게이트를 검증합니다. 호스트 CPU 관측이 높으면 증량 전 원인 확인을 요구하며 부하는 실행하지 않습니다.", {
  batchId: z.string().regex(/^[A-Za-z0-9-]{1,64}$/)
}, { readOnlyHint: true, openWorldHint: false }, async ({ batchId }) => withEvidenceError(async () =>
  success(await repository.assessSmallSeatRepeat30(batchId))));

await server.connect(new StdioServerTransport());

function success(payload: unknown) {
  return { content: [{ type: "text" as const, text: JSON.stringify(payload, null, 2) }] };
}

async function withEvidenceError(action: () => Promise<ReturnType<typeof success>>) {
  try {
    return await action();
  } catch (error) {
    if (error instanceof EvidenceError) {
      return { content: [{ type: "text" as const, text: JSON.stringify({ error: error.code, message: error.message }) }], isError: true };
    }
    throw error;
  }
}
