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
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatTooltipModule } from '@angular/material/tooltip';
import { FormsModule } from '@angular/forms';
import { EquipmentUnavailabilityService } from '../../../core/services/equipment-unavailability.service';
import {
  EquipmentUnavailabilityRecord,
  CreateEquipmentUnavailabilityRequest,
  UnavailabilityConflictResponse,
} from '../../../core/models';

@Component({
  selector: 'app-equipment-unavailability-list',
  standalone: true,
  imports: [
    MatCardModule, MatTableModule, MatButtonModule, MatIconModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule,
    MatProgressSpinnerModule, MatChipsModule, MatDialogModule,
    MatCheckboxModule, MatTooltipModule, FormsModule,
  ],
  template: `
    <div class="page-container">
      <h1 class="page-title">장비 사용불가 스케줄 관리</h1>

      <!-- 조회 조건 -->
      <mat-card class="search-card">
        <mat-card-content>
          <div class="search-row">
            <mat-form-field appearance="outline">
              <mat-label>클리닉 ID</mat-label>
              <input matInput type="number" [(ngModel)]="clinicId">
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>장비 ID</mat-label>
              <input matInput type="number" [(ngModel)]="equipmentId">
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>시작일</mat-label>
              <input matInput type="date" [(ngModel)]="fromDate">
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>종료일</mat-label>
              <input matInput type="date" [(ngModel)]="toDate">
            </mat-form-field>

            <button mat-raised-button color="primary" (click)="loadList()" [disabled]="loading()">
              <mat-icon>search</mat-icon> 조회
            </button>
          </div>
        </mat-card-content>
      </mat-card>

      <!-- 로딩 -->
      @if (loading()) {
        <div class="loading-container">
          <mat-spinner diameter="40"></mat-spinner>
        </div>
      }

      <!-- 등록 폼 -->
      <mat-card class="form-card">
        <mat-card-header>
          <mat-card-title>{{ editingId() ? '스케줄 수정' : '신규 등록' }}</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <div class="form-grid">
            <mat-checkbox [(ngModel)]="formRecurring">반복 스케줄</mat-checkbox>

            @if (!formRecurring()) {
              <mat-form-field appearance="outline">
                <mat-label>사용불가 날짜</mat-label>
                <input matInput type="date" [(ngModel)]="formDate">
              </mat-form-field>
            }

            @if (formRecurring()) {
              <mat-form-field appearance="outline">
                <mat-label>반복 요일</mat-label>
                <mat-select [(ngModel)]="formDayOfWeek">
                  <mat-option value="MONDAY">월요일</mat-option>
                  <mat-option value="TUESDAY">화요일</mat-option>
                  <mat-option value="WEDNESDAY">수요일</mat-option>
                  <mat-option value="THURSDAY">목요일</mat-option>
                  <mat-option value="FRIDAY">금요일</mat-option>
                  <mat-option value="SATURDAY">토요일</mat-option>
                  <mat-option value="SUNDAY">일요일</mat-option>
                </mat-select>
              </mat-form-field>

              <mat-form-field appearance="outline">
                <mat-label>유효 시작일</mat-label>
                <input matInput type="date" [(ngModel)]="formEffectiveFrom">
              </mat-form-field>

              <mat-form-field appearance="outline">
                <mat-label>유효 종료일</mat-label>
                <input matInput type="date" [(ngModel)]="formEffectiveUntil">
              </mat-form-field>
            }

            <mat-form-field appearance="outline">
              <mat-label>시작 시간</mat-label>
              <input matInput type="time" [(ngModel)]="formStartTime">
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>종료 시간</mat-label>
              <input matInput type="time" [(ngModel)]="formEndTime">
            </mat-form-field>

            <mat-form-field appearance="outline" class="full-span">
              <mat-label>사유</mat-label>
              <input matInput [(ngModel)]="formReason">
            </mat-form-field>
          </div>
        </mat-card-content>
        <mat-card-actions align="end">
          @if (editingId()) {
            <button mat-button (click)="cancelEdit()">취소</button>
          }
          <button mat-raised-button color="accent" (click)="previewConflicts()" [disabled]="loading()">
            <mat-icon>preview</mat-icon> 충돌 미리보기
          </button>
          <button mat-raised-button color="primary" (click)="save()" [disabled]="loading()">
            <mat-icon>save</mat-icon> {{ editingId() ? '수정' : '등록' }}
          </button>
        </mat-card-actions>
      </mat-card>

      <!-- 충돌 미리보기 결과 -->
      @if (conflictResult()) {
        <mat-card class="conflict-card">
          <mat-card-content>
            <h2 class="section-title">
              충돌 예약 ({{ conflictResult()!.conflictCount }}건)
            </h2>
            @if (conflictResult()!.conflicts.length > 0) {
              <table mat-table [dataSource]="conflictResult()!.conflicts" class="full-width">
                <ng-container matColumnDef="appointmentId">
                  <th mat-header-cell *matHeaderCellDef>예약 ID</th>
                  <td mat-cell *matCellDef="let c">{{ c.appointmentId }}</td>
                </ng-container>
                <ng-container matColumnDef="patientName">
                  <th mat-header-cell *matHeaderCellDef>환자명</th>
                  <td mat-cell *matCellDef="let c">{{ c.patientName }}</td>
                </ng-container>
                <ng-container matColumnDef="appointmentDate">
                  <th mat-header-cell *matHeaderCellDef>날짜</th>
                  <td mat-cell *matCellDef="let c">{{ c.appointmentDate }}</td>
                </ng-container>
                <ng-container matColumnDef="time">
                  <th mat-header-cell *matHeaderCellDef>시간</th>
                  <td mat-cell *matCellDef="let c">{{ c.startTime }} ~ {{ c.endTime }}</td>
                </ng-container>
                <tr mat-header-row *matHeaderRowDef="conflictColumns"></tr>
                <tr mat-row *matRowDef="let row; columns: conflictColumns;"></tr>
              </table>
            } @else {
              <p class="no-conflict">충돌 예약이 없습니다.</p>
            }
          </mat-card-content>
        </mat-card>
      }

      <!-- 스케줄 목록 -->
      @if (records().length > 0) {
        <mat-card class="result-card">
          <mat-card-content>
            <h2 class="section-title">사용불가 스케줄 ({{ records().length }}건)</h2>
            <table mat-table [dataSource]="records()" class="full-width">
              <ng-container matColumnDef="id">
                <th mat-header-cell *matHeaderCellDef>ID</th>
                <td mat-cell *matCellDef="let r">{{ r.id }}</td>
              </ng-container>

              <ng-container matColumnDef="type">
                <th mat-header-cell *matHeaderCellDef>유형</th>
                <td mat-cell *matCellDef="let r">
                  <mat-chip-set>
                    <mat-chip [highlighted]="r.isRecurring">
                      {{ r.isRecurring ? '반복' : '단건' }}
                    </mat-chip>
                  </mat-chip-set>
                </td>
              </ng-container>

              <ng-container matColumnDef="schedule">
                <th mat-header-cell *matHeaderCellDef>스케줄</th>
                <td mat-cell *matCellDef="let r">
                  @if (r.isRecurring) {
                    {{ r.recurringDayOfWeek }} ({{ r.effectiveFrom }} ~ {{ r.effectiveUntil ?? '무기한' }})
                  } @else {
                    {{ r.unavailableDate }}
                  }
                </td>
              </ng-container>

              <ng-container matColumnDef="time">
                <th mat-header-cell *matHeaderCellDef>시간</th>
                <td mat-cell *matCellDef="let r">{{ r.startTime }} ~ {{ r.endTime }}</td>
              </ng-container>

              <ng-container matColumnDef="reason">
                <th mat-header-cell *matHeaderCellDef>사유</th>
                <td mat-cell *matCellDef="let r">{{ r.reason ?? '-' }}</td>
              </ng-container>

              <ng-container matColumnDef="actions">
                <th mat-header-cell *matHeaderCellDef>작업</th>
                <td mat-cell *matCellDef="let r">
                  <button mat-icon-button color="primary" (click)="startEdit(r)" matTooltip="수정">
                    <mat-icon>edit</mat-icon>
                  </button>
                  <button mat-icon-button color="warn" (click)="deleteRecord(r.id)" matTooltip="삭제">
                    <mat-icon>delete</mat-icon>
                  </button>
                  <button mat-icon-button (click)="detectConflicts(r.id)" matTooltip="충돌 조회">
                    <mat-icon>warning</mat-icon>
                  </button>
                  <button mat-icon-button (click)="openExceptionForm(r.id)" matTooltip="예외 추가">
                    <mat-icon>event_busy</mat-icon>
                  </button>
                </td>
              </ng-container>

              <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
              <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
            </table>
          </mat-card-content>
        </mat-card>
      }

      <!-- 예외 등록 폼 -->
      @if (showExceptionForm()) {
        <mat-card class="form-card">
          <mat-card-header>
            <mat-card-title>예외 날짜 등록 (스케줄 #{{ exceptionTargetId() }})</mat-card-title>
          </mat-card-header>
          <mat-card-content>
            <div class="form-grid">
              <mat-form-field appearance="outline">
                <mat-label>원래 날짜</mat-label>
                <input matInput type="date" [(ngModel)]="exOriginalDate">
              </mat-form-field>

              <mat-form-field appearance="outline">
                <mat-label>예외 유형</mat-label>
                <mat-select [(ngModel)]="exType">
                  <mat-option value="SKIP">건너뛰기</mat-option>
                  <mat-option value="RESCHEDULE">일정 변경</mat-option>
                </mat-select>
              </mat-form-field>

              @if (exType() === 'RESCHEDULE') {
                <mat-form-field appearance="outline">
                  <mat-label>변경 날짜</mat-label>
                  <input matInput type="date" [(ngModel)]="exRescheduledDate">
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>변경 시작 시간</mat-label>
                  <input matInput type="time" [(ngModel)]="exStartTime">
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>변경 종료 시간</mat-label>
                  <input matInput type="time" [(ngModel)]="exEndTime">
                </mat-form-field>
              }

              <mat-form-field appearance="outline" class="full-span">
                <mat-label>사유</mat-label>
                <input matInput [(ngModel)]="exReason">
              </mat-form-field>
            </div>
          </mat-card-content>
          <mat-card-actions align="end">
            <button mat-button (click)="closeExceptionForm()">취소</button>
            <button mat-raised-button color="primary" (click)="addException()" [disabled]="loading()">
              <mat-icon>add</mat-icon> 예외 등록
            </button>
          </mat-card-actions>
        </mat-card>
      }

      <!-- 빈 결과 -->
      @if (!loading() && searched() && records().length === 0) {
        <mat-card class="empty-card">
          <mat-card-content>
            <mat-icon class="empty-icon">build_circle</mat-icon>
            <p>해당 기간에 등록된 사용불가 스케줄이 없습니다.</p>
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

    .search-card, .form-card, .result-card, .conflict-card {
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

    .form-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
      gap: 12px;
      align-items: center;
    }

    .full-span {
      grid-column: 1 / -1;
    }

    .full-width {
      width: 100%;
    }

    .loading-container {
      display: flex;
      justify-content: center;
      padding: 48px;
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

    .no-conflict {
      text-align: center;
      color: #4caf50;
      padding: 16px;
    }

    .conflict-card {
      border-left: 4px solid #ff9800;
    }

    th.mat-header-cell {
      font-weight: 600;
      color: #333;
    }
  `],
})
export class EquipmentUnavailabilityListComponent {
  private readonly service = inject(EquipmentUnavailabilityService);
  private readonly snackBar = inject(MatSnackBar);

  readonly displayedColumns = ['id', 'type', 'schedule', 'time', 'reason', 'actions'];
  readonly conflictColumns = ['appointmentId', 'patientName', 'appointmentDate', 'time'];

  // Search
  readonly clinicId = signal<number>(1);
  readonly equipmentId = signal<number>(1);
  readonly fromDate = signal<string>('');
  readonly toDate = signal<string>('');

  // State
  readonly loading = signal(false);
  readonly searched = signal(false);
  readonly records = signal<EquipmentUnavailabilityRecord[]>([]);
  readonly conflictResult = signal<UnavailabilityConflictResponse | null>(null);

  // Form
  readonly editingId = signal<number | null>(null);
  readonly formRecurring = signal(false);
  readonly formDate = signal<string>('');
  readonly formDayOfWeek = signal<string>('MONDAY');
  readonly formEffectiveFrom = signal<string>('');
  readonly formEffectiveUntil = signal<string>('');
  readonly formStartTime = signal<string>('09:00');
  readonly formEndTime = signal<string>('10:00');
  readonly formReason = signal<string>('');

  // Exception form
  readonly showExceptionForm = signal(false);
  readonly exceptionTargetId = signal<number>(0);
  readonly exOriginalDate = signal<string>('');
  readonly exType = signal<'SKIP' | 'RESCHEDULE'>('SKIP');
  readonly exRescheduledDate = signal<string>('');
  readonly exStartTime = signal<string>('');
  readonly exEndTime = signal<string>('');
  readonly exReason = signal<string>('');

  async loadList(): Promise<void> {
    if (!this.fromDate() || !this.toDate()) {
      this.snackBar.open('조회 기간을 입력하세요.', '확인', { duration: 3000 });
      return;
    }
    this.loading.set(true);
    this.searched.set(true);
    try {
      const result = await this.service.getList(
        this.clinicId(), this.equipmentId(), this.fromDate(), this.toDate()
      );
      this.records.set(result);
    } catch {
      this.snackBar.open('스케줄 조회에 실패했습니다.', '확인', { duration: 3000 });
    } finally {
      this.loading.set(false);
    }
  }

  async save(): Promise<void> {
    const request = this.buildRequest();
    this.loading.set(true);
    try {
      if (this.editingId()) {
        await this.service.update(this.clinicId(), this.equipmentId(), this.editingId()!, request);
        this.snackBar.open('스케줄이 수정되었습니다.', '확인', { duration: 3000 });
      } else {
        await this.service.create(this.clinicId(), this.equipmentId(), request);
        this.snackBar.open('스케줄이 등록되었습니다.', '확인', { duration: 3000 });
      }
      this.cancelEdit();
      if (this.searched()) await this.loadList();
    } catch {
      this.snackBar.open('저장에 실패했습니다.', '확인', { duration: 3000 });
    } finally {
      this.loading.set(false);
    }
  }

  async deleteRecord(id: number): Promise<void> {
    this.loading.set(true);
    try {
      await this.service.delete(this.clinicId(), this.equipmentId(), id);
      this.snackBar.open('스케줄이 삭제되었습니다.', '확인', { duration: 3000 });
      if (this.searched()) await this.loadList();
    } catch {
      this.snackBar.open('삭제에 실패했습니다.', '확인', { duration: 3000 });
    } finally {
      this.loading.set(false);
    }
  }

  async previewConflicts(): Promise<void> {
    this.loading.set(true);
    try {
      const result = await this.service.previewConflicts(
        this.clinicId(), this.equipmentId(), this.buildRequest()
      );
      this.conflictResult.set(result);
    } catch {
      this.snackBar.open('충돌 미리보기에 실패했습니다.', '확인', { duration: 3000 });
    } finally {
      this.loading.set(false);
    }
  }

  async detectConflicts(id: number): Promise<void> {
    this.loading.set(true);
    try {
      const result = await this.service.detectConflicts(this.clinicId(), this.equipmentId(), id);
      this.conflictResult.set(result);
    } catch {
      this.snackBar.open('충돌 조회에 실패했습니다.', '확인', { duration: 3000 });
    } finally {
      this.loading.set(false);
    }
  }

  startEdit(record: EquipmentUnavailabilityRecord): void {
    this.editingId.set(record.id);
    this.formRecurring.set(record.isRecurring);
    this.formDate.set(record.unavailableDate ?? '');
    this.formDayOfWeek.set(record.recurringDayOfWeek ?? 'MONDAY');
    this.formEffectiveFrom.set(record.effectiveFrom);
    this.formEffectiveUntil.set(record.effectiveUntil ?? '');
    this.formStartTime.set(record.startTime);
    this.formEndTime.set(record.endTime);
    this.formReason.set(record.reason ?? '');
  }

  cancelEdit(): void {
    this.editingId.set(null);
    this.formRecurring.set(false);
    this.formDate.set('');
    this.formDayOfWeek.set('MONDAY');
    this.formEffectiveFrom.set('');
    this.formEffectiveUntil.set('');
    this.formStartTime.set('09:00');
    this.formEndTime.set('10:00');
    this.formReason.set('');
    this.conflictResult.set(null);
  }

  openExceptionForm(unavailabilityId: number): void {
    this.exceptionTargetId.set(unavailabilityId);
    this.showExceptionForm.set(true);
  }

  closeExceptionForm(): void {
    this.showExceptionForm.set(false);
    this.exOriginalDate.set('');
    this.exType.set('SKIP');
    this.exRescheduledDate.set('');
    this.exStartTime.set('');
    this.exEndTime.set('');
    this.exReason.set('');
  }

  async addException(): Promise<void> {
    this.loading.set(true);
    try {
      await this.service.addException(
        this.clinicId(), this.equipmentId(), this.exceptionTargetId(),
        {
          originalDate: this.exOriginalDate(),
          exceptionType: this.exType(),
          rescheduledDate: this.exType() === 'RESCHEDULE' ? this.exRescheduledDate() : null,
          rescheduledStartTime: this.exType() === 'RESCHEDULE' ? this.exStartTime() : null,
          rescheduledEndTime: this.exType() === 'RESCHEDULE' ? this.exEndTime() : null,
          reason: this.exReason() || null,
        }
      );
      this.snackBar.open('예외가 등록되었습니다.', '확인', { duration: 3000 });
      this.closeExceptionForm();
    } catch {
      this.snackBar.open('예외 등록에 실패했습니다.', '확인', { duration: 3000 });
    } finally {
      this.loading.set(false);
    }
  }

  private buildRequest(): CreateEquipmentUnavailabilityRequest {
    return {
      unavailableDate: this.formRecurring() ? null : this.formDate(),
      isRecurring: this.formRecurring(),
      recurringDayOfWeek: this.formRecurring() ? this.formDayOfWeek() : null,
      effectiveFrom: this.formEffectiveFrom() || this.formDate(),
      effectiveUntil: this.formEffectiveUntil() || null,
      startTime: this.formStartTime(),
      endTime: this.formEndTime(),
      reason: this.formReason() || null,
    };
  }
}
