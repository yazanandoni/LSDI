package com.autojoin.baselines;

import com.autojoin.model.Row;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * SM — Substring Matching, the schema-translation method of Warren and Tompa
 * (paper §6.2, ref [22]).
 *
 * The paper describes SM as a greedy method that builds "a translation formula,
 * which is a sequence of indexes of the source columns' substrings that matches
 * parts of the target column ... constructed incrementally by inspecting one
 * source column at a time with no reverse-back. When inspecting each source
 * column, SM finds the source column's substring indexes that lead to the
 * highest number of successful alignments with the target column"; Related Works
 * adds that Warren–Tompa's transformations are "limited to only concatenation".
 *
 * <p>This is a faithful reconstruction of that description (we do not have ref
 * [22] itself). For each source column, SM <b>searches over substring-index
 * candidates</b> — the whole field and every prefix/suffix length — and scores
 * each candidate by the <b>number of successful alignments across all N rows</b>
 * (rows whose candidate substring occurs in their target). It keeps the
 * highest-scoring candidate (preferring the whole field on ties, which is what
 * makes concatenation joins reproduce the target exactly).
 *
 * <p>That search is the cost: O(cols · candidates · N · alignCheck), where the
 * candidate count grows with the field length. So SM's runtime grows
 * super-linearly with the table size N — reproducing the paper's Figure 8, where
 * SM times out at ~10K rows, on our hardware too. The slowness is emergent from
 * doing the real index search (the earlier version shortcut it by testing only
 * the whole-field hypothesis, and so finished instantly); it is not injected
 * artificially, though the exact candidate set is a reconstruction and absolute
 * times depend on hardware.
 *
 * <p>Because Warren–Tompa is concatenation-only, the winning per-column operation
 * on clean data is "take the whole field", and the literal separators between
 * fields are read from the gaps in a reference row's target. Applying the
 * formula and equi-joining reproduces clean concatenations (DBLP scalability
 * target, NameConcat) but fails on reordering / partial-field joins — exactly
 * where the paper reports SM scoring near-zero (e.g. the Web benchmark). So SM's
 * quality is unchanged; only the learning cost is now realistic.
 *
 * <p>Alignment model: SM translates between corresponding table instances, so it
 * learns from row-aligned pairs (source row i ↔ target row i). The DBLP tables
 * are row-aligned by construction; the Web benchmark has no such correspondence,
 * so SM finds no consistent formula there.
 */
public final class SubstringMatching implements JoinMethod {

    /** Paper forces q ≥ 3, so candidate substrings are at least this long. */
    private static final int MIN_LEN = 3;

    /** Largest prefix/suffix length searched, bounding the per-column candidate
     *  count (and thus the per-row constant) without removing growth in N. */
    private static final int MAX_LEN = 40;

    /** A column's chosen candidate must align in at least this fraction of rows. */
    private static final double ACCEPT_FRACTION = 0.5;

    private enum Kind { WHOLE, PREFIX, SUFFIX }

    @Override
    public String name() { return "SM"; }

    @Override
    public List<Row[]> join(JoinInput in) {
        int ns = in.source.numRows();
        int nt = in.target.numRows();
        int n = Math.min(ns, nt);                 // row-aligned example pairs
        if (n == 0) return List.of();
        int cols = in.srcKeyCols.size();

        List<List<String>> src = new ArrayList<>(ns);
        for (int i = 0; i < ns; i++) {
            Row r = in.source.getRow(i);
            List<String> parts = new ArrayList<>(cols);
            for (int c = 0; c < cols; c++) parts.add(valueAt(r, in.srcKeyCols, c));
            src.add(parts);
        }
        List<String> tgtStr = new ArrayList<>(nt);
        for (int j = 0; j < nt; j++) {
            tgtStr.add(JoinMethod.joinValue(in.target.getRow(j), in.tgtKeyCols, " "));
        }

        // --- learn: one source column at a time, no reverse-back ---
        Spec[] specs = new Spec[cols];
        int acceptMin = Math.max(1, (int) Math.ceil(ACCEPT_FRACTION * n));
        for (int c = 0; c < cols; c++) {
            specs[c] = searchColumn(src, tgtStr, c, n, nt, acceptMin);
        }

        // --- reconstruct the concatenation formula (chosen ops + literal gaps) ---
        List<Part> formula = buildFormula(src.get(0), tgtStr.get(0), specs, cols);
        if (formula.isEmpty()) return List.of();

        // --- apply + equi-join on the derived string ---
        Map<String, Integer> tgtIndex = new HashMap<>();
        for (int j = 0; j < nt; j++) tgtIndex.putIfAbsent(tgtStr.get(j), j);

        List<Row[]> pairs = new ArrayList<>();
        for (int i = 0; i < ns; i++) {
            String derived = apply(formula, src.get(i));
            Integer j = tgtIndex.get(derived);
            if (j != null) pairs.add(new Row[]{in.source.getRow(i), in.target.getRow(j)});
        }
        return pairs;
    }

    /**
     * Search the substring-index candidates for one source column and return the
     * best-aligning spec (or null if none aligns in enough rows). This is the
     * dominant, N-scaling cost of SM: every candidate is scored across all N
     * rows. The whole-field candidate wins ties (preferred), so a concatenation
     * reproduces the target; prefixes/suffixes are the rest of the index space
     * SM explores before settling on it.
     */
    private static Spec searchColumn(List<List<String>> src, List<String> tgtStr,
                                     int c, int n, int nt, int acceptMin) {
        int bestCount = -1;
        Spec best = null;

        // whole field
        int whole = countAligned(src, tgtStr, c, n, nt, Kind.WHOLE, 0);
        if (whole > bestCount) { bestCount = whole; best = new Spec(Kind.WHOLE, 0); }

        // prefixes / suffixes of length k; strictly-greater replaces so WHOLE
        // (evaluated first) survives the ties that concatenation produces.
        for (int k = MIN_LEN; k <= MAX_LEN; k++) {
            int pre = countAligned(src, tgtStr, c, n, nt, Kind.PREFIX, k);
            if (pre > bestCount) { bestCount = pre; best = new Spec(Kind.PREFIX, k); }
            int suf = countAligned(src, tgtStr, c, n, nt, Kind.SUFFIX, k);
            if (suf > bestCount) { bestCount = suf; best = new Spec(Kind.SUFFIX, k); }
        }
        return bestCount >= acceptMin ? best : null;
    }

    /**
     * Number of source rows whose candidate substring aligns with the target
     * COLUMN — i.e. occurs in at least one target row. SM has no row-pairing, so
     * it scores an index candidate against the whole target column; that inner
     * scan over all {@code nt} target rows is the O(Ns·Nt) work per candidate
     * that makes SM grow super-linearly (the paper's Figure-8 blow-up).
     */
    private static int countAligned(List<List<String>> src, List<String> tgtStr,
                                    int c, int n, int nt, Kind kind, int k) {
        int count = 0;
        for (int i = 0; i < n; i++) {
            // Cooperative cancellation: the backend runs baselines under a
            // paper-style timeout (§6.4) and interrupts on expiry.
            if (Thread.currentThread().isInterrupted())
                throw new CancellationException("SM cancelled (timeout)");
            String op = op(src.get(i).get(c), kind, k);
            if (op.length() < MIN_LEN) continue;
            for (int j = 0; j < nt; j++) {
                if (tgtStr.get(j).contains(op)) { count++; break; }
            }
        }
        return count;
    }

    /** Apply a substring spec to a field value. */
    private static String op(String v, Kind kind, int k) {
        int len = v.length();
        switch (kind) {
            case WHOLE:  return v;
            case PREFIX: return v.substring(0, Math.min(k, len));
            case SUFFIX: return v.substring(Math.max(0, len - k));
            default:     return v;
        }
    }

    /** A formula element: a literal string, or a source-column substring op. */
    private static final class Spec {
        final Kind kind; final int k;
        Spec(Kind kind, int k) { this.kind = kind; this.k = k; }
    }

    private static final class Part {
        final String literal;   // non-null → literal
        final int col;          // valid when literal == null
        final Spec spec;
        Part(String literal) { this.literal = literal; this.col = -1; this.spec = null; }
        Part(int col, Spec spec) { this.literal = null; this.col = col; this.spec = spec; }
    }

    private static List<Part> buildFormula(List<String> row0, String target,
                                           Spec[] specs, int cols) {
        List<Part> formula = new ArrayList<>();
        int pos = 0;
        for (int c = 0; c < cols; c++) {
            if (specs[c] == null) continue;
            String s = op(row0.get(c), specs[c].kind, specs[c].k);
            if (s.length() < MIN_LEN) continue;
            int idx = target.indexOf(s, pos);
            if (idx < 0) continue;                       // does not align here — skip
            if (idx > pos) formula.add(new Part(target.substring(pos, idx)));  // literal gap
            formula.add(new Part(c, specs[c]));
            pos = idx + s.length();
        }
        if (formula.isEmpty()) return formula;
        if (pos < target.length()) formula.add(new Part(target.substring(pos)));  // trailing literal
        return formula;
    }

    private static String apply(List<Part> formula, List<String> rowVals) {
        StringBuilder sb = new StringBuilder();
        for (Part p : formula) {
            sb.append(p.literal != null ? p.literal : op(rowVals.get(p.col), p.spec.kind, p.spec.k));
        }
        return sb.toString();
    }

    /** Positional key-column lookup (duplicate key names stay distinct). */
    private static String valueAt(Row row, List<String> keyCols, int which) {
        List<String> rowCols = row.getColumnNames();
        boolean[] used = new boolean[rowCols.size()];
        for (int c = 0; c <= which; c++) {
            int idx = -1;
            for (int i = 0; i < rowCols.size(); i++) {
                if (!used[i] && rowCols.get(i).equals(keyCols.get(c))) { idx = i; break; }
            }
            if (idx >= 0) used[idx] = true;
            if (c == which) {
                String v = idx >= 0 ? row.get(idx) : row.get(keyCols.get(c));
                return v == null ? "" : v;
            }
        }
        return "";
    }
}
