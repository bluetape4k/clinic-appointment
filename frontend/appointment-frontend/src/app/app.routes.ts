import { Routes } from '@angular/router';
import { roleGuard } from './core/guards/role.guard';

export const routes: Routes = [
  { path: '', redirectTo: 'calendar', pathMatch: 'full' },
  {
    path: 'calendar',
    loadChildren: () => import('./features/calendar/calendar.routes').then(m => m.CALENDAR_ROUTES),
  },
  {
    path: 'appointments',
    loadChildren: () => import('./features/appointments/appointments.routes').then(m => m.APPOINTMENT_ROUTES),
  },
  {
    path: 'portal',
    loadChildren: () => import('./features/patient-portal/patient-portal.routes').then(m => m.PATIENT_PORTAL_ROUTES),
  },
  {
    path: 'management',
    canActivate: [roleGuard],
    data: { requiredRoles: ['ROLE_ADMIN', 'ROLE_STAFF', 'ROLE_DOCTOR'] },
    loadChildren: () => import('./features/management/management.routes').then(m => m.MANAGEMENT_ROUTES),
  },
];
