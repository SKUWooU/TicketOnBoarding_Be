import { lstat, readdir, readFile, realpath } from "node:fs/promises";
import path from "node:path";

const RUN_ID = /^[A-Za-z0-9-]{1,64}$/;
const SUMMARY_FILE = /^[A-Za-z0-9-]+-summary\.json$/;
const SENSITIVE_KEY = /(authorization|cookie|jwt|token|password|secret|requestbody|responsebody)/i;

export type EvidenceErrorCode =
  | "INVALID_RUN_ID"
  | "RUN_NOT_FOUND"
  | "ARTIFACT_NOT_FOUND"
  | "UNSUPPORTED_ARTIFACT"
  | "MALFORMED_ARTIFACT";

export class EvidenceError extends Error {
  constructor(public readonly code: EvidenceErrorCode, message: string) {
    super(message);
  }
}

export interface EvidenceRepositoryOptions {
  resultsRoot: string;
}

export class EvidenceRepository {
  private readonly resultsRoot: string;

  constructor({ resultsRoot }: EvidenceRepositoryOptions) {
    this.resultsRoot = path.resolve(resultsRoot);
  }

  async listRuns(limit = 20) {
    const boundedLimit = Math.max(1, Math.min(limit, 50));
    const canonicalRoot = await this.canonicalRootOrUndefined();
    if (!canonicalRoot) return [];
    let entries;
    try {
      entries = await readdir(canonicalRoot, { withFileTypes: true });
    } catch {
      return [];
    }

    const candidates = await Promise.all(entries
      .filter((entry) => entry.isDirectory() && RUN_ID.test(entry.name))
      .sort((left, right) => right.name.localeCompare(left.name))
      .map(async (entry) => {
        const directory = await this.resolveCanonicalRunDirectory(entry.name, canonicalRoot);
        if (!directory) return undefined;
        const files = await readdir(directory);
        return {
          runId: entry.name,
          summaryArtifacts: files.filter((file) => SUMMARY_FILE.test(file)).sort(),
          aggregateArtifacts: files.filter((file) => file.endsWith("-aggregate.json")).sort(),
          manifestAvailable: files.some((file) => file.endsWith("-manifest.json"))
        };
      }));
    return candidates.filter((candidate): candidate is NonNullable<typeof candidate> => candidate !== undefined).slice(0, boundedLimit);
  }

  async readSummary(runId: string, artifact?: string) {
    const canonicalRoot = await this.canonicalRootOrThrow();
    const directory = await this.resolveCanonicalRunDirectory(runId, canonicalRoot);
    if (!directory) {
      throw new EvidenceError("RUN_NOT_FOUND", `측정 실행 '${runId}'을 찾을 수 없습니다.`);
    }
    let files: string[];
    try {
      files = await readdir(directory);
    } catch {
      throw new EvidenceError("RUN_NOT_FOUND", `측정 실행 '${runId}'을 찾을 수 없습니다.`);
    }
    const summaryArtifacts = files.filter((file) => SUMMARY_FILE.test(file)).sort();
    const selected = artifact ?? summaryArtifacts[0];
    if (!selected || !SUMMARY_FILE.test(selected) || !summaryArtifacts.includes(selected)) {
      throw new EvidenceError("UNSUPPORTED_ARTIFACT", "summary.json 형식의 허용된 측정 요약만 조회할 수 있습니다.");
    }

    let parsed: unknown;
    try {
      // PowerShell-generated summaries can begin with a UTF-8 BOM. It is not part
      // of JSON text, so remove only that leading marker before parsing.
      const artifactPath = await realpath(path.join(directory, selected));
      if (!isDescendant(directory, artifactPath)) {
        throw new EvidenceError("UNSUPPORTED_ARTIFACT", "결과 실행 디렉터리 밖을 가리키는 artifact는 조회할 수 없습니다.");
      }
      parsed = JSON.parse((await readFile(artifactPath, "utf8")).replace(/^\uFEFF/, ""));
    } catch (error) {
      if (error instanceof EvidenceError) throw error;
      throw new EvidenceError("MALFORMED_ARTIFACT", `'${selected}' 요약 파일을 읽을 수 없습니다.`);
    }
    return { artifact: selected, summary: sanitize(parsed) };
  }

  private async canonicalRootOrUndefined() {
    try {
      // The configured root is itself part of the security boundary. Do not
      // follow a Windows junction or symbolic link before establishing it.
      if ((await lstat(this.resultsRoot)).isSymbolicLink()) return undefined;
      return await realpath(this.resultsRoot);
    } catch {
      return undefined;
    }
  }

  private async canonicalRootOrThrow() {
    const root = await this.canonicalRootOrUndefined();
    if (!root) throw new EvidenceError("RUN_NOT_FOUND", "측정 결과 루트를 찾을 수 없습니다.");
    return root;
  }

  private async resolveCanonicalRunDirectory(runId: string, canonicalRoot: string) {
    if (!RUN_ID.test(runId)) {
      throw new EvidenceError("INVALID_RUN_ID", "runId는 영문자·숫자·하이픈만 사용할 수 있습니다.");
    }
    const candidate = path.resolve(canonicalRoot, runId);
    if (!isDescendant(canonicalRoot, candidate)) {
      throw new EvidenceError("INVALID_RUN_ID", "허용되지 않은 runId입니다.");
    }
    try {
      const directory = await realpath(candidate);
      return isDescendant(canonicalRoot, directory) ? directory : undefined;
    } catch {
      return undefined;
    }
  }
}

function isDescendant(parent: string, candidate: string) {
  const relative = path.relative(parent, candidate);
  return relative !== "" && !relative.startsWith(`..${path.sep}`) && relative !== ".." && !path.isAbsolute(relative);
}

export function createInvariantReport(summary: unknown) {
  const record = asRecord(summary);
  const k6 = asRecord(record.K6);
  const snapshot = asRecord(k6.FinalSnapshot);
  const checks = [
    checkBoolean("validMeasurement", record.ValidMeasurement),
    checkBoolean("k6ExitCodeZero", k6.ExitCode === 0),
    checkBoolean("finalSnapshotInvariant", snapshot.invariantSatisfied),
    checkSeatCount(snapshot)
  ];
  const insufficient = checks.some((check) => check.status === "INSUFFICIENT_EVIDENCE");
  const failed = checks.some((check) => check.status === "FAIL");
  return {
    status: insufficient ? "INSUFFICIENT_EVIDENCE" : failed ? "FAIL" : "PASS",
    checks
  };
}

function checkBoolean(name: string, value: unknown) {
  if (typeof value !== "boolean") return { name, status: "INSUFFICIENT_EVIDENCE", detail: "측정 요약에 값이 없습니다." };
  return { name, status: value ? "PASS" : "FAIL", detail: String(value) };
}

function checkSeatCount(snapshot: Record<string, unknown>) {
  const expected = snapshot.expectedTotalSeats;
  const actual = snapshot.actualSeatCount;
  if (typeof expected !== "number" || typeof actual !== "number") {
    return { name: "seatCountMatchesFixture", status: "INSUFFICIENT_EVIDENCE", detail: "최종 좌석 수 필드가 없습니다." };
  }
  return { name: "seatCountMatchesFixture", status: expected === actual ? "PASS" : "FAIL", detail: `expected=${expected}, actual=${actual}` };
}

function asRecord(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function sanitize(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sanitize);
  if (value !== null && typeof value === "object") {
    return Object.fromEntries(Object.entries(value as Record<string, unknown>)
      .filter(([key]) => !SENSITIVE_KEY.test(key))
      .map(([key, nested]) => [key, sanitize(nested)]));
  }
  return value;
}
