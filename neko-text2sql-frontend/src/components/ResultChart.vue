<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { init, use, type EChartsCoreOption, type EChartsType } from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TitleComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { ChartSpec, QueryResult } from '@/types'

use([
  BarChart,
  LineChart,
  PieChart,
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
  CanvasRenderer,
])

const props = defineProps<{
  rows: QueryResult
  chart: ChartSpec
}>()

const containerRef = ref<HTMLElement | null>(null)
let chartInstance: EChartsType | null = null
let observer: ResizeObserver | null = null

const ariaLabel = computed(() =>
  props.chart.title ? `图表：${props.chart.title}` : '查询结果图表',
)

/** A category/measure pair that survived coercion. */
interface ChartPoint {
  name: string
  value: number
}

/** Coerces a result cell to a finite number; NaN marks a non-chartable cell. */
function numericValue(value: unknown): number {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : Number.NaN
}

/** Extracts category/measure pairs, dropping rows whose measure is not finite. */
function chartPoints(rows: QueryResult, xField: string, yField: string): ChartPoint[] {
  const points: ChartPoint[] = []
  for (const row of rows.rows) {
    const value = numericValue(row[yField])
    if (!Number.isFinite(value)) {
      continue
    }
    const label = row[xField]
    points.push({ name: label === null || label === undefined ? '' : String(label), value })
  }
  return points
}

/** Reads an Element Plus design token, falling back when the variable is absent. */
function readToken(name: string, fallback: string): string {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return value || fallback
}

/**
 * Categorical palette documented in the Academic Clarity theme.
 *
 * Teal is the data/insight accent and is deliberately part of the series colors;
 * the fallbacks repeat the token values so a chart still renders before the
 * stylesheet is applied (or in a non-themed embed).
 */
function themePalette(): string[] {
  return [
    readToken('--ac-chart-1', '#1E5AA8'),
    readToken('--ac-chart-2', '#0F9D8A'),
    readToken('--ac-chart-3', '#D97706'),
    readToken('--ac-chart-4', '#7C6BC4'),
    readToken('--ac-chart-5', '#4B8BBE'),
    readToken('--ac-chart-6', '#94A3B8'),
  ]
}

/** Neutral axis and split-line styling so the chart sits inside the surface card. */
function axisStyle(): Record<string, unknown> {
  return {
    axisLabel: { color: readToken('--ac-muted', '#667085') },
    axisLine: { lineStyle: { color: readToken('--ac-border', '#E4E7EC') } },
    axisTick: { show: false },
  }
}

/** Builds the ECharts option for the spec: bar / line share axes, pie uses name-value pairs. */
function buildOption(rows: QueryResult, chart: ChartSpec): EChartsCoreOption {
  const xField = chart.xField ?? ''
  const yField = chart.yField ?? ''
  const points = chartPoints(rows, xField, yField)
  const option: EChartsCoreOption = {
    color: themePalette(),
    textStyle: { color: readToken('--ac-text', '#475467') },
    tooltip: { trigger: chart.type === 'PIE' ? 'item' : 'axis' },
  }

  if (chart.title) {
    option.title = {
      text: chart.title,
      left: 'center',
      textStyle: {
        color: readToken('--ac-ink', '#1F2A37'),
        fontSize: 14,
        fontWeight: 600,
      },
    }
  }

  if (chart.type === 'PIE') {
    option.legend = {
      bottom: 0,
      type: 'scroll',
      textStyle: { color: readToken('--ac-text', '#475467') },
    }
    option.series = [
      {
        type: 'pie',
        radius: ['38%', '66%'],
        center: ['50%', chart.title ? '53%' : '50%'],
        data: points.map((point) => ({ name: point.name, value: point.value })),
        label: { formatter: '{b}: {c}' },
        itemStyle: { borderColor: '#ffffff', borderWidth: 1 },
      },
    ]
  } else {
    option.grid = { left: 12, right: 16, top: chart.title ? 44 : 16, bottom: 8, containLabel: true }
    const axis = axisStyle()
    option.xAxis = {
      type: 'category',
      data: points.map((point) => point.name),
      ...axis,
      axisLabel: { ...(axis.axisLabel as Record<string, unknown>), hideOverlap: true },
    }
    option.yAxis = {
      type: 'value',
      ...axis,
      splitLine: { lineStyle: { color: readToken('--ac-border-soft', '#EEF0F3') } },
    }
    option.series =
      chart.type === 'LINE'
        ? [{ type: 'line', data: points.map((point) => point.value), smooth: true, symbolSize: 6 }]
        : [
            {
              type: 'bar',
              data: points.map((point) => point.value),
              barMaxWidth: 36,
              itemStyle: { borderRadius: [4, 4, 0, 0] },
            },
          ]
  }

  return option
}

/** Pushes the current props into the ECharts instance, replacing the previous option. */
function renderChart(): void {
  chartInstance?.setOption(buildOption(props.rows, props.chart), true)
}

onMounted(() => {
  const container = containerRef.value
  if (!container) {
    return
  }
  chartInstance = init(container)
  renderChart()
  observer = new ResizeObserver(() => {
    chartInstance?.resize()
  })
  observer.observe(container)
})

watch([() => props.rows, () => props.chart], () => {
  renderChart()
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  chartInstance?.dispose()
  chartInstance = null
})
</script>

<template>
  <div ref="containerRef" class="result-chart" role="img" :aria-label="ariaLabel" />
</template>

<style scoped>
.result-chart {
  width: 100%;
  height: 260px;
}
</style>
