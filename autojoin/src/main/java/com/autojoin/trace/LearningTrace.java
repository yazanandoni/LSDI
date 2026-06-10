package com.autojoin.trace;

import java.util.List;

public class LearningTrace {
    private final String sourceColumnName;
    private final String targetColumnName;
    private final int injectiveScore;
    private final int totalSourceRows;
    private final List<ExamplePairData> examplePairs;
    private final List<OperatorNode> operators;
    private final String demoInput;
    private final String demoTarget;
    private final List<TransformStep> transformDemo;
    private final List<DemoMatch> demoMatches;

    public LearningTrace() {
        this("", "", 0, 0, List.of(), List.of(), "", "", List.of(), List.of());
    }

    public LearningTrace(String sourceColumnName, String targetColumnName,
                         int injectiveScore, int totalSourceRows,
                         List<ExamplePairData> examplePairs,
                         List<OperatorNode> operators,
                         String demoInput, String demoTarget,
                         List<TransformStep> transformDemo,
                         List<DemoMatch> demoMatches) {
        this.sourceColumnName = sourceColumnName;
        this.targetColumnName = targetColumnName;
        this.injectiveScore = injectiveScore;
        this.totalSourceRows = totalSourceRows;
        this.examplePairs = examplePairs;
        this.operators = operators;
        this.demoInput = demoInput;
        this.demoTarget = demoTarget;
        this.transformDemo = transformDemo;
        this.demoMatches = demoMatches;
    }

    public String getSourceColumnName() { return sourceColumnName; }
    public String getTargetColumnName() { return targetColumnName; }
    public int getInjectiveScore() { return injectiveScore; }
    public int getTotalSourceRows() { return totalSourceRows; }
    public List<ExamplePairData> getExamplePairs() { return examplePairs; }
    public List<OperatorNode> getOperators() { return operators; }
    public String getDemoInput() { return demoInput; }
    public String getDemoTarget() { return demoTarget; }
    public List<TransformStep> getTransformDemo() { return transformDemo; }
    public List<DemoMatch> getDemoMatches() { return demoMatches; }
}