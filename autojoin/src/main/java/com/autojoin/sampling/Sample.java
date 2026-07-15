package com.autojoin.sampling;

import com.autojoin.model.Column;
import com.autojoin.model.Table;

import java.util.*;

public class Sample {

        public record SampleResult(Table sourceSample, Table targetSample) {
    }

    /**
     * calculates sample sizes and draws samples
     *
     * @param sourceTable the original source table
     * @param targetTable the original target table
     * @param t           required number of examples, usually 4 according to paper
     * @param delta       parameter for confidence interval (e.g. 0.8)
     * @param r           estimated join-participation rate (e.g. 0.01 for 1% or 0.1 for 10%)
     * @return            SampleResult with sample source and sample target table
     */
    public static SampleResult sampleTables(Table sourceTable, Table targetTable, double t, double delta, double r) {
        int numSourceRows = sourceTable.numRows();
        int numTargetRows = targetTable.numRows();

        if (numSourceRows <= 0 || numTargetRows <= 0 || r <= 0 || delta >= 1.0) {
            return new SampleResult(sourceTable, targetTable);
        }

        double sourceProb = (1.0 / numSourceRows) * Math.sqrt((t * numTargetRows) / ((1.0 - delta) * r));
        double targetProb = (1.0 / numTargetRows) * Math.sqrt((t * numSourceRows) / ((1.0 - delta) * r));

        int sourceSampleSize = sourceProb >= 1.0 ? numSourceRows : (int) Math.ceil(numSourceRows * sourceProb);
        int targetSampleSize = targetProb >= 1.0 ? numTargetRows : (int) Math.ceil(numTargetRows * targetProb);

        Table sourceSample = drawSample(sourceTable, sourceSampleSize);
        Table targetSample = drawSample(targetTable, targetSampleSize);

        return new SampleResult(sourceSample, targetSample);
    }

    public static Table drawSample(Table originalTable, int sampleSize) {
        int n = originalTable.numRows();

        if (sampleSize >= n) {
            return originalTable;
        }

        Random random = new Random();

        Set<Integer> pickedIndices = new HashSet<>((int)(sampleSize / 0.75) + 1);
        while (pickedIndices.size() < sampleSize) {
            pickedIndices.add(random.nextInt(n));
        }

        List<String> columnNames = originalTable.getRow(0).getColumnNames();
        List<Column> sampleColumns = new ArrayList<>(columnNames.size());

        for (String columnName : columnNames) {
            Optional<Column> optionalCol = originalTable.getColumn(columnName);
            Column originalCol = optionalCol.get();
            List<String> sampleValues = new ArrayList<>(sampleSize);

            for (int index : pickedIndices) {
                String value = originalCol.getValue(index);
                sampleValues.add(value);
            }

            Column sampleCol = new Column(originalCol.getName(), sampleValues, originalCol.isKey());
            sampleColumns.add(sampleCol);
        }

        return new Table(originalTable.getName(), sampleColumns);
    }
}
