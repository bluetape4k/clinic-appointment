import { Routes } from '@angular/router';
import { formatCalendarDate } from '../../core/services/calendar-state.service';

export const CALENDAR_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./calendar-view/calendar-view.component').then((m) => m.CalendarViewComponent),
    children: [
      { path: '', redirectTo: 'week', pathMatch: 'full' },
      {
        path: 'day',
        redirectTo: `day/${formatToday()}`,
        pathMatch: 'full',
      },
      {
        path: 'day/:date',
        loadComponent: () =>
          import('./day-view/day-view.component').then((m) => m.DayViewComponent),
      },
      {
        path: 'week',
        redirectTo: `week/${formatToday()}`,
        pathMatch: 'full',
      },
      {
        path: 'week/:date',
        loadComponent: () =>
          import('./week-view/week-view.component').then((m) => m.WeekViewComponent),
      },
      {
        path: 'month',
        redirectTo: `month/${formatToday()}`,
        pathMatch: 'full',
      },
      {
        path: 'month/:date',
        loadComponent: () =>
          import('./month-view/month-view.component').then((m) => m.MonthViewComponent),
      },
    ],
  },
];

function formatToday(): string {
  return formatCalendarDate(new Date());
}
