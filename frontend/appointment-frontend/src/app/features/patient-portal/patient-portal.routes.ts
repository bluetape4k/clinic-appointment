import { Routes } from '@angular/router';
import { PatientPortalShellComponent } from './patient-portal-shell.component';
import { PatientAppointmentsPageComponent } from './pages/patient-appointments-page.component';
import { PatientNotificationsPageComponent } from './pages/patient-notifications-page.component';
import { PatientProfilePageComponent } from './pages/patient-profile-page.component';
import { PatientPortalVisualFixturePageComponent } from './pages/patient-portal-visual-fixture-page.component';
import { PatientLoginPageComponent } from './pages/patient-login-page.component';
import { PatientRegisterPageComponent } from './pages/patient-register-page.component';
import { patientAuthGuard } from '../../core/guards/patient-auth.guard';
import { environment } from '../../../environments/environment';

export const PATIENT_PORTAL_ROUTES: Routes = [
  { path: 'login', component: PatientLoginPageComponent },
  { path: 'register', component: PatientRegisterPageComponent },
  {
    path: '',
    component: PatientPortalShellComponent,
    canActivate: [patientAuthGuard],
    children: [
      { path: '', redirectTo: 'appointments', pathMatch: 'full' },
      {
        path: 'appointments',
        children: [
          { path: '', component: PatientAppointmentsPageComponent },
          ...(!environment.production
            ? [{ path: 'visual-fixture', component: PatientPortalVisualFixturePageComponent }]
            : []),
        ],
      },
      { path: 'notifications', component: PatientNotificationsPageComponent },
      { path: 'profile', component: PatientProfilePageComponent },
    ],
  },
];
