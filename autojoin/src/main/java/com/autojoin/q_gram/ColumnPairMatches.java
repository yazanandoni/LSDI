package com.autojoin.q_gram;

import java.util.List;

public class ColumnPairMatches {
    String sourceColumnName;
    String targetColumnName;
    List<MatchResult> matches; // Sorted highest score to lowest
}
