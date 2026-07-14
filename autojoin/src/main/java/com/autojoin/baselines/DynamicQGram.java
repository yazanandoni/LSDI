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

/**
 * DQ-P / DQ-R — Dynamic q-gram (paper §6.2).
 *
 * "Since q-grams already identify some joinable row-pairs (from which we
 * generate transformations), one may wonder if it is sufficient to perform
 * join using q-gram matches alone. In this method we use matches produced in
 * Section 3.1 ... Joinable row pairs are used directly as join result."
 *
 * These are sub-components of Auto-Join itself: the same §3.1 optimal-q-gram
 * search ({@link QGramFinder} over {@link ColumnSuffixIndex}) that AJ uses to
 * collect transformation examples, except its row pairs ARE the output — no
 * transformation is learned and no fuzzy join runs. "Matches produced in
 * Section 3.1" are what AJ's pipeline materializes, and that pipeline runs
 * discovery on the §4 row samples (paper parameters t=4, δ=0.8, r=0.1;
 * small tables where the required rate reaches 1 stay whole) — so DQ samples
 * identically. Emitted pairs are therefore capped by sample participation on
 * large cases, exactly like the paper's DQ numbers.
 *
 * The two variants differ only in which matches are admitted:
 * <ul>
 *   <li><b>DQ-P</b> (precision): "only allow 1-to-1 q-gram matches to ensure
 *       high precision" — the q-gram occurs in exactly one source and one
 *       target row (n = m = 1).</li>
 *   <li><b>DQ-R</b> (recall): "we allow n-to-1 q-gram matches as join
 *       results" — one target row (m = 1), any number of source rows, each of
 *       which joins that target row. "This produces results of higher recall
 *       but can also lead to lower precision compared to DQ-P."</li>
 * </ul>
 *
 * Matching runs source-to-target as in §3.1 (all source columns probed
 * against the target key columns' suffix indexes); a shared q-gram is a
 * substring of both sides, so the pair evidence is symmetric.
 */
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

        // §4: discovery runs on row samples with the paper's parameters, the
        // same call AJ's tryDirection makes before finding joinable row pairs.
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
                    if (m.getTargetFreq() != 1) continue;               // ambiguous target
                    if (oneToOneOnly && m.getSourceFreq() != 1) continue; // DQ-P: 1-to-1 only

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
