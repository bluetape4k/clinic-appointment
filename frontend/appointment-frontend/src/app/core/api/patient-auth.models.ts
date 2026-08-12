export type PatientLoginIdentifierKey = 'PHONE' | 'EMAIL' | 'LOGIN_ID';

export interface PatientLoginIdentifierRequest {
  key: PatientLoginIdentifierKey;
  value: string;
}

export interface PatientRegisterRequest {
  displayName: string;
  password: string;
  identifiers: PatientLoginIdentifierRequest[];
}

export interface PatientLoginRequest {
  identifier: PatientLoginIdentifierRequest;
  password: string;
}

export interface PatientSessionSummary {
  tenantCode: string;
  role: 'PATIENT';
  displayName: string;
  expiresAt: string;
}

export interface PatientRegistrationResponse {
  registered: boolean;
}
