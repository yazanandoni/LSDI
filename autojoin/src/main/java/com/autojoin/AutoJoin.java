package com.autojoin;

import com.autojoin.model.Table;

/**
 * Main entry point for the Auto-Join algorithm.
 *
 * Automatically joins two tables whose join columns use different textual
 * representations by discovering and applying string transformations.
 *
 * Pipeline (Zhu et al., VLDB 2017):
 *   1. Find joinable row pairs via q-gram matching on suffix array indexes
 *   2. Learn a minimum-complexity transformation program from those row pairs
 *   3. Apply constrained fuzzy join to recover rows missed by the transformation
 */
public class AutoJoin {

    /**
     * Attempt to join two tables by discovering transformations in both directions
     * (ts→tt and tt→ts) and returning the result with higher row coverage.
     *
     * @param ts first table
     * @param tt second table
     * @return the best join result found, or an empty result if none
     */
    public JoinResult join(Table ts, Table tt) {
        throw new UnsupportedOperationException("Not yet implemented — coming in Phase 3+");
    }
}