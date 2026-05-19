import {
  AfterViewInit,
  Component,
  ElementRef,
  Input,
  OnDestroy,
  ViewChild,
} from '@angular/core';
import { Chart, ChartData, registerables } from 'chart.js';
import { DoctorStatsResponse } from '../../../core/models';

Chart.register(...registerables);

/** Horizontal bar chart: top-N doctors by total appointments. */
@Component({
  selector: 'app-doctor-stats-chart',
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
export class DoctorStatsChartComponent implements AfterViewInit, OnDestroy {
  @ViewChild('chartCanvas') chartCanvas!: ElementRef<HTMLCanvasElement>;

  private _stats: DoctorStatsResponse | null = null;
  private chart?: Chart;

  @Input() set stats(value: DoctorStatsResponse | null) {
    this._stats = value;
    if (this.chart) this.updateChart();
  }

  ngAfterViewInit(): void {
    this.chart = new Chart(this.chartCanvas.nativeElement, {
      type: 'bar',
      data: this.buildData(),
      options: {
        indexAxis: 'y',
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { position: 'top' } },
        scales: { x: { beginAtZero: true } },
      },
    });
  }

  ngOnDestroy(): void {
    this.chart?.destroy();
  }

  private buildData(): ChartData<'bar'> {
    const doctors = this._stats?.doctors ?? [];
    return {
      labels: doctors.map(d => `의사 #${d.doctorId}`),
      datasets: [
        {
          label: '완료',
          data: doctors.map(d => d.completed),
          backgroundColor: '#388e3c',
        },
        {
          label: '취소',
          data: doctors.map(d => d.cancelled),
          backgroundColor: '#d32f2f',
        },
        {
          label: '미방문',
          data: doctors.map(d => d.noShow),
          backgroundColor: '#f57c00',
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
