import { downloadBlob } from './download';

describe('downloadBlob', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('downloads the exact blob and safe filename and releases the URL after the click', () => {
    vi.useFakeTimers();
    const createObjectURL = vi.fn().mockReturnValue('blob:export');
    const revokeObjectURL = vi.fn();
    vi.stubGlobal('URL', { createObjectURL, revokeObjectURL });
    const blob = new Blob(['Ω'], { type: 'text/csv;charset=utf-8' });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      expect(this.isConnected).toBe(true);
      expect(this.href).toBe('blob:export');
      expect(this.download).toBe('tournament-id.csv');
      expect(revokeObjectURL).not.toHaveBeenCalled();
    });
    downloadBlob(blob, 'tournament-id.csv');
    expect(createObjectURL).toHaveBeenCalledWith(blob);
    expect((click.mock.instances[0] as HTMLAnchorElement).isConnected).toBe(false);
    vi.runAllTimers();
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:export');
  });
});
