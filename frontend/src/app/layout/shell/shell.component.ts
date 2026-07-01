import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss'
})
export class ShellComponent {
  navLinks = [
    { label: 'Dashboard', path: '' },
    { label: 'Benchmarks', path: 'benchmarks' },
    { label: 'Scalability', path: 'scalability' },
    { label: 'Results', path: 'results' },
    { label: 'Upload', path: 'upload' }
  ];
}
