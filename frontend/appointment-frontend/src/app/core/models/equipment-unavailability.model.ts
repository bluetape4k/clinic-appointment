export interface EquipmentUnavailabilityRecord {
  id: number;
  equipmentId: number;
  clinicId: number;
  unavailableDate: string | null;   // YYYY-MM-DD, null when recurring
  isRecurring: boolean;
  recurringDayOfWeek: string | null; // 'MONDAY'...'SUNDAY', null when one-time
  effectiveFrom: string;             // YYYY-MM-DD
  effectiveUntil: string | null;     // YYYY-MM-DD, null = indefinite
  startTime: string;                 // HH:mm:ss
  endTime: string;                   // HH:mm:ss
  reason: string | null;
}

export interface EquipmentUnavailabilityExceptionRecord {
  id: number;
  unavailabilityId: number;
  originalDate: string;             // YYYY-MM-DD
  exceptionType: 'SKIP' | 'RESCHEDULE';
  rescheduledDate: string | null;
  rescheduledStartTime: string | null;
  rescheduledEndTime: string | null;
  reason: string | null;
}

export interface CreateEquipmentUnavailabilityRequest {
  unavailableDate: string | null;
  isRecurring: boolean;
  recurringDayOfWeek: string | null;
  effectiveFrom: string;
  effectiveUntil: string | null;
  startTime: string;
  endTime: string;
  reason: string | null;
}

export interface UpdateEquipmentUnavailabilityRequest {
  unavailableDate: string | null;
  isRecurring: boolean;
  recurringDayOfWeek: string | null;
  effectiveFrom: string;
  effectiveUntil: string | null;
  startTime: string;
  endTime: string;
  reason: string | null;
}

export interface UnavailabilityExceptionRequest {
  originalDate: string;
  exceptionType: 'SKIP' | 'RESCHEDULE';
  rescheduledDate: string | null;
  rescheduledStartTime: string | null;
  rescheduledEndTime: string | null;
  reason: string | null;
}

export interface ConflictingAppointmentResponse {
  appointmentId: number;
  patientName: string;
  appointmentDate: string;
  startTime: string;
  endTime: string;
  doctorId: number;
  equipmentId: number;
}

export interface UnavailabilityConflictResponse {
  unavailabilityId: number;
  conflictCount: number;
  conflicts: ConflictingAppointmentResponse[];
}
