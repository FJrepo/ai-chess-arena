import { Component, Input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { ProviderBrand, resolveProviderBrand } from '../../utils/provider-brand';

@Component({
  selector: 'app-provider-logo',
  imports: [MatIconModule],
  templateUrl: './provider-logo.html',
  styleUrl: './provider-logo.scss',
})
export class ProviderLogoComponent {
  @Input() provider: string | null = null;
  @Input() modelId: string | null = null;
  @Input() size = 18;

  get brand(): ProviderBrand {
    return resolveProviderBrand(this.provider, this.modelId);
  }
}
