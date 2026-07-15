package com.autojoin.baselines;

import com.autojoin.model.Column;
import com.autojoin.model.Row;
import com.autojoin.model.Table;
import com.autojoin.q_gram.ColumnSuffixIndex;
import com.autojoin.q_gram.MatchResult;
import com.autojoin.q_gram.QGramFinder;
import com.autojoin.sampling.Sample;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

public final class DynamicQGram implements JoinMethod {

    /** Paper Appendix E.1 / Algorithm 3: matches with q* &lt; 3 are skipped. */
    private static final int MIN_QGRAM_LENGTH = 3;

    private final boolean oneToOneOnly;

    /** @param oneToOneOnly true for DQ-P (1-to-1 only), false for DQ-R (n-to-1). */
    public DynamicQGram(boolean oneToOneOnly) {
        this.oneToOneOnly = oneToOneOnly;
    }

    @Override
    public String name() { return oneToOneOnly ? "DQ-P" : "DQ-R"; }

    @Override
    public List<Row[]> join(JoinInput in) {
        QGramFinder finder = new QGramFinder();

        Sample.SampleResult sampled = Sample.sampleTables(in.source, in.target, 4, 0.8, 0.1);
        Table source = sampled.sourceSample();
        Table target = sampled.targetSample();

        // Index each target key column once (index construction dominates).
        List<Column> targetKeyColumns = target.getKeyColumns();
        List<ColumnSuffixIndex> targetIndexes = new ArrayList<>(targetKeyColumns.size());
        for (Column ct : targetKeyColumns) {
            targetIndexes.add(new ColumnSuffixIndex(ct.getValues()));
        }

        Set<Long> seen = new HashSet<>();
        List<Row[]> pairs = new ArrayList<>();
        for (Column cs : source.getColumns()) {
            ColumnSuffixIndex sourceIdx = new ColumnSuffixIndex(cs.getValues());
            Set<String> distinctValues = new HashSet<>(cs.getValues());
            for (ColumnSuffixIndex targetIdx : targetIndexes) {
                for (String v : distinctValues) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new CancellationException(name() + " cancelled (timeout)");
                    }
                    if (v == null || v.isEmpty()) continue;

                    MatchResult m = finder.findOptimalQGram(v, sourceIdx, targetIdx);
                    if (m == null || m.getQGram() == null
                            || m.getQGram().length() < MIN_QGRAM_LENGTH) continue;
                    if (m.getTargetFreq() != 1) continue;
                    if (oneToOneOnly && m.getSourceFreq() != 1) continue;

                    int tgtRow = m.getBestTargetRows().get(0);
                    for (int srcRow : m.getBestSourceRows()) {
                        long key = ((long) srcRow << 32) | (tgtRow & 0xffffffffL);
                        if (seen.add(key)) {
                            pairs.add(new Row[]{source.getRow(srcRow), target.getRow(tgtRow)});
                        }
                    }
                }
            }
        }
        return pairs;
    }
}
