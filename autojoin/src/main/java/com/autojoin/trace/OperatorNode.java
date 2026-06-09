package com.autojoin.trace;

import java.util.LinkedHashMap;
import java.util.Map;

public class OperatorNode {
    private final String type;
    private final String description;
    private final Map<String, String> params;

    public OperatorNode(String type, String description, Map<String, String> params) {
        this.type = type;
        this.description = description;
        this.params = new LinkedHashMap<>(params);
    }

    public String getType() { return type; }
    public String getDescription() { return description; }
    public Map<String, String> getParams() { return params; }
}