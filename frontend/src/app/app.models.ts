export interface BenchmarkDescriptor {
  pairId: string;
  sourceRows: number;
  sourceColumns: number;
  targetRows: number;
  targetColumns: number;
  sourceKeys: string[];
  targetKeys: string[];
}

export interface BenchmarkRunRequest {
  pairId: string;
}

export interface ResultIdResponse {
  resultId: string;
}

export interface Mismatch {
  sourceFingerprint: string;
  expectedTarget: string[] | null;
  joinedTarget: string;
}

export interface BenchmarkSummaryView {
  resultId: string;
  pairId: string;
  direction: string;
  truePositives: number;
  joinedPairs: number;
  groundTruthPairs: number;
  precision: number;
  recall: number;
  durationMs: number;
  transformation: string | null;
  mismatches: Mismatch[];
}
