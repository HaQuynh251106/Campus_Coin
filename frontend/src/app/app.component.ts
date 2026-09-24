import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { ThemeService } from './core/services/theme.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, RouterModule],
  template: `
    <router-outlet></router-outlet>
  `,
  styles: [`
    :host {
      display: block;
      min-height: 100vh;
    }
  `]
})
export class AppComponent {
  // Inject ThemeService on bootstrap to initialize dark mode and font preferences
  private theme = inject(ThemeService);
}

// Alias export for backward compatibility with Angular CLI main.ts
export { AppComponent as App };
