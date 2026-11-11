package com.nekocong.text2sql.gbi;

import com.nekocong.text2sql.sql.model.ColumnMeta;
import com.nekocong.text2sql.sql.model.QueryResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Infers a simple chart from a query result (a slice of DB-GPT's Generative-BI charting).
 *
 * <p>Heuristic, deterministic and model-free: pick the first non-numeric column as the dimension and
 * the last numeric column as the measure; a time-like dimension yields a line chart, otherwise a
 * bar chart. Anything ambiguous (fewer than two columns, no numeric measure, empty result) is left
 * as a plain table.
 */
@Component
public class ChartInference {

    /**
     * Infers a chart spec for a result.
     *
     * @param result the query result
     * @return the chart spec, or {@link ChartSpec#none()}
     */
    public ChartSpec infer(QueryResult result) {
        if (result == null || result.columns().size() < 2 || result.rows().isEmpty()) {
            return ChartSpec.none();
        }
        List<String> dimensions = new ArrayList<>();
        List<String> measures = new ArrayList<>();
        String timeDimension = null;
        for (ColumnMeta column : result.columns()) {
            if (isNumeric(column.typeName())) {
                measures.add(column.name());
            } else {
                dimensions.add(column.name());
                if (timeDimension == null && isTimeLike(column)) {
                    timeDimension = column.name();
                }
            }
        }
        if (dimensions.isEmpty() || measures.isEmpty()) {
            return ChartSpec.none();
        }
        String x = timeDimension != null ? timeDimension : dimensions.get(0);
        String y = measures.get(measures.size() - 1);
        ChartType type = timeDimension != null ? ChartType.LINE : ChartType.BAR;
        return new ChartSpec(type, x, y, null);
    }

    private static boolean isNumeric(String typeName) {
        if (typeName == null) {
            return false;
        }
        String upper = typeName.toUpperCase(Locale.ROOT);
        return upper.contains("INT")
                || upper.contains("DECIMAL")
                || upper.contains("NUMERIC")
                || upper.contains("DOUBLE")
                || upper.contains("FLOAT")
                || upper.contains("REAL")
                || upper.contains("NUMBER")
                || upper.contains("COUNT");
    }

    private static boolean isTimeLike(ColumnMeta column) {
        String type = column.typeName() == null ? "" : column.typeName().toUpperCase(Locale.ROOT);
        if (type.contains("DATE") || type.contains("TIME")) {
            return true;
        }
        String name = column.name().toLowerCase(Locale.ROOT);
        return name.contains("date") || name.contains("time") || name.endsWith("_at");
    }
}
