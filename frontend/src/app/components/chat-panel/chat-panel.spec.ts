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
});
