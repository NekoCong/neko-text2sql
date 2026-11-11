package com.nekocong.text2sql.gbi;

/**
 * A chart specification inferred from a query result.
 *
 * @param type the chart type ({@link ChartType#NONE} means "render as a table")
 * @param xField the category/time column (dimension)
 * @param yField the numeric column (measure)
 * @param title an optional title
 */
public record ChartSpec(ChartType type, String xField, String yField, String title) {

    /**
     * A "no chart" spec.
     *
     * @return the spec
     */
    public static ChartSpec none() {
        return new ChartSpec(ChartType.NONE, null, null, null);
    }
}
