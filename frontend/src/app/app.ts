import { Component, OnInit, signal } from '@angular/core';
import { RouterOutlet, RouterLink } from '@angular/router';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, MatToolbarModule, MatButtonModule, MatIconModule],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App implements OnInit {
  darkMode = signal(true);

  ngOnInit(): void {
    this.applyTheme(this.resolveInitialTheme());
  }

  toggleTheme(): void {
    this.applyTheme(!this.darkMode());
  }

  private applyTheme(isDark: boolean): void {
    this.darkMode.set(isDark);
    document.documentElement.setAttribute('data-theme', isDark ? 'dark' : 'light');
    document.documentElement.classList.toggle('dark-theme', isDark);
    document.body.classList.toggle('dark-theme', isDark);
    try {
      localStorage.setItem('theme', isDark ? 'dark' : 'light');
    } catch {
      // Ignore storage failures (private mode / restrictive browser settings).
    }
  }

  private resolveInitialTheme(): boolean {
    try {
      const savedTheme = localStorage.getItem('theme');
      if (savedTheme === 'dark' || savedTheme === 'true') {
        return true;
      }
      if (savedTheme === 'light' || savedTheme === 'false') {
        return false;
      }
    } catch {
      // Fall through to system/default preference.
    }

    return true;
  }
}
