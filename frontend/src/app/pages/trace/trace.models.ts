export interface AlgorithmTrace {
  forwardWon: boolean;
  forwardTrace: DirectionTrace;
  backwardTrace: DirectionTrace;
  inputTables: InputTablesTrace;
}

export interface InputTablesTrace {
  source: TableInfo;
  target: TableInfo;
}

export interface TableInfo {
  name: string;
  rows: number;
  columns: number;
  columnNames: string[];
  keyColumns: string[];
  sampleRows: string[][];
}

export interface DirectionTrace {
  discovery: DiscoveryTrace;
  learning: LearningTrace;
  application: ApplicationTrace;
}

export interface DiscoveryTrace {
  columnPairGroups: ColumnPairGroup[];
}

export interface ColumnPairGroup {
  sourceColumnName: string;
  targetColumnName: string;
  matchCount: number;
  avgScore: number;
  topMatches: QGramMatch[];
}

export interface QGramMatch {
  sourceValue: string;
  qgram: string;
  targetValue: string;
  score: number;
}

export interface LearningTrace {
  sourceColumnName: string;
  targetColumnName: string;
  injectiveScore: number;
  totalSourceRows: number;
  examplePairs: ExamplePairData[];
  operators: OperatorNode[];
  demoInput: string;
  demoTarget: string;
  transformDemo: TransformStep[];
  demoMatches: DemoMatch[];
}

export interface DemoMatch {
  sourceValue: string;
  transformedKey: string;
  targetValue: string;
  matches: boolean;
}

export interface ExamplePairData {
  sourceValue: string;
  targetValue: string;
}

export interface OperatorNode {
  type: string;
  description: string;
  params: Record<string, string>;
}

export interface TransformStep {
  operatorType: string;
  operatorDescription: string;
  params: Record<string, string>;
  output: string;
}

export interface ApplicationTrace {
  totalSourceRows: number;
  totalMatched: number;
  totalUnmatched: number;
  sampleMatches: SampleMatch[];
}

export interface SampleMatch {
  sourceValue: string;
  transformedKey: string;
  matchedTargetValue: string;
  status: string;
}