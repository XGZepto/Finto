import { Component, input } from '@angular/core';

@Component({
  selector: 'finto-nav-icon',
  template: `
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"
         stroke-linecap="square" stroke-linejoin="miter" aria-hidden="true">
      @switch (name()) {
        @case ('summary') { <path d="M4 13h6V4H4v9Zm10 7h6V11h-6v9ZM4 20h6v-3H4v3Zm10-13h6V4h-6v3Z" /> }
        @case ('accounts') { <path d="M3 7h18M5 7V5h14v2M5 11h14v8H5zM8 15h3" /> }
        @case ('timeline') { <path d="M4 6h4M4 12h4M4 18h4M11 6h9M11 12h7M11 18h9" /> }
        @case ('blotter') { <path d="M4 5h16M4 10h16M4 15h16M4 20h10" /> }
        @case ('import') { <path d="M12 4v11m0-11L8 8m4-4 4 4M5 15v5h14v-5" /> }
        @case ('installments') { <path d="M4 6h16v12H4zM8 10h8M8 14h5" /> }
        @case ('investments') { <path d="M4 18 9 13l3 3 8-10M15 6h5v5" /> }
        @case ('review') { <path d="M5 4h14v16H5zM8 8h8M8 12h5M8 16h3" /> }
        @case ('integrity') { <path d="m5 12 4 4L19 6M4 4h16v16H4z" /> }
        @case ('ask') { <path d="M9 9a3 3 0 1 1 4 2.83c-.61.27-1 .87-1 1.54V14M12 18h.01M4 4h16v16H4z" /> }
        @case ('settings') { <path d="M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8Zm0-5v3m0 12v3M3 12h3m12 0h3M5.64 5.64l2.12 2.12m8.48 8.48 2.12 2.12m0-12.72-2.12 2.12M7.76 16.24l-2.12 2.12" /> }
        @case ('more') { <path d="M5 12h.01M12 12h.01M19 12h.01" stroke-width="3" /> }
      }
    </svg>
  `,
  styles: `:host, svg { display: block; width: 100%; height: 100%; }`,
})
export class NavIcon { readonly name = input.required<string>(); }
