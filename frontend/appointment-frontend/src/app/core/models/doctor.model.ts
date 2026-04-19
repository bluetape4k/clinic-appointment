export interface Doctor {
  id: number;
  clinicId: number;
  name: string;
  specialty?: string;
  providerType: string;
  maxConcurrentPatients?: number;
}

export interface DoctorSchedule {
  id: number;
  doctorId: number;
  dayOfWeek: string;
  startTime: string;
  endTime: string;
}

export interface DoctorAbsence {
  id: number;
  doctorId: number;
  absenceDate: string;
  startTime?: string;
  endTime?: string;
  reason?: string;
}
