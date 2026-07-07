import * as echarts from 'echarts/core';
import { BarChart, PieChart, ScatterChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TitleComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';

echarts.use([BarChart, PieChart, ScatterChart, GridComponent, LegendComponent, TitleComponent, TooltipComponent, CanvasRenderer]);

export interface MethodAverage {
  method: string;
  precision: number;
  recall: number;
  cases: number;
}

const METHOD_COLORS: Record<string, string> = {
  'AJ': '#0f4c5c', 'AJ-E': '#4d8a9c', 'SM': '#c1121f',
  'DQ-P': '#7b2d8b', 'DQ-R': '#b06ac9',
  'FJ-C': '#5fad56', 'FJ-FR': '#2e6b3e', 'FJ-O': '#e36414'
};

/**
 * Figure 5 (paper) — average precision (x) vs recall (y), one point per
 * method, over the Web benchmark cases that were run. Sized as a small square
 * panel (grid margins chosen so the plot area is 1:1 inside a 420x440 canvas)
 * to match the paper's layout.
 */
export function buildMethodAverageOption(averages: MethodAverage[]) {
  return {
    title: {
      text: 'Figure 5 — Avg Precision / Recall',
      left: 'center',
      top: 8,
      textStyle: { fontFamily: 'Space Grotesk', fontSize: 13 }
    },
    tooltip: {
      trigger: 'item',
      formatter: (p: any) => {
        const a = p.data.avg as MethodAverage;
        return `<b>${a.method}</b><br/>Precision: ${a.precision.toFixed(3)}<br/>` +
               `Recall: ${a.recall.toFixed(3)}<br/>${a.cases} case(s)`;
      }
    },
    // 420x440 canvas: 340x340 plot area — equal x/y scale like the paper.
    grid: { left: 55, right: 25, top: 55, bottom: 45 },
    xAxis: {
      type: 'value', name: 'Precision', min: 0, max: 1, interval: 0.2,
      nameLocation: 'center', nameGap: 28,
      nameTextStyle: { fontFamily: 'Space Grotesk', fontSize: 12 },
      axisLabel: { fontFamily: 'Space Grotesk', fontSize: 11 }
    },
    yAxis: {
      type: 'value', name: 'Recall', min: 0, max: 1, interval: 0.2,
      nameLocation: 'center', nameGap: 38,
      nameTextStyle: { fontFamily: 'Space Grotesk', fontSize: 12 },
      axisLabel: { fontFamily: 'Space Grotesk', fontSize: 11 }
    },
    series: [{
      type: 'scatter',
      symbolSize: 12,
      data: averages.map(a => ({
        value: [a.precision, a.recall],
        avg: a,
        itemStyle: { color: METHOD_COLORS[a.method] || '#666' }
      })),
      label: {
        show: true,
        position: 'top',
        formatter: (p: any) => p.data.avg.method,
        fontFamily: 'Space Grotesk',
        fontSize: 11,
        color: '#333'
      }
    }]
  };
}

export function buildPrecisionRecallOption(precision: number, recall: number) {
  return {
    title: {
      text: 'Precision vs Recall',
      left: 'center',
      top: 10,
      textStyle: { fontFamily: 'Space Grotesk', fontSize: 14 }
    },
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, bottom: 30, top: 60 },
    xAxis: {
      type: 'category',
      data: ['Precision', 'Recall'],
      axisLabel: { fontFamily: 'Space Grotesk' }
    },
    yAxis: { type: 'value', max: 1 },
    series: [
      {
        type: 'bar',
        data: [precision, recall],
        itemStyle: { color: '#0f4c5c', borderRadius: [6, 6, 0, 0] }
      }
    ]
  };
}

export function buildMatchBreakdownOption(tp: number, mismatches: number) {
  return {
    title: {
      text: 'Match Breakdown',
      left: 'center',
      top: 10,
      textStyle: { fontFamily: 'Space Grotesk', fontSize: 14 }
    },
    tooltip: { trigger: 'item' },
    legend: { bottom: 0 },
    series: [
      {
        name: 'Matches',
        type: 'pie',
        radius: ['40%', '70%'],
        label: { show: false },
        data: [
          { value: tp, name: 'True positives' },
          { value: mismatches, name: 'Mismatches' }
        ],
        itemStyle: {
          borderColor: '#fff',
          borderWidth: 2
        }
      }
    ]
  };
}
