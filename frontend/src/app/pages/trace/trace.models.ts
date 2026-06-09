export interface AlgorithmTrace {
  forwardWon: boolean;
  forwardTrace: DirectionTrace;
  backwardTrace: DirectionTrace;
}

export interface DirectionTrace {
  discovery: DiscoveryTrace;
  learning: LearningTrace;
  application: ApplicationTrace;
}

export interface DiscoveryTrace {
}

export interface LearningTrace {
}

export interface ApplicationTrace {
}