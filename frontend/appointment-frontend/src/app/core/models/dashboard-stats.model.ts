/** Daily appointment counts for one date, broken down by status. */
export interface DailyAppointmentBucket {
  date: string;
  countsByStatus: Record<string, number>;
  total: number;
}

/** GET /api/admin/stats/appointments response. */
export interface AppointmentStatsResponse {
  clinicId: number;
  from: string;
  to: string;
  totals: Record<string, number>;
  daily: DailyAppointmentBucket[];
}

/** Appointment metrics for a single doctor. */
export interface DoctorBucket {
  doctorId: number;
  totalAppointments: number;
  completed: number;
  cancelled: number;
  noShow: number;
  completionRate: number;
}

/** GET /api/admin/stats/doctors response. */
export interface DoctorStatsResponse {
  clinicId: number;
  from: string;
  to: string;
  doctors: DoctorBucket[];
}

/** GET /api/admin/stats/cancellations response. */
export interface CancellationStatsResponse {
  clinicId: number;
  from: string;
  to: string;
  totalCancelled: number;
  totalNoShow: number;
  totalRescheduled: number;
  totalCompleted: number;
  cancellationRate: number;
  noShowRate: number;
  daily: DailyCancellationBucket[];
}

/** Per-day cancellation breakdown. */
export interface DailyCancellationBucket {
  date: string;
  cancelled: number;
  noShow: number;
  rescheduled: number;
}
