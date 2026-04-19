import { Component, inject, signal } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';
import { FormsModule } from '@angular/forms';
import { RescheduleService } from '../../../core/services/reschedule.service';
import { RescheduleCandidate } from '../../../core/models';

@Component({
  selector: 'app-reschedule-list',
  standalone: true,
  imports: [
    MatCardModule, MatTableModule, MatButtonModule, MatIconModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule,
    MatProgressSpinnerModule, MatChipsModule, FormsModule,
  ],
  template: `
    <div class="page-container">
      <h1 class="page-title">예약 재배정 관리</h1>

      <!-- 검색 조건 -->
      <mat-card class="search-card">
        <mat-card-content>
          <div class="search-row">
            <mat-form-field appearance="outline">
              <mat-label>예약 ID</mat-label>
              <input matInput type="number" [(ngModel)]="appointmentId" placeholder="예약 번호 입력">
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>조회 유형</mat-label>
              <mat-select [(ngModel)]="searchType">
                <mat-option value="candidates">개별 재배정 후보</mat-option>
                <mat-option value="closure">휴진 일괄 재배정</mat-option>
              </mat-select>
            </mat-form-field>

            @if (searchType() === 'closure') {
              <mat-form-field appearance="outline">
                <mat-label>클리닉 ID</mat-label>
                <input matInput type="number" [(ngModel)]="clinicId">
              </mat-form-field>

              <mat-form-field appearance="outline">
                <mat-label>휴진 날짜</mat-label>
                <input matInput type="date" [(ngModel)]="closureDate">
              </mat-form-field>

              <mat-form-field appearance="outline">
                <mat-label>검색 범위 (일)</mat-label>
                <input matInput type="number" [(ngModel)]="searchDays">
              </mat-form-field>
            }

            <button mat-raised-button color="primary" (click)="search()" [disabled]="loading()">
              <mat-icon>search</mat-icon> 조회
            </button>
          </div>
        </mat-card-content>
      </mat-card>

      <!-- 로딩 -->
      @if (loading()) {
        <div class="loading-container">
          <mat-spinner diameter="40"></mat-spinner>
          @if (elapsedSeconds() > 0) {
            <p class="loading-message">최적화 진행 중... {{ elapsedSeconds() }}초</p>
          }
        </div>
      }

      <!-- 후보 목록 -->
      @if (candidates().length > 0) {
        <mat-card class="result-card">
          <mat-card-content>
            <h2 class="section-title">재배정 후보 ({{ candidates().length }}건)</h2>
            <table mat-table [dataSource]="candidates()" class="full-width">
              <ng-container matColumnDef="candidateDate">
                <th mat-header-cell *matHeaderCellDef>날짜</th>
                <td mat-cell *matCellDef="let c">{{ c.candidateDate }}</td>
              </ng-container>

              <ng-container matColumnDef="time">
                <th mat-header-cell *matHeaderCellDef>시간</th>
                <td mat-cell *matCellDef="let c">{{ c.startTime }} ~ {{ c.endTime }}</td>
              </ng-container>

              <ng-container matColumnDef="doctorId">
                <th mat-header-cell *matHeaderCellDef>의사 ID</th>
                <td mat-cell *matCellDef="let c">{{ c.doctorId }}</td>
              </ng-container>

              <ng-container matColumnDef="priority">
                <th mat-header-cell *matHeaderCellDef>우선순위</th>
                <td mat-cell *matCellDef="let c">
                  <mat-chip-set>
                    <mat-chip [highlighted]="c.priority <= 3">{{ c.priority }}</mat-chip>
                  </mat-chip-set>
                </td>
              </ng-container>

              <ng-container matColumnDef="actions">
                <th mat-header-cell *matHeaderCellDef>작업</th>
                <td mat-cell *matCellDef="let c">
                  <button mat-icon-button color="primary" (click)="confirm(c.id)" matTooltip="이 후보로 확정">
                    <mat-icon>check_circle</mat-icon>
                  </button>
                </td>
              </ng-container>

              <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
              <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
            </table>
          </mat-card-content>

          <mat-card-actions align="end">
            <button mat-raised-button color="accent" (click)="autoReschedule()" [disabled]="loading()">
              <mat-icon>auto_fix_high</mat-icon> 자동 재배정
            </button>
          </mat-card-actions>
        </mat-card>
      }

      <!-- 빈 결과 -->
      @if (!loading() && searched() && candidates().length === 0) {
        <mat-card class="empty-card">
          <mat-card-content>
            <mat-icon class="empty-icon">event_busy</mat-icon>
            <p>재배정 가능한 후보가 없습니다.</p>
          </mat-card-content>
        </mat-card>
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

    .section-title {
      font-size: 1.1rem;
      font-weight: 500;
      margin-bottom: 12px;
      color: #333;
    }

    .search-card {
      margin-bottom: 24px;
    }

    .search-row {
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
      align-items: center;
    }

    .search-row mat-form-field {
      flex: 0 0 auto;
      min-width: 140px;
    }

    .result-card {
      margin-bottom: 24px;
    }

    .full-width {
      width: 100%;
    }

    .loading-container {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 48px;
      gap: 12px;
    }

    .loading-message {
      color: #666;
      font-size: 0.9rem;
      margin: 0;
    }

    .empty-card mat-card-content {
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 48px;
      color: #999;
    }

    .empty-icon {
      font-size: 48px;
      width: 48px;
      height: 48px;
      margin-bottom: 12px;
    }

    th.mat-header-cell {
      font-weight: 600;
      color: #333;
    }
  `],
})
export class RescheduleListComponent {
  private readonly rescheduleService = inject(RescheduleService);
  private readonly snackBar = inject(MatSnackBar);

  readonly displayedColumns = ['candidateDate', 'time', 'doctorId', 'priority', 'actions'];

  readonly appointmentId = signal<number>(0);
  readonly searchType = signal<'candidates' | 'closure'>('candidates');
  readonly clinicId = signal<number>(1);
  readonly closureDate = signal<string>('');
  readonly searchDays = signal<number>(7);

  readonly loading = signal(false);
  readonly searched = signal(false);
  readonly candidates = signal<RescheduleCandidate[]>([]);
  readonly elapsedSeconds = signal(0);
  private elapsedTimer: ReturnType<typeof setInterval> | null = null;

  async search(): Promise<void> {
    const id = this.appointmentId();
    if (!id) {
      this.snackBar.open('예약 ID를 입력하세요.', '확인', { duration: 3000 });
      return;
    }

    this.loading.set(true);
    this.searched.set(true);

    try {
      if (this.searchType() === 'candidates') {
        const result = await this.rescheduleService.getCandidates(id);
        this.candidates.set(result);
      } else {
        const result = await this.rescheduleService.getClosureCandidates(
          id, this.clinicId(), this.closureDate(), this.searchDays()
        );
        const flat = Array.from(result.values()).flat();
        this.candidates.set(flat);
      }
    } catch (e) {
      this.snackBar.open('재배정 후보 조회에 실패했습니다.', '확인', { duration: 3000 });
    } finally {
      this.loading.set(false);
    }
  }

  async confirm(candidateId: number): Promise<void> {
    try {
      const newId = await this.rescheduleService.confirm(this.appointmentId(), candidateId);
      this.snackBar.open(`재배정 완료 — 새 예약 ID: ${newId}`, '확인', { duration: 5000 });
      this.candidates.set([]);
      this.searched.set(false);
    } catch (e) {
      this.snackBar.open('재배정 확정에 실패했습니다.', '확인', { duration: 3000 });
    }
  }

  async autoReschedule(): Promise<void> {
    this.loading.set(true);
    this.startElapsedTimer();
    try {
      const newId = await this.rescheduleService.autoReschedule(this.appointmentId());
      if (newId) {
        this.snackBar.open(`자동 재배정 완료 — 새 예약 ID: ${newId}`, '확인', { duration: 5000 });
        this.candidates.set([]);
        this.searched.set(false);
      } else {
        this.snackBar.open('자동 재배정 가능한 후보가 없습니다.', '확인', { duration: 3000 });
      }
    } catch (e) {
      this.snackBar.open('자동 재배정에 실패했습니다.', '확인', { duration: 3000 });
    } finally {
      this.stopElapsedTimer();
      this.loading.set(false);
    }
  }

  private startElapsedTimer(): void {
    this.elapsedSeconds.set(0);
    this.elapsedTimer = setInterval(() => {
      this.elapsedSeconds.update(v => v + 1);
    }, 1000);
  }

  private stopElapsedTimer(): void {
    if (this.elapsedTimer) {
      clearInterval(this.elapsedTimer);
      this.elapsedTimer = null;
    }
  }
}
