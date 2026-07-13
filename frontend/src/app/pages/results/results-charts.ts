import * as echarts from 'echarts/core';
import { BarChart, PieChart, ScatterChart } from 'echarts/charts';
import { GraphicComponent, GridComponent, LegendComponent, TitleComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';

// GraphicComponent renders the paper-style method-label boxes (option.graphic)
// — without it registered, echarts silently drops them.
echarts.use([BarChart, PieChart, ScatterChart, GraphicComponent, GridComponent, LegendComponent, TitleComponent, TooltipComponent, CanvasRenderer]);

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
export function buildMethodAverageOption(averages: MethodAverage[],
                                         title = 'Figure 5 — Avg Precision / Recall') {
  return {
    title: {
      text: title,
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
    // 480x440 canvas: 340x340 plot area — equal x/y scale like the paper;
    // the wide right margin gives shifted labels room at precision = 1.0.
    grid: { left: 55, right: 85, top: 55, bottom: 45 },
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
      label: { show: false }
    }],
    // Paper-style annotations: boxed method names placed around the points
    // with leader lines, positions computed to never collide (Figure 5 look).
    graphic: { elements: placeMethodLabels(averages) }
  };
}

/**
 * Greedy non-colliding label placement on the fixed 480x440 canvas
 * (plot area: x 55..395, y 55..395 — must match the grid above).
 * Each label is a white rounded box with a thin leader line to its point,
 * like the annotation boxes in the paper's Figure 5.
 */
function placeMethodLabels(averages: MethodAverage[]): any[] {
  const X0 = 55, Y0 = 55, SPAN = 340, W = 480, H = 440;
  const px = (p: number) => X0 + p * SPAN;
  const py = (r: number) => Y0 + (1 - r) * SPAN;

  interface Box { x: number; y: number; w: number; h: number; }
  const overlaps = (a: Box, b: Box, m = 3) =>
    a.x < b.x + b.w + m && b.x < a.x + a.w + m &&
    a.y < b.y + b.h + m && b.y < a.y + a.h + m;

  // Points themselves count as obstacles so no box covers a dot.
  const obstacles: Box[] = averages.map(a =>
    ({ x: px(a.precision) - 8, y: py(a.recall) - 8, w: 16, h: 16 }));

  const elements: any[] = [];
  // Deterministic order: leftmost points place first.
  const ordered = [...averages].sort((a, b) => a.precision - b.precision || a.recall - b.recall);
  for (const a of ordered) {
    const cx = px(a.precision), cy = py(a.recall);
    const w = a.method.length * 6.8 + 10, h = 17;
    // Candidate box centers around the point, near ring then far ring.
    const cands: [number, number][] = [];
    for (const d of [14, 30, 48]) {
      cands.push([cx + w / 2 + d, cy], [cx, cy - h / 2 - d], [cx - w / 2 - d, cy],
                 [cx, cy + h / 2 + d], [cx + w / 2 + d - 4, cy - h / 2 - d + 4],
                 [cx - w / 2 - d + 4, cy - h / 2 - d + 4],
                 [cx + w / 2 + d - 4, cy + h / 2 + d - 4],
                 [cx - w / 2 - d + 4, cy + h / 2 + d - 4]);
    }
    let box: Box | null = null;
    for (const [bx, by] of cands) {
      const cand: Box = { x: bx - w / 2, y: by - h / 2, w, h };
      if (cand.x < 2 || cand.y < 2 || cand.x + w > W - 2 || cand.y + h > H - 2) continue;
      if (obstacles.some(o => overlaps(cand, o))) continue;
      box = cand;
      break;
    }
    if (!box) box = { x: Math.min(cx + 10, W - w - 2), y: Math.max(cy - h - 10, 2), w, h };
    obstacles.push(box);

    // Leader line from the dot to the box edge (clip the segment to the box).
    const bx = box.x + w / 2, by = box.y + h / 2;
    const dx = bx - cx, dy = by - cy;
    const tx = dx !== 0 ? (w / 2 + 2) / Math.abs(dx) : Infinity;
    const ty = dy !== 0 ? (h / 2 + 2) / Math.abs(dy) : Infinity;
    const t = Math.max(0, 1 - Math.min(tx, ty));
    if (Math.hypot(dx * t, dy * t) > 10) {
      elements.push({
        type: 'line', silent: true, z: 4,
        shape: { x1: cx, y1: cy, x2: cx + dx * t, y2: cy + dy * t },
        style: { stroke: '#aaaaaa', lineWidth: 1 }
      });
    }
    elements.push({
      type: 'rect', silent: true, z: 5,
      shape: { x: box.x, y: box.y, width: w, height: h, r: 3 },
      style: { fill: '#ffffff', stroke: '#999999', lineWidth: 1 }
    });
    elements.push({
      type: 'text', silent: true, z: 6,
      style: {
        text: a.method, x: bx, y: by,
        textAlign: 'center', textVerticalAlign: 'middle',
        fill: '#333333', font: '11px "Space Grotesk", sans-serif'
      }
    });
  }
  return elements;
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
