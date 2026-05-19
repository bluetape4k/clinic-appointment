import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { DashboardStatsService } from '../../../core/services';
import {
  AppointmentStatsResponse,
  CancellationStatsResponse,
  DoctorStatsResponse,
} from '../../../core/models';
import { AppointmentStatsChartComponent } from './appointment-stats-chart.component';
import { DoctorStatsChartComponent } from './doctor-stats-chart.component';
import { CancellationStatsChartComponent } from './cancellation-stats-chart.component';

function toIsoDate(date: Date): string {
  return date.toISOString().slice(0, 10);
}

/** Admin dashboard: loads and displays 3 stats charts for a clinic date range. */
@Component({
  selector: 'app-admin-dashboard',
  standalone: true,
  imports: [
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
    AppointmentStatsChartComponent,
    DoctorStatsChartComponent,
    CancellationStatsChartComponent,
  ],
  template: `
    <div class="dashboard-container">
      <h1 class="page-title">어드민 대시보드</h1>

      <!-- Filter bar -->
      <mat-card class="filter-card">
        <mat-card-content class="filter-row">
          <mat-form-field appearance="outline">
            <mat-label>클리닉 ID</mat-label>
            <input matInput type="number" [(ngModel)]="clinicId" min="1" />
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>시작일</mat-label>
            <input matInput type="date" [(ngModel)]="from" />
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>종료일</mat-label>
            <input matInput type="date" [(ngModel)]="to" />
          </mat-form-field>

          <button mat-raised-button color="primary" (click)="load()" [disabled]="loading()">
            조회
          </button>
        </mat-card-content>
      </mat-card>

      @if (loading()) {
        <div class="spinner-row">
          <mat-spinner diameter="48" />
        </div>
      }

      @if (error()) {
        <mat-card class="error-card">
          <mat-card-content>{{ error() }}</mat-card-content>
        </mat-card>
      }

      <!-- Appointment stats chart -->
      <mat-card class="chart-card">
        <mat-card-header>
          <mat-card-title>일별 예약 현황</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <app-appointment-stats-chart [stats]="appointmentStats()" />
        </mat-card-content>
      </mat-card>

      <!-- Doctor stats chart -->
      <mat-card class="chart-card">
        <mat-card-header>
          <mat-card-title>의사별 예약 현황</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <app-doctor-stats-chart [stats]="doctorStats()" />
        </mat-card-content>
      </mat-card>

      <!-- Cancellation stats chart -->
      <mat-card class="chart-card">
        <mat-card-header>
          <mat-card-title>취소·미방문 현황</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <app-cancellation-stats-chart [stats]="cancellationStats()" />
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: [`
    .dashboard-container {
      padding: 24px;
      max-width: 1200px;
      margin: 0 auto;
      display: flex;
      flex-direction: column;
      gap: 24px;
    }

    .page-title {
      font-size: 1.75rem;
      font-weight: 600;
      margin: 0;
      color: #1a1a1a;
    }

    .filter-card mat-card-content.filter-row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 16px;
      padding: 8px 0;
    }

    .filter-row mat-form-field {
      flex: 1 1 160px;
    }

    .spinner-row {
      display: flex;
      justify-content: center;
      padding: 32px 0;
    }

    .error-card {
      background: #ffebee;
      color: #c62828;
    }

    .chart-card mat-card-content {
      padding-top: 16px;
    }
  `],
})
export class AdminDashboardComponent implements OnInit {
  private readonly statsService = inject(DashboardStatsService);

  clinicId = 1;
  from = toIsoDate(new Date(Date.now() - 29 * 24 * 60 * 60 * 1000));
  to = toIsoDate(new Date());

  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly appointmentStats = signal<AppointmentStatsResponse | null>(null);
  readonly doctorStats = signal<DoctorStatsResponse | null>(null);
  readonly cancellationStats = signal<CancellationStatsResponse | null>(null);

  ngOnInit(): void {
    this.load();
  }

  async load(): Promise<void> {
    if (this.clinicId < 1) {
      this.error.set('클리닉 ID는 1 이상이어야 합니다.');
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    try {
      const [appt, doctor, cancel] = await Promise.all([
        this.statsService.getAppointmentStats(this.clinicId, this.from, this.to),
        this.statsService.getDoctorStats(this.clinicId, this.from, this.to),
        this.statsService.getCancellationStats(this.clinicId, this.from, this.to),
      ]);
      this.appointmentStats.set(appt);
      this.doctorStats.set(doctor);
      this.cancellationStats.set(cancel);
    } catch {
      this.error.set('통계 데이터를 불러오는 중 오류가 발생했습니다.');
    } finally {
      this.loading.set(false);
    }
  }
}
