export interface BenchmarkDescriptor {
  pairId: string;
  sourceRows: number;
  sourceColumns: number;
  targetRows: number;
  targetColumns: number;
  sourceKeys: string[];
  targetKeys: string[];
}

export interface Mismatch {
  sourceFingerprint: string;
  expectedTarget: string[] | null;
  joinedTarget: string;
}

export interface BenchmarkSummary {
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
  indexingTimeMs: number;
  learningTimeMs: number;
  joinTimeMs: number;
  fuzzyTimeMs: number;
  method: string;
  timedOut: boolean;
}
