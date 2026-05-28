package com.autojoin.synthesis;

import java.util.List;

public class BenchmarkFixture {
    String pair_id;
    Source source;
    Target target;
    GroundTruth ground_truth;

    static class Source {
        String file;
        List<String> key_columns;
    }

    static class Target {
        String file;
        List<String> key_columns;
    }

    static class GroundTruth {
        String file;
        String format;
        List<String> source_key_columns;
        List<String> target_key_columns;
    }
}