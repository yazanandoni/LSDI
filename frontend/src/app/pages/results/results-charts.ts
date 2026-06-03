import * as echarts from 'echarts/core';
import { BarChart, PieChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TitleComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';

echarts.use([BarChart, PieChart, GridComponent, LegendComponent, TitleComponent, TooltipComponent, CanvasRenderer]);

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
