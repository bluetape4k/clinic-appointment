export interface RescheduleProgressEvent {
  appointmentId: number;
  candidateCount: number;
  totalProcessed: number;
  done: boolean;
}

export interface BatchRescheduleStreamParams {
  clinicId: number;
  closureDate: string;
  searchDays: number;
}
