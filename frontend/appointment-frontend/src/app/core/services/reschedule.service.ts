import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom, Observable } from 'rxjs';
import { ApiResponse, RescheduleCandidate, BatchRescheduleStreamParams, RescheduleProgressEvent } from '../models';
import { environment } from '../../../environments/environment';
import { AuthService } from './auth.service';

@Injectable({ providedIn: 'root' })
export class RescheduleService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/appointments`;
  private readonly authService = inject(AuthService);

  /**
   * 진료실 휴진 일괄 재배정 후보 조회 (R1)
   */
  async getClosureCandidates(
    appointmentId: number,
    clinicId: number,
    closureDate: string,
    searchDays: number,
  ): Promise<Map<number, RescheduleCandidate[]>> {
    const params = new HttpParams()
      .set('clinicId', clinicId)
      .set('closureDate', closureDate)
      .set('searchDays', searchDays);
    const res = await firstValueFrom(
      this.http.post<ApiResponse<Record<number, RescheduleCandidate[]>>>(
        `${this.baseUrl}/${appointmentId}/reschedule/closure`,
        null,
        { params },
      )
    );
    return new Map(Object.entries(res.data ?? {}).map(([k, v]) => [Number(k), v]));
  }

  /**
   * 개별 예약 재배정 후보 목록 조회 (R2)
   */
  async getCandidates(appointmentId: number): Promise<RescheduleCandidate[]> {
    const res = await firstValueFrom(
      this.http.get<ApiResponse<RescheduleCandidate[]>>(
        `${this.baseUrl}/${appointmentId}/reschedule/candidates`,
      )
    );
    return res.data ?? [];
  }

  /**
   * 선택한 후보로 재배정 확정 (R3)
   */
  async confirm(appointmentId: number, candidateId: number): Promise<number> {
    const res = await firstValueFrom(
      this.http.post<ApiResponse<number>>(
        `${this.baseUrl}/${appointmentId}/reschedule/confirm/${candidateId}`,
        null,
      )
    );
    return res.data!;
  }

  /**
   * 최적 후보로 자동 재배정 (R4)
   */
  async autoReschedule(appointmentId: number): Promise<number | null> {
    const res = await firstValueFrom(
      this.http.post<ApiResponse<number | null>>(
        `${this.baseUrl}/${appointmentId}/reschedule/auto`,
        null,
      )
    );
    return res.data ?? null;
  }

  /**
   * Streams batch closure reschedule progress as Server-Sent Events.
   *
   * Uses fetch + ReadableStream to support Authorization header (EventSource does not).
   *
   * ## Behavior / Contract
   * - Emits one RescheduleProgressEvent per appointment as candidates are found.
   * - Emits a terminal event with done=true when the stream completes.
   * - Unsubscribing aborts the fetch via AbortController.
   */
  streamBatchReschedule(params: BatchRescheduleStreamParams): Observable<RescheduleProgressEvent> {
    return new Observable(observer => {
      const token = this.authService.getToken();
      const qs = new URLSearchParams({
        clinicId: String(params.clinicId),
        closureDate: params.closureDate,
        searchDays: String(params.searchDays),
      });
      const url = `${environment.apiUrl}/reschedule/batch/stream?${qs}`;
      const controller = new AbortController();

      fetch(url, {
        headers: {
          Accept: 'text/event-stream',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        signal: controller.signal,
      })
        .then(async response => {
          if (response.status === 401) {
            observer.error(new Error('SSE: 인증이 필요합니다.'));
            return;
          }
          if (!response.ok) {
            observer.error(new Error(`SSE failed: ${response.status}`));
            return;
          }
          if (!response.body) {
            observer.error(new Error('SSE: 응답 본문이 없습니다.'));
            return;
          }

          const reader = response.body.getReader();
          const decoder = new TextDecoder();
          let buffer = '';

          while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });
            const blocks = buffer.split('\n\n');
            buffer = blocks.pop() ?? '';

            for (const block of blocks) {
              if (!block.trim()) continue;
              let dataLine = '';
              for (const line of block.split('\n')) {
                if (line.startsWith('data:')) {
                  dataLine = line.slice(5).trim();
                }
              }
              if (!dataLine) continue;
              try {
                const event: RescheduleProgressEvent = JSON.parse(dataLine);
                observer.next(event);
                if (event.done) {
                  observer.complete();
                  return;
                }
              } catch {
                // malformed JSON — skip
              }
            }
          }
          observer.complete();
        })
        .catch(err => {
          if ((err as Error).name !== 'AbortError') {
            observer.error(err);
          }
        });

      return () => controller.abort();
    });
  }
}
