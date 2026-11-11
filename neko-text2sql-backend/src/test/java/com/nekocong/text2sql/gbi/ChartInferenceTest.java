package com.nekocong.text2sql.gbi;

import static org.assertj.core.api.Assertions.assertThat;

import com.nekocong.text2sql.sql.model.ColumnMeta;
import com.nekocong.text2sql.sql.model.QueryResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for Generative-BI chart inference. Pure, no Spring context. */
class ChartInferenceTest {

    private final ChartInference inference = new ChartInference();

    private static QueryResult result(List<ColumnMeta> columns, int rowCount) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            rows.add(new LinkedHashMap<>());
        }
        return new QueryResult(columns, rows, rowCount, false, 1);
    }

    @Test
    void barForDimensionAndMeasure() {
        ChartSpec spec =
                inference.infer(
                        result(
                                List.of(
                                        new ColumnMeta("city", "VARCHAR"),
                                        new ColumnMeta("order_count", "BIGINT")),
                                3));
        assertThat(spec.type()).isEqualTo(ChartType.BAR);
        assertThat(spec.xField()).isEqualTo("city");
        assertThat(spec.yField()).isEqualTo("order_count");
    }

    @Test
    void lineForTimeDimension() {
        ChartSpec spec =
                inference.infer(
                        result(
                                List.of(
                                        new ColumnMeta("created_at", "TIMESTAMP"),
                                        new ColumnMeta("total", "DECIMAL")),
                                5));
        assertThat(spec.type()).isEqualTo(ChartType.LINE);
        assertThat(spec.xField()).isEqualTo("created_at");
        assertThat(spec.yField()).isEqualTo("total");
    }

    @Test
    void noneWhenSingleColumn() {
        ChartSpec spec =
                inference.infer(result(List.of(new ColumnMeta("city", "VARCHAR")), 3));
        assertThat(spec.type()).isEqualTo(ChartType.NONE);
    }

    @Test
    void noneWhenNoNumericMeasure() {
        ChartSpec spec =
                inference.infer(
                        result(
                                List.of(
                                        new ColumnMeta("city", "VARCHAR"),
                                        new ColumnMeta("country", "VARCHAR")),
                                3));
        assertThat(spec.type()).isEqualTo(ChartType.NONE);
    }

    @Test
    void noneWhenEmptyResult() {
        ChartSpec spec =
                inference.infer(
                        result(
                                List.of(
                                        new ColumnMeta("city", "VARCHAR"),
                                        new ColumnMeta("order_count", "BIGINT")),
                                0));
        assertThat(spec.type()).isEqualTo(ChartType.NONE);
    }
}
