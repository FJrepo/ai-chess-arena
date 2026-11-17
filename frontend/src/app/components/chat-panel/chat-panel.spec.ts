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
      'Spicy',
    );
    fixture.componentRef.setInput('chatMode', 'MANDATORY');
    fixture.componentRef.setInput('banterLevel', null);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.chat-mode-label').textContent.trim()).toBe(
      '· Chat mandatory',
    );
  });
});
