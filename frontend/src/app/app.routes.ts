import { Routes } from '@angular/router';
import { DashboardComponent } from './pages/dashboard/dashboard.component';
import { BenchmarksComponent } from './pages/benchmarks/benchmarks.component';
import { ResultsComponent } from './pages/results/results.component';
import { UploadComponent } from './pages/upload/upload.component';
import { TracePageComponent } from './pages/trace/trace-page.component';

export const routes: Routes = [
  { path: '', component: DashboardComponent },
  { path: 'benchmarks', component: BenchmarksComponent },
  { path: 'results', component: ResultsComponent },
  { path: 'upload', component: UploadComponent },
  { path: 'trace/:resultId', component: TracePageComponent },
  { path: '**', redirectTo: '' }
];
