import { TestBed } from '@angular/core/testing';
import { ChatPanel } from './chat-panel';

describe('ChatPanel', () => {
  it('shows the tournament chat mode next to the subtitle and an empty text that fits it', async () => {
    TestBed.configureTestingModule({ imports: [ChatPanel] });
    const fixture = TestBed.createComponent(ChatPanel);
    fixture.componentRef.setInput('messages', []);
    fixture.componentRef.setInput('chatMode', 'MANDATORY');
    fixture.detectChanges();
    await fixture.whenStable();
    const element: HTMLElement = fixture.nativeElement;

    expect(element.querySelector('.chat-mode-label')?.textContent).toContain('Chat mandatory');
    expect(element.querySelector('.empty-chat')?.textContent).toContain(
      'short message with every move',
    );

    fixture.componentRef.setInput('chatMode', 'OFF');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(element.querySelector('.chat-mode-label')?.textContent).toContain('Chat off');
    expect(element.querySelector('.empty-chat')?.textContent).toContain('Chat is off');
  });

  it('shows no mode while it is unknown', () => {
    const panel = new ChatPanel();

    expect(panel.chatModeLabel()).toBeNull();
    expect(panel.emptyText()).toContain('trash talk will appear here');

    panel.chatMode = 'OPTIONAL';
    expect(panel.chatModeLabel()).toBe('Chat optional');
  });
  it('shows each tone next to the chat mode, hides it while Off, and clears it', async () => {
    TestBed.configureTestingModule({ imports: [ChatPanel] });
    const fixture = TestBed.createComponent(ChatPanel);
    fixture.componentRef.setInput('chatMode', 'OPTIONAL');
    for (const [level, label] of [
      ['POLITE', 'Polite'],
      ['PLAYFUL', 'Playful'],
      ['SPICY', 'Spicy'],
      ['MIND_GAMES', 'Mind games'],
    ]) {
      fixture.componentRef.setInput('banterLevel', level);
      fixture.detectChanges();
      await fixture.whenStable();
      expect(fixture.nativeElement.querySelector('.chat-mode-label').textContent).toContain(
        `Chat optional · ${label}`,
      );
    }
    fixture.componentRef.setInput('chatMode', 'OFF');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.chat-mode-label').textContent).not.toContain(
      'Mind games',
    );
    fixture.componentRef.setInput('chatMode', 'MANDATORY');
    fixture.componentRef.setInput('banterLevel', null);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.chat-mode-label').textContent.trim()).toBe(
      '· Chat mandatory',
    );
  });

  function message(id: string, moveNumber: number, senderColor: 'WHITE' | 'BLACK') {
    return {
      id,
      moveNumber,
      senderModel: 'model',
      senderColor,
      message: `Message ${id}`,
      createdAt: '2026-03-06T10:02:00Z',
    };
  }

  it('makes the transcript a named, focusable scroll region', () => {
    TestBed.configureTestingModule({ imports: [ChatPanel] });
    const fixture = TestBed.createComponent(ChatPanel);
    fixture.componentRef.setInput('messages', [message('a', 1, 'WHITE')]);
    fixture.detectChanges();
    const element: HTMLElement = fixture.nativeElement;
    const scroll = element.querySelector<HTMLElement>('.chat-scroll')!;

    expect(scroll.getAttribute('tabindex')).toBe('0');
    expect(scroll.getAttribute('role')).toBe('region');
    const labelId = scroll.getAttribute('aria-labelledby')!;
    expect(labelId).toBeTruthy();
    expect(element.querySelector(`[id="${labelId}"]`)?.textContent?.trim()).toBe('Chat');
  });

  it('turns a move number into a button when that ply was played, and emits its index', () => {
    TestBed.configureTestingModule({ imports: [ChatPanel] });
    const fixture = TestBed.createComponent(ChatPanel);
    fixture.componentRef.setInput('messages', [
      message('a', 1, 'WHITE'),
      message('b', 1, 'BLACK'),
      message('c', 2, 'WHITE'),
    ]);
    fixture.componentRef.setInput(
      'moveIndexByPly',
      new Map([
        ['1-WHITE', 0],
        ['1-BLACK', 1],
      ]),
    );
    const selected = vi.fn();
    fixture.componentInstance.moveSelected.subscribe(selected);
    fixture.detectChanges();
    const element: HTMLElement = fixture.nativeElement;

    // Newest first: 2 (no recorded ply), then 1 Black, then 1 White.
    const rows = Array.from(element.querySelectorAll<HTMLElement>('.chat-message'));
    expect(rows[0].querySelector('button.msg-move')).toBeNull();
    expect(rows[0].querySelector('.msg-time')?.textContent?.trim()).toBe('move 2');
    const black = rows[1].querySelector<HTMLButtonElement>('button.msg-move')!;
    expect(black.type).toBe('button');
    expect(black.textContent?.trim()).toBe('move 1');
    expect(black.getAttribute('aria-label')).toBe("Show the board after Black's move 1");
    black.click();
    rows[2].querySelector<HTMLButtonElement>('button.msg-move')!.click();
    expect(selected.mock.calls).toEqual([[1], [0]]);
  });
});
