package com.autojoin.backend.model;

import java.util.List;

public record BatchRunRequest(List<String> pairIds, List<String> methods) {}