import { TestBed } from '@angular/core/testing';
import { MatExpansionPanel } from '@angular/material/expansion';
import { By } from '@angular/platform-browser';
import type { Game, GameConfiguration, MoveAttempt } from '../../models/tournament.model';
import { GameConfigurationHistory } from './game-configuration-history';

describe('GameConfigurationHistory', () => {
  it('shows the requested and sent effort per attempt, including unknowns and reported zero', () => {
    const first: GameConfiguration = {
      maxAttempts: 3,
      maxOutputTokens: 16384,
      chatMode: 'OPTIONAL',
      resignationAllowed: false,
      adjudicationEnabled: false,
      legalMoveHints: true,
      reasoningEffort: 'medium',
      sharedInstructionsHash: 'first-fingerprint',
      white: { controlType: 'AI', requestedReasoningEffort: 'medium' },
      black: { controlType: 'AI', requestedReasoningEffort: 'medium' },
    };
    const second: GameConfiguration = {
      ...first,
      reasoningEffort: 'high',
      sharedInstructionsHash: 'second-fingerprint',
      white: { controlType: 'AI', requestedReasoningEffort: 'high' },
    };
    const fixture = TestBed.createComponent(GameConfigurationHistory);
    fixture.componentRef.setInput('game', {
      status: 'COMPLETED',
      startingConfiguration: first,
      configurationHistory: [
        { revision: 1, settings: first },
        { revision: 2, settings: second },
      ],
    } satisfies Partial<Game>);
    fixture.componentRef.setInput('attempts', [
      {
        id: 'first',
        moveNumber: 1,
        color: 'WHITE',
        attemptNumber: 1,
        configurationRevision: 1,
        reasoningEffort: 'medium',
        reasoningTokens: 120,
      },
      {
        id: 'second',
        moveNumber: 2,
        color: 'WHITE',
        attemptNumber: 1,
        configurationRevision: 2,
        reasoningEffort: 'medium',
        reasoningTokens: 0,
      },
      { id: 'old', moveNumber: 3, color: 'WHITE', attemptNumber: 1, reasoningTokens: null },
    ] satisfies Partial<MoveAttempt>[]);
    fixture.detectChanges();
    fixture.debugElement.query(By.directive(MatExpansionPanel)).componentInstance.open();
    fixture.detectChanges();
    const text: string = fixture.nativeElement.textContent;

    expect(text).toMatch(/Reasoning effort\s*Medium\s*High/);
    expect(text).toMatch(/Shared instructions\s*Custom\s*Edited/);
    expect(text).not.toContain('second-fingerprint');
    expect(text).toContain('2 of 3 attempts reported reasoning tokens.');
    expect(text).toMatch(/1 White\s*1\s*1\s*Medium\s*Medium\s*120/);
    expect(text).toMatch(/2 White\s*1\s*2\s*High\s*Medium\s*0/);
    expect(text).toMatch(/3 White\s*1\s*Unknown\s*Unknown\s*Unknown/);
  });
});
