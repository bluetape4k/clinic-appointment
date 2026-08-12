import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';

export type PortalApiErrorKind =
  | 'tenant-missing'
  | 'transport'
  | 'unauthorized'
  | 'forbidden'
  | 'conflict'
  | 'expired'
  | 'precondition'
  | 'retryable'
  | 'unknown';

export interface PortalApiErrorState {
  kind: PortalApiErrorKind;
  status: number;
  code: string;
  message: string;
  retryAfterSeconds: number | null;
  correlationId: string | null;
}

export class PortalApiException extends Error {
  constructor(readonly state: PortalApiErrorState, cause?: unknown) {
    super(state.message, { cause });
    this.name = 'PortalApiException';
  }
}

export function mapPortalApiError(error: HttpErrorResponse): PortalApiErrorState {
  const payload = typeof error.error === 'object' && error.error !== null ? error.error as Record<string, unknown> : {};
  const status = error.status;
  const code = typeof payload['errorCode'] === 'string' ? payload['errorCode'] : `HTTP_${status}`;
  const message = typeof payload['error'] === 'string' ? payload['error'] : '예약 요청을 처리하지 못했습니다.';
  const correlationId = typeof payload['correlationId'] === 'string' ? payload['correlationId'] : null;
  const retryAfterSeconds = parseRetryAfter(error.headers);

  let kind: PortalApiErrorKind;
  if (status === 0) kind = 'transport';
  else if (status === 401) kind = 'unauthorized';
  else if (status === 403) kind = 'forbidden';
  else if (status === 409 || status === 412) kind = 'conflict';
  else if (status === 410) kind = 'expired';
  else if (status === 422 || status === 428) kind = 'precondition';
  else if (status === 429 || status === 500 || status === 502 || status === 503 || status === 504) kind = 'retryable';
  else kind = 'unknown';

  return { kind, status, code, message, retryAfterSeconds, correlationId };
}

function parseRetryAfter(headers: HttpHeaders): number | null {
  const raw = headers.get('Retry-After');
  if (!raw) return null;
  const seconds = Number.parseInt(raw, 10);
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : null;
}
