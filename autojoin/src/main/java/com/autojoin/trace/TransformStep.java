package com.autojoin.trace;

import java.util.LinkedHashMap;
import java.util.Map;

public class TransformStep {
    private final String operatorType;
    private final String operatorDescription;
    private final Map<String, String> params;
    private final String output;

    public TransformStep(String operatorType, String operatorDescription,
                         Map<String, String> params, String output) {
        this.operatorType = operatorType;
        this.operatorDescription = operatorDescription;
        this.params = new LinkedHashMap<>(params);
        this.output = output;
    }

    public String getOperatorType() { return operatorType; }
    public String getOperatorDescription() { return operatorDescription; }
    public Map<String, String> getParams() { return params; }
    public String getOutput() { return output; }
}