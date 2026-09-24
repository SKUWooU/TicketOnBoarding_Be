import path from "node:path";
import { fileURLToPath } from "node:url";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { createInvariantReport, EvidenceError, EvidenceRepository } from "./evidence.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const defaultResultsRoot = path.resolve(here, "../../../load-test/results");
const repository = new EvidenceRepository({ resultsRoot: defaultResultsRoot });
const server = new McpServer({ name: "ticketon-evidence-mcp", version: "0.1.0" });

server.tool("list_evidence_runs", "로컬 고경합 측정 실행과 허용된 요약 파일 목록을 조회합니다.", {
  limit: z.number().int().min(1).max(50).optional()
}, async ({ limit }) => success(await repository.listRuns(limit)));

server.tool("get_run_summary", "JWT·쿠키·요청 본문을 제외한 단일 k6 측정 요약을 조회합니다.", {
  runId: z.string(),
  artifact: z.string().optional()
}, async ({ runId, artifact }) => withEvidenceError(async () => {
  const result = await repository.readSummary(runId, artifact);
  return success(result);
}));

server.tool("verify_domain_invariants", "측정 종료 후 좌석 수와 예약 상태 불변식을 검증합니다.", {
  runId: z.string(),
  artifact: z.string().optional()
}, async ({ runId, artifact }) => withEvidenceError(async () => {
  const result = await repository.readSummary(runId, artifact);
  return success({ artifact: result.artifact, report: createInvariantReport(result.summary) });
}));

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
