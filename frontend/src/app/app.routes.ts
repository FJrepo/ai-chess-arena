import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', redirectTo: '/tournaments', pathMatch: 'full' },
  {
    path: 'tournaments',
    loadComponent: () =>
      import('./pages/tournament-list/tournament-list').then((m) => m.TournamentList),
  },
  {
    path: 'tournaments/new',
    loadComponent: () =>
      import('./pages/tournament-setup/tournament-setup').then((m) => m.TournamentSetup),
  },
  {
    path: 'analytics',
    loadComponent: () =>
      import('./pages/analytics-dashboard/analytics-dashboard').then((m) => m.AnalyticsDashboard),
  },
  {
    path: 'tournaments/:id',
    loadComponent: () =>
      import('./pages/tournament-bracket/tournament-bracket').then((m) => m.TournamentBracket),
  },
  {
    path: 'games/:id',
    loadComponent: () => import('./pages/game-view/game-view').then((m) => m.GameView),
  },
];
