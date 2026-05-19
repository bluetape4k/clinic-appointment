import {
  AfterViewInit,
  Component,
  ElementRef,
  Input,
  OnDestroy,
  ViewChild,
} from '@angular/core';
import { Chart, ChartData, registerables } from 'chart.js';
import { CancellationStatsResponse } from '../../../core/models';

Chart.register(...registerables);

/** Doughnut chart: cancellation vs no-show vs completed ratio. */
@Component({
  selector: 'app-cancellation-stats-chart',
  standalone: true,
  template: `
    <div class="chart-wrapper">
      <canvas #chartCanvas></canvas>
    </div>
  `,
  styles: [`
    .chart-wrapper { position: relative; height: 300px; }
  `],
})
export class CancellationStatsChartComponent implements AfterViewInit, OnDestroy {
  @ViewChild('chartCanvas') chartCanvas!: ElementRef<HTMLCanvasElement>;

  private _stats: CancellationStatsResponse | null = null;
  private chart?: Chart;

  @Input() set stats(value: CancellationStatsResponse | null) {
    this._stats = value;
    if (this.chart) this.updateChart();
  }

  ngAfterViewInit(): void {
    this.chart = new Chart(this.chartCanvas.nativeElement, {
      type: 'doughnut',
      data: this.buildData(),
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { position: 'right' } },
      },
    });
  }

  ngOnDestroy(): void {
    this.chart?.destroy();
  }

  private buildData(): ChartData<'doughnut'> {
    const s = this._stats;
    return {
      labels: ['완료', '취소', '미방문', '재배정'],
      datasets: [
        {
          data: [
            s?.totalCompleted ?? 0,
            s?.totalCancelled ?? 0,
            s?.totalNoShow ?? 0,
            s?.totalRescheduled ?? 0,
          ],
          backgroundColor: ['#388e3c', '#d32f2f', '#f57c00', '#7b1fa2'],
        },
      ],
    };
  }

  private updateChart(): void {
    if (!this.chart) return;
    this.chart.data = this.buildData();
    this.chart.update();
  }
}
