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
    expect(selected.mock.calls).toEqual([
      [{ index: 1, pointer: false }],
      [{ index: 0, pointer: false }],
    ]);
  });

  function key(target: Element, name: string) {
    target.dispatchEvent(
      new KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true }),
    );
  }

  function renderLinked(messages: ReturnType<typeof message>[]) {
    TestBed.configureTestingModule({ imports: [ChatPanel] });
    const fixture = TestBed.createComponent(ChatPanel);
    fixture.componentRef.setInput('messages', messages);
    fixture.componentRef.setInput(
      'moveIndexByPly',
      new Map([
        ['1-WHITE', 0],
        ['1-BLACK', 1],
        ['2-WHITE', 2],
      ]),
    );
    const selected = vi.fn();
    fixture.componentInstance.moveSelected.subscribe(selected);
    fixture.detectChanges();
    return { fixture, selected, element: fixture.nativeElement as HTMLElement };
  }

  it('keeps a focused move link on its own message when a live message arrives', () => {
    const first = message('a', 1, 'WHITE');
    const { fixture, selected, element } = renderLinked([first]);
    const link = element.querySelector<HTMLButtonElement>('button.msg-move')!;
    link.focus();
    expect(document.activeElement).toBe(link);

    fixture.componentRef.setInput('messages', [first, message('b', 1, 'BLACK')]);
    fixture.detectChanges();

    expect(document.activeElement).toBe(link);
    expect(link.getAttribute('aria-label')).toBe("Show the board after White's move 1");
    link.click();
    expect(selected).toHaveBeenCalledExactlyOnceWith({ index: 0, pointer: false });
  });

  it('keeps legacy messages without an id apart, even with equal text', () => {
    const legacy = { ...message('', 1, 'WHITE'), message: 'Same' };
    const { fixture, element } = renderLinked([legacy, { ...legacy }]);
    expect(element.querySelectorAll('.chat-message')).toHaveLength(2);
    fixture.componentRef.setInput('messages', [legacy, { ...legacy }, message('c', 2, 'WHITE')]);
    fixture.detectChanges();
    expect(element.querySelectorAll('.chat-message')).toHaveLength(3);
  });

  it('gives the move links one tab stop and moves between them with the arrow keys', () => {
    const { fixture, selected, element } = renderLinked([
      message('a', 1, 'WHITE'),
      message('b', 1, 'BLACK'),
      message('c', 2, 'WHITE'),
    ]);
    const links = () => Array.from(element.querySelectorAll<HTMLButtonElement>('button.msg-move'));
    const stops = () => {
      fixture.detectChanges();
      return links().map((link) => link.getAttribute('tabindex'));
    };

    expect(element.querySelector('.chat-scroll')?.getAttribute('tabindex')).toBe('0');
    // Newest first: the newest message's link is the tab stop.
    expect(stops()).toEqual(['0', '-1', '-1']);

    links()[0].focus();
    key(links()[0], 'ArrowDown');
    expect(document.activeElement).toBe(links()[1]);
    expect(stops()).toEqual(['-1', '0', '-1']);
    key(links()[1], 'End');
    expect(document.activeElement).toBe(links()[2]);
    key(links()[2], 'ArrowUp');
    expect(document.activeElement).toBe(links()[1]);
    key(links()[1], 'Home');
    expect(document.activeElement).toBe(links()[0]);
    expect(stops()).toEqual(['0', '-1', '-1']);
    expect(selected).not.toHaveBeenCalled();
  });

  it('tells pointer from keyboard activation and announces the position politely', () => {
    const { fixture, selected, element } = renderLinked([message('a', 1, 'WHITE')]);
    const link = element.querySelector<HTMLButtonElement>('button.msg-move')!;
    const status = () => element.querySelector('[role="status"]');
    expect(status()?.getAttribute('aria-live')).toBe('polite');
    expect(status()?.textContent?.trim()).toBe('');

    // Enter or Space on a button fires a click with detail 0.
    link.dispatchEvent(new MouseEvent('click', { bubbles: true, detail: 0 }));
    fixture.detectChanges();
    expect(selected).toHaveBeenLastCalledWith({ index: 0, pointer: false });
    expect(status()?.textContent?.trim()).toBe("Showing the board after White's move 1");

    link.dispatchEvent(new MouseEvent('click', { bubbles: true, detail: 1 }));
    expect(selected).toHaveBeenLastCalledWith({ index: 0, pointer: true });
  });

  it('keeps a focused move link in place when a live message is added above it', () => {
    const first = message('a', 1, 'WHITE');
    const { fixture, element } = renderLinked([first]);
    const scroll = element.querySelector<HTMLElement>('.chat-scroll')!;
    const link = element.querySelector<HTMLButtonElement>('button.msg-move')!;
    let linkTop = 50;
    vi.spyOn(scroll, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 300, 100));
    vi.spyOn(link, 'getBoundingClientRect').mockImplementation(
      () => new DOMRect(250, linkTop, 50, 16),
    );
    link.focus();

    fixture.componentRef.setInput('messages', [first, message('b', 1, 'BLACK')]);
    linkTop = 130; // the new two-line message above pushed the link below the transcript's edge
    fixture.detectChanges();
    expect(document.activeElement).toBe(link);
    expect(scroll.scrollTop).toBe(80);

    // Without focus in the transcript a live message leaves the scroll position alone.
    link.blur();
    scroll.scrollTop = 0;
    fixture.componentRef.setInput('messages', [
      first,
      message('b', 1, 'BLACK'),
      message('c', 2, 'WHITE'),
    ]);
    linkTop = 200;
    fixture.detectChanges();
    expect(scroll.scrollTop).toBe(0);
  });

  it('leaves modified arrow keys (browser shortcuts) to the browser', () => {
    const { element } = renderLinked([message('a', 1, 'WHITE'), message('b', 1, 'BLACK')]);
    const links = Array.from(element.querySelectorAll<HTMLButtonElement>('button.msg-move'));
    links[0].focus();
    for (const modifier of ['altKey', 'ctrlKey', 'metaKey', 'shiftKey']) {
      const event = new KeyboardEvent('keydown', {
        key: 'ArrowDown',
        [modifier]: true,
        bubbles: true,
        cancelable: true,
      });
      links[0].dispatchEvent(event);
      expect(event.defaultPrevented).toBe(false);
      expect(document.activeElement).toBe(links[0]);
    }
  });
});
