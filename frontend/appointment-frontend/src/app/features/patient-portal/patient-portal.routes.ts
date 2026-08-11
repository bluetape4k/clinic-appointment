import { Routes } from '@angular/router';
import { PatientPortalShellComponent } from './patient-portal-shell.component';
import { PatientAppointmentsPageComponent } from './pages/patient-appointments-page.component';
import { PatientNotificationsPageComponent } from './pages/patient-notifications-page.component';
import { PatientProfilePageComponent } from './pages/patient-profile-page.component';

export const PATIENT_PORTAL_ROUTES: Routes = [
  {
    path: '',
    component: PatientPortalShellComponent,
    children: [
      { path: '', redirectTo: 'appointments', pathMatch: 'full' },
      { path: 'appointments', component: PatientAppointmentsPageComponent },
      { path: 'notifications', component: PatientNotificationsPageComponent },
      { path: 'profile', component: PatientProfilePageComponent },
    ],
  },
];
