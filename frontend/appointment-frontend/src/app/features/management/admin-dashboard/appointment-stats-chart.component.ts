import {
  AfterViewInit,
  Component,
  ElementRef,
  Input,
  OnDestroy,
  ViewChild,
} from '@angular/core';
import { Chart, ChartData, registerables } from 'chart.js';
import { AppointmentStatsResponse } from '../../../core/models';

Chart.register(...registerables);

/** Bar chart: daily appointment counts grouped by status. */
@Component({
  selector: 'app-appointment-stats-chart',
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
export class AppointmentStatsChartComponent implements AfterViewInit, OnDestroy {
  @ViewChild('chartCanvas') chartCanvas!: ElementRef<HTMLCanvasElement>;

  private _stats: AppointmentStatsResponse | null = null;
  private chart?: Chart;

  @Input() set stats(value: AppointmentStatsResponse | null) {
    this._stats = value;
    if (this.chart) this.updateChart();
  }

  ngAfterViewInit(): void {
    this.chart = new Chart(this.chartCanvas.nativeElement, {
      type: 'bar',
      data: this.buildData(),
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { position: 'top' } },
        scales: { x: { stacked: true }, y: { stacked: true, beginAtZero: true } },
      },
    });
  }

  ngOnDestroy(): void {
    this.chart?.destroy();
  }

  private buildData(): ChartData<'bar'> {
    const daily = this._stats?.daily ?? [];
    const labels = daily.map(b => b.date);
    const statuses = [...new Set(daily.flatMap(b => Object.keys(b.countsByStatus)))];
    const STATUS_COLORS: Record<string, string> = {
      CONFIRMED: '#1976d2',
      COMPLETED: '#388e3c',
      CANCELLED: '#d32f2f',
      NO_SHOW: '#f57c00',
      RESCHEDULED: '#7b1fa2',
      REQUESTED: '#0288d1',
    };
    return {
      labels,
      datasets: statuses.map(status => ({
        label: status,
        data: daily.map(b => b.countsByStatus[status] ?? 0),
        backgroundColor: STATUS_COLORS[status] ?? '#90a4ae',
      })),
    };
  }

  private updateChart(): void {
    if (!this.chart) return;
    const data = this.buildData();
    this.chart.data = data;
    this.chart.update();
  }
}
