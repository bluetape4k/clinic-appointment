export interface Clinic {
  id: number;
  name: string;
  slotDurationMinutes: number;
  timezone: string;
  locale: string;
  maxConcurrentPatients: number;
  openOnHolidays: boolean;
}

export interface OperatingHours {
  id: number;
  clinicId: number;
  dayOfWeek: string;
  openTime: string;
  closeTime: string;
  isActive: boolean;
}

export interface ClinicBreakTime {
  id: number;
  clinicId: number;
  name: string;
  startTime: string;
  endTime: string;
}
