import { Routes } from '@angular/router';
import { DashboardComponent } from './pages/dashboard/dashboard.component';
import { BenchmarksComponent } from './pages/benchmarks/benchmarks.component';
import { DblpComponent } from './pages/dblp/dblp.component';
import { ResultsComponent } from './pages/results/results.component';
import { TracePageComponent } from './pages/trace/trace-page.component';
import { ScalabilityComponent } from './pages/scalability/scalability.component';
import { SyntheticComponent } from './pages/synthetic/synthetic.component';

export const routes: Routes = [
  { path: '', component: DashboardComponent },
  { path: 'benchmarks', component: BenchmarksComponent },
  { path: 'dblp', component: DblpComponent },
  { path: 'results', component: ResultsComponent },
  { path: 'scalability', component: ScalabilityComponent },
  { path: 'synthetic', component: SyntheticComponent },
  { path: 'trace/:resultId', component: TracePageComponent },
  { path: '**', redirectTo: '' }
];
