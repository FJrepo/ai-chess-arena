import { expectTypeOf } from 'vitest';
import type { ModelReliability } from './analytics.model';

it('allows an unknown average cost per move in reliability results', () => {
  expectTypeOf<ModelReliability['averageCostPerMoveUsd']>().toEqualTypeOf<number | null>();
});
