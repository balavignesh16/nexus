package com.nexus.intelligence.anomaly;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns raw rows into an ordered, de-duplicated numeric series. The result does not depend on the order of the input rows (rows are sorted by event time and
 * then value), which is what makes out-of-order delivery harmless. BOOLEAN, STRING, ENUM and OBJECT capabilities are NOT_NUMERIC: they are never treated as
 * continuous readings.
 */
public final class SeriesCleaner {
    private SeriesCleaner() {}

    public static CleanedSeries clean(List<RawRow> rows, Instant evaluatedAt, int skewToleranceSeconds, boolean truncated) {
        int raw = rows.size();
        if (rows.isEmpty()) return new CleanedSeries(CleanedSeries.Applicability.EMPTY, List.of(), null, new CleanedSeries.Quality(0, 0, 0, 0, 0, 0, truncated));
        Set<String> types = new HashSet<>();
        for (RawRow r : rows) types.add(r.valueType() == null ? "UNKNOWN" : r.valueType());
        if (types.size() > 1) return new CleanedSeries(CleanedSeries.Applicability.MIXED_VALUE_TYPES, List.of(), null, new CleanedSeries.Quality(raw, 0, 0, 0, 0, 0, truncated));
        if (!types.contains("NUMBER")) return new CleanedSeries(CleanedSeries.Applicability.NOT_NUMERIC, List.of(), null, new CleanedSeries.Quality(raw, 0, 0, 0, 0, 0, truncated));

        Instant latest = evaluatedAt.plusSeconds(skewToleranceSeconds);
        int future = 0, nonFinite = 0;
        List<RawRow> usable = new ArrayList<>();
        Set<String> units = new HashSet<>();
        for (RawRow r : rows) {
            if (r.numeric() == null || r.numeric().isNaN() || r.numeric().isInfinite()) {
                nonFinite++;
                continue;
            }
            if (r.at().isAfter(latest)) {
                future++;
                continue;
            }
            usable.add(r);
            units.add(r.unit() == null ? "" : r.unit());
        }
        if (units.size() > 1) return new CleanedSeries(CleanedSeries.Applicability.MIXED_UNITS, List.of(), null, new CleanedSeries.Quality(raw, 0, 0, 0, future, nonFinite, truncated));

        usable.sort(Comparator.comparing(RawRow::at).thenComparingDouble(RawRow::numeric).thenComparing(RawRow::id));
        List<Sample> out = new ArrayList<>();
        int duplicates = 0, conflicts = 0;
        int i = 0;
        while (i < usable.size()) {
            int j = i;
            while (j < usable.size() && usable.get(j).at().equals(usable.get(i).at())) j++;
            boolean same = true;
            for (int k = i + 1; k < j; k++) same &= usable.get(k).numeric().equals(usable.get(i).numeric());
            if (same) {
                out.add(new Sample(usable.get(i).id(), usable.get(i).at(), usable.get(i).numeric()));
                duplicates += j - i - 1;
            } else {
                conflicts++;
            }
            i = j;
        }
        String unit = units.isEmpty() || units.contains("") && units.size() == 1 ? null : units.iterator().next();
        CleanedSeries.Quality q = new CleanedSeries.Quality(raw, out.size(), duplicates, conflicts, future, nonFinite, truncated);
        return new CleanedSeries(out.isEmpty() ? CleanedSeries.Applicability.EMPTY : CleanedSeries.Applicability.NUMERIC, out, unit, q);
    }
}
