import { Component, OnInit, inject } from '@angular/core';
import { MatTableModule } from '@angular/material/table';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { TreatmentType } from '../../../core/models';
import { TreatmentTypeService } from '../../../core/services';

const CLINIC_ID = 1;

@Component({
  selector: 'app-treatment-type-list',
  standalone: true,
  imports: [MatTableModule, MatCardModule, MatIconModule],
  template: `
    <div class="page-container">
      <h1 class="page-title">진료 유형</h1>

      <mat-card>
        <mat-card-content>
          <table mat-table [dataSource]="treatmentTypes()" class="full-width">
            <ng-container matColumnDef="name">
              <th mat-header-cell *matHeaderCellDef>진료명</th>
              <td mat-cell *matCellDef="let t">{{ t.name }}</td>
            </ng-container>

            <ng-container matColumnDef="category">
              <th mat-header-cell *matHeaderCellDef>카테고리</th>
              <td mat-cell *matCellDef="let t">{{ t.category }}</td>
            </ng-container>

            <ng-container matColumnDef="duration">
              <th mat-header-cell *matHeaderCellDef>기본 소요 시간</th>
              <td mat-cell *matCellDef="let t">{{ t.defaultDurationMinutes }}분</td>
            </ng-container>

            <ng-container matColumnDef="requiresEquipment">
              <th mat-header-cell *matHeaderCellDef>장비 필요</th>
              <td mat-cell *matCellDef="let t">{{ t.requiresEquipment ? '예' : '아니오' }}</td>
            </ng-container>

            <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
            <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>

            <tr class="mat-row" *matNoDataRow>
              <td class="mat-cell no-data" [attr.colspan]="displayedColumns.length">
                등록된 진료 유형이 없습니다.
              </td>
            </tr>
          </table>
        </mat-card-content>
      </mat-card>
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

    .full-width {
      width: 100%;
    }

    .no-data {
      text-align: center;
      padding: 24px;
      color: #999;
    }

    th.mat-header-cell {
      font-weight: 600;
      color: #333;
    }
  `],
})
export class TreatmentTypeListComponent implements OnInit {
  private readonly treatmentTypeService = inject(TreatmentTypeService);

  readonly displayedColumns = ['name', 'category', 'duration', 'requiresEquipment'];
  readonly treatmentTypes = this.treatmentTypeService.treatmentTypes;

  ngOnInit(): void {
    this.treatmentTypeService.loadByClinic(CLINIC_ID);
  }
}
