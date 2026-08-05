import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Api } from '../../core/api.service';
import { Preferences } from '../../core/preferences.service';

@Component({
  selector: 'app-login',
  imports: [FormsModule],
  templateUrl: './login.html',
  styleUrl: './login.css',
})
export class LoginPage {
  private api = inject(Api);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private preferences = inject(Preferences);
  identifier = signal('');
  password = signal('');
  loading = signal(false);
  error = signal('');

  submit(): void {
    if (!this.identifier() || !this.password() || this.loading()) return;
    this.loading.set(true);
    this.error.set('');
    this.api.login(this.identifier(), this.password()).subscribe({
      next: ({ user }) => {
        this.preferences.applyUser(user);
        const target = this.route.snapshot.queryParamMap.get('next') || '/summary';
        this.router.navigateByUrl(target.startsWith('/') ? target : '/summary');
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Incorrect username, email, or password.');
      },
    });
  }
}
