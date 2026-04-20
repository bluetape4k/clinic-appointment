import { Component, inject, OnInit } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ClinicService } from '../../../core/services/clinic.service';

@Component({
  selector: 'app-clinic-list',
  standalone: true,
  imports: [MatCardModule, MatIconModule, MatProgressSpinnerModule],
  template: `
    <div class="page-container">
      <h1 class="page-title">클리닉 정보</h1>

      @if (loading()) {
        <div class="loading-container">
          <mat-spinner diameter="48" />
        </div>
      } @else {
        <div class="clinic-grid">
          @for (clinic of clinics(); track clinic.id) {
            <mat-card class="clinic-card">
              <mat-card-header>
                <mat-icon mat-card-avatar>local_hospital</mat-icon>
                <mat-card-title>{{ clinic.name }}</mat-card-title>
                <mat-card-subtitle>클리닉 #{{ clinic.id }}</mat-card-subtitle>
              </mat-card-header>
              <mat-card-content>
                <ul class="info-list">
                  <li>
                    <mat-icon>schedule</mat-icon>
                    <span>슬롯 단위: {{ clinic.slotDurationMinutes }}분</span>
                  </li>
                  <li>
                    <mat-icon>language</mat-icon>
                    <span>시간대: {{ clinic.timezone }}</span>
                  </li>
                  <li>
                    <mat-icon>people</mat-icon>
                    <span>최대 동시 환자: {{ clinic.maxConcurrentPatients }}명</span>
                  </li>
                  <li>
                    <mat-icon>event_available</mat-icon>
                    <span>공휴일 운영: {{ clinic.openOnHolidays ? '예' : '아니오' }}</span>
                  </li>
                </ul>
              </mat-card-content>
            </mat-card>
          }
        </div>
      }
    </div>
  `,
  styles: [`
    .page-container {
      padding: 24px;
      max-width: 1200px;
      margin: 0 auto;
    }

    .page-title {
      font-size: 1.75rem;
      font-weight: 600;
      margin-bottom: 24px;
      color: #1a1a1a;
    }

    .loading-container {
      display: flex;
      justify-content: center;
      padding: 48px;
    }

    .clinic-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
      gap: 16px;
    }

    .clinic-card mat-card-content {
      padding: 16px;
    }

    .info-list {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 10px;
    }

    .info-list li {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 0.9rem;
      color: #333;
    }

    .info-list mat-icon {
      font-size: 18px;
      width: 18px;
      height: 18px;
      color: #1976d2;
    }
  `],
})
export class ClinicListComponent implements OnInit {
  private readonly clinicService = inject(ClinicService);

  readonly clinics = this.clinicService.clinics;
  readonly loading = this.clinicService.loading;

  ngOnInit(): void {
    this.clinicService.getAll();
  }
}
