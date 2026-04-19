import { Routes } from '@angular/router';

export const MANAGEMENT_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./dashboard/management-dashboard.component').then(
        m => m.ManagementDashboardComponent
      ),
  },
  {
    path: 'clinics',
    loadComponent: () =>
      import('./clinic-list/clinic-list.component').then(
        m => m.ClinicListComponent
      ),
  },
  {
    path: 'doctors',
    loadComponent: () =>
      import('./doctor-list/doctor-list.component').then(
        m => m.DoctorListComponent
      ),
  },
  {
    path: 'treatments',
    loadComponent: () =>
      import('./treatment-list/treatment-type-list.component').then(
        m => m.TreatmentTypeListComponent
      ),
  },
  {
    path: 'reschedule',
    loadComponent: () =>
      import('./reschedule-list/reschedule-list.component').then(
        m => m.RescheduleListComponent
      ),
  },
  {
    path: 'equipment-unavailability',
    loadComponent: () =>
      import('./equipment-unavailability-list/equipment-unavailability-list.component').then(
        m => m.EquipmentUnavailabilityListComponent
      ),
  },
];
