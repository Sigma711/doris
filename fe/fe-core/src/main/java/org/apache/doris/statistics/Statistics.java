// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.statistics;

import org.apache.doris.catalog.Column;
import org.apache.doris.nereids.stats.StatsMathUtil;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.Slot;
import org.apache.doris.nereids.trees.expressions.SlotReference;
import org.apache.doris.nereids.types.coercion.CharacterType;

import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class Statistics {
    private static final int K_BYTES = 1024;

    private final double rowCount;

    private final Map<Expression, ColumnStatistic> expressionToColumnStats;
    private final int widthInJoinCluster;

    // the byte size of one tuple
    private double tupleSize;

    private double deltaRowCount = 0.0;

    private long actualRowCount = -1L;
    private boolean isFromHbo = false;

    public Statistics(Statistics another) {
        this.rowCount = another.rowCount;
        this.widthInJoinCluster = another.widthInJoinCluster;
        this.expressionToColumnStats = new HashMap<>(another.expressionToColumnStats);
        this.tupleSize = another.tupleSize;
        this.deltaRowCount = another.getDeltaRowCount();
        this.isFromHbo = another.isFromHbo;
    }

    public Statistics(double rowCount, int widthInJoinCluster,
            Map<Expression, ColumnStatistic> expressionToColumnStats, double deltaRowCount, boolean isFromHbo) {
        this.rowCount = rowCount;
        this.widthInJoinCluster = widthInJoinCluster;
        this.expressionToColumnStats = expressionToColumnStats;
        this.deltaRowCount = deltaRowCount;
        this.isFromHbo = isFromHbo;
    }

    public Statistics(double rowCount, Map<Expression, ColumnStatistic> expressionToColumnStats) {
        this(rowCount, 1, expressionToColumnStats, 0, false);
    }

    public Statistics(double rowCount, int widthInJoinCluster,
            Map<Expression, ColumnStatistic> expressionToColumnStats) {
        this(rowCount, widthInJoinCluster, expressionToColumnStats, 0, false);
    }

    public ColumnStatistic findColumnStatistics(Expression expression) {
        return expressionToColumnStats.get(expression);
    }

    public Map<Expression, ColumnStatistic> columnStatistics() {
        return expressionToColumnStats;
    }

    public double getRowCount() {
        return rowCount;
    }

    public Statistics withRowCount(double rowCount) {
        return new Statistics(rowCount, widthInJoinCluster, new HashMap<>(expressionToColumnStats),
                0, isFromHbo);
    }

    public Statistics withExpressionToColumnStats(Map<Expression, ColumnStatistic> expressionToColumnStats) {
        return new Statistics(rowCount, widthInJoinCluster, expressionToColumnStats, 0, isFromHbo);
    }

    /**
     * Update by count.
     */
    public Statistics withRowCountAndEnforceValid(double rowCount) {
        Statistics statistics = new Statistics(rowCount, widthInJoinCluster,
                expressionToColumnStats, 0, isFromHbo);
        statistics.normalizeColumnStatistics();
        return statistics;
    }

    public Statistics withRowCountAndHboFlag(double rowCount) {
        Statistics statistics = withRowCountAndEnforceValid(rowCount);
        statistics.setFromHbo(true);
        return statistics;
    }

    // IMPORTANT: it is suggested to do this action after each estimation critical visiting,
    // since statistics will have serious deviation during the partial deriving.
    public void normalizeColumnStatistics() {
        normalizeColumnStatistics(this.rowCount);
    }

    public void normalizeColumnStatistics(double inputRowCount) {
        normalizeColumnStatistics(this.rowCount, false);
    }

    public void normalizeColumnStatistics(double inputRowCount, boolean isNumNullsDecreaseByProportion) {
        double factor = isNumNullsDecreaseByProportion ? rowCount / inputRowCount : 1.0;
        for (Entry<Expression, ColumnStatistic> entry : expressionToColumnStats.entrySet()) {
            ColumnStatistic columnStatistic = entry.getValue();
            // the following columnStatistic.isUnKnown() judgment is loop inside since current doris
            // supports partial stats deriving, i.e, allowing part of tables have stats and other parts don't,
            // or part of columns have stats but other parts don't, especially join and filter estimation.
            if (!columnStatistic.isUnKnown() && (!checkColumnStatsValid(columnStatistic, rowCount)
                    || isNumNullsDecreaseByProportion && columnStatistic.numNulls != 0)) {
                ColumnStatisticBuilder columnStatisticBuilder = new ColumnStatisticBuilder(columnStatistic);
                double ndv = Math.min(columnStatistic.ndv, rowCount);
                double numNulls = columnStatistic.numNulls;
                if (numNulls > 0) {
                    numNulls = Math.max(1, Math.min(columnStatistic.numNulls * factor, rowCount - ndv));
                }
                columnStatisticBuilder.setNumNulls(numNulls);
                columnStatisticBuilder.setNdv(ndv);
                columnStatistic = columnStatisticBuilder.build();
                expressionToColumnStats.put(entry.getKey(), columnStatistic);
            }
        }
    }

    public boolean checkColumnStatsValid(ColumnStatistic columnStatistic, double rowCount) {
        return columnStatistic.ndv <= rowCount && columnStatistic.numNulls <= rowCount - columnStatistic.ndv;
    }

    public Statistics withSel(double sel) {
        return withSel(sel, 0);
    }

    public Statistics withSel(double notNullSel, double numNull) {
        notNullSel = StatsMathUtil.minNonNaN(notNullSel, 1);
        if (Double.isNaN(rowCount)) {
            return this;
        }
        double newCount = rowCount * notNullSel + numNull;
        return new Statistics(newCount, widthInJoinCluster, new HashMap<>(expressionToColumnStats),
                0, isFromHbo);
    }

    public Statistics addColumnStats(Expression expression, ColumnStatistic columnStatistic) {
        expressionToColumnStats.put(expression, columnStatistic);
        return this;
    }

    public boolean isInputSlotsUnknown(Set<Slot> inputs) {
        boolean unknown = true;
        for (Slot input : inputs) {
            if (!(expressionToColumnStats.containsKey(input)
                    && expressionToColumnStats.get(input).isUnKnown)) {
                unknown = false;
            }
        }
        return unknown;
    }

    public double computeTupleSize(List<Slot> slots) {
        if (tupleSize <= 0) {
            double tempSize = 0.0;
            for (Slot slot : slots) {
                ColumnStatistic s = expressionToColumnStats.get(slot);
                if (s != null) {
                    tempSize += Math.max(1, Math.min(CharacterType.DEFAULT_WIDTH, s.avgSizeByte));
                }
            }
            tupleSize = Math.max(1, tempSize);
        }
        return tupleSize;
    }

    public List<Slot> getAllSlotsFromColumnStatsMap() {
        return expressionToColumnStats.keySet().stream()
                .filter(Slot.class::isInstance).map(expr -> (Slot) expr)
                .collect(Collectors.toList());
    }

    public double computeSize(List<Slot> slots) {
        return computeTupleSize(slots) * rowCount;
    }

    public double dataSizeFactor(List<Slot> slots) {
        boolean allUnknown = true;
        for (Slot slot : slots) {
            if (slot instanceof SlotReference) {
                Optional<Column> colOpt = ((SlotReference) slot).getOriginalColumn();
                if (colOpt.isPresent() && colOpt.get().isVisible()) {
                    ColumnStatistic colStats = expressionToColumnStats.get(slot);
                    if (colStats != null && !colStats.isUnKnown) {
                        allUnknown = false;
                        break;
                    }
                }
            }
        }
        if (allUnknown) {
            double lowerBound = 0.03;
            double upperBound = 0.07;
            return Math.min(Math.max(computeTupleSize(slots) / K_BYTES, lowerBound), upperBound);
        } else {
            return 0.05 * computeTupleSize(slots);
        }
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        if (isFromHbo) {
            builder.append("(hbo)");
        }
        if (Double.isNaN(rowCount)) {
            builder.append("NaN");
        } else if (Double.POSITIVE_INFINITY == rowCount) {
            builder.append("Infinite");
        } else if (Double.NEGATIVE_INFINITY == rowCount) {
            builder.append("-Infinite");
        } else {
            DecimalFormat format = new DecimalFormat("#,###.##");
            builder.append(format.format(rowCount));
        }
        if (deltaRowCount > 0) {
            builder.append("(").append((long) deltaRowCount).append(")");
        }
        if (actualRowCount != -1) {
            builder.append(" actualRows=").append(actualRowCount);
        }
        return builder.toString();
    }

    public String printColumnStats() {
        StringBuilder builder = new StringBuilder();
        for (Expression key : expressionToColumnStats.keySet()) {
            ColumnStatistic columnStatistic = expressionToColumnStats.get(key);
            builder.append("  ").append(key).append(" -> ").append(columnStatistic).append("\n");
        }
        return builder.toString();
    }

    public int getBENumber() {
        return 1;
    }

    public static double getValidSelectivity(double selectivity) {
        return selectivity < 0 ? 0 : (selectivity > 1 ? 1 : selectivity);
    }

    /**
     * merge this and other colStats.ndv, choose min
     */
    public void updateNdv(Statistics other) {
        for (Expression expr : expressionToColumnStats.keySet()) {
            ColumnStatistic otherColStats = other.findColumnStatistics(expr);
            if (otherColStats != null) {
                ColumnStatistic thisColStats = expressionToColumnStats.get(expr);
                if (thisColStats.ndv > otherColStats.ndv) {
                    expressionToColumnStats.put(expr,
                            new ColumnStatisticBuilder(thisColStats).setNdv(otherColStats.ndv).build());
                }
            }
        }
    }

    public String detail(String prefix) {
        StringBuilder builder = new StringBuilder();
        builder.append(prefix).append("rows=").append(rowCount).append("\n");
        builder.append(prefix).append("tupleSize=")
                .append(computeTupleSize(getAllSlotsFromColumnStatsMap())).append("\n");
        builder.append(prefix).append("width=").append(widthInJoinCluster).append("\n");
        for (Entry<Expression, ColumnStatistic> entry : expressionToColumnStats.entrySet()) {
            builder.append(prefix).append(entry.getKey()).append(" -> ").append(entry.getValue()).append("\n");
        }
        return builder.toString();
    }

    public int getWidthInJoinCluster() {
        return widthInJoinCluster;
    }

    public double getDeltaRowCount() {
        return deltaRowCount;
    }

    public void setDeltaRowCount(double deltaRowCount) {
        this.deltaRowCount = deltaRowCount;
    }

    public long getActualRowCount() {
        return actualRowCount;
    }

    public void setActualRowCount(long actualRowCount) {
        this.actualRowCount = actualRowCount;
    }

    public void setFromHbo(boolean isFromHbo) {
        this.isFromHbo = isFromHbo;
    }

    public boolean isFromHbo() {
        return this.isFromHbo;
    }
}
