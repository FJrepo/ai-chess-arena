import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { Practice } from './practice';

describe('Practice', () => {
  it('validates the strength and sends the selected color for a Stockfish game', () => {
    const createStockfishPractice = vi.fn(() => of({ id: 'engine-game' }));
    TestBed.configureTestingModule({
      providers: [
        {
          provide: ApiService,
          useValue: { getPracticeGames: () => of([]), createStockfishPractice },
        },
        provideRouter([{ path: 'games/:id', component: Practice }]),
      ],
    });
    const component = TestBed.createComponent(Practice).componentInstance;
    component.elo = 1319;
    component.createStockfish();
    component.elo = 1500.5;
    component.createStockfish();
    expect(createStockfishPractice).not.toHaveBeenCalled();

    component.elo = 1500;
    component.side = 'BLACK';
    component.createStockfish();
    expect(createStockfishPractice).toHaveBeenCalledOnce();
    expect(createStockfishPractice).toHaveBeenCalledWith('BLACK', 1500);
  });
});
