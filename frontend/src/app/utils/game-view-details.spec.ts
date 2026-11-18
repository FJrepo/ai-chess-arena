import { isBongcloudMove, liveMoveDetail } from './game-view-details';

const afterCapture = '4k3/8/3P4/8/8/8/8/4K3 b - - 0 3';
const selectedCapture = {
  modelId: 'test/model',
  isOverride: false,
  moveNumber: 3,
  color: 'WHITE' as const,
  san: 'exd6',
  fen: afterCapture,
};
const whiteHome = 'rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2';
const whiteWalk = 'rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPPKPPP/RNBQ1BNR b kq - 1 2';
const blackHome = 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2';
const blackWalk = 'rnbq1bnr/ppppkppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQ - 2 3';

describe('liveMoveDetail', () => {
  it('recognizes en passant for both sides by the actual board transition', () => {
    expect(liveMoveDetail('4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 3', selectedCapture)).toBe(
      'Google en passant. Holy hell!',
    );
    expect(
      liveMoveDetail('4k3/8/8/8/3Pp3/8/8/4K3 b - d3 0 3', {
        ...selectedCapture,
        color: 'BLACK',
        san: 'exd3',
        fen: '4k3/8/8/8/8/3p4/8/4K3 w - - 0 4',
      }),
    ).toBe('Google en passant. Holy hell!');
    expect(liveMoveDetail('4k3/8/3p4/4P3/8/8/8/4K3 w - - 0 3', selectedCapture)).toBeNull();
    expect(liveMoveDetail('4k3/8/8/3pP3/8/8/8/4K3 w - - 0 3', selectedCapture)).toBeNull();
  });

  it('excludes inherited, book, override, noncanonical and human moves', () => {
    const fen = '4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 3';
    for (const excluded of [
      { isInherited: true },
      { isBook: true },
      { isOverride: true },
      { isCanonical: false },
      { modelId: null },
    ]) {
      expect(liveMoveDetail(fen, { ...selectedCapture, ...excluded })).toBeNull();
    }
  });

  it('recognizes both early king walks and rejects a late or unrelated king move', () => {
    const white = { ...selectedCapture, fen: whiteWalk, san: 'Ke2', moveNumber: 2 };
    const black = { ...white, fen: blackWalk, color: 'BLACK' as const, san: 'Ke7' };
    expect(liveMoveDetail(whiteHome, white)).toBe('Bongcloud detected. Bold.');
    expect(liveMoveDetail(blackHome, black)).toBe('Bongcloud detected. Bold.');
    expect(isBongcloudMove(whiteHome, { ...white, moveNumber: 4 })).toBe(false);
    expect(isBongcloudMove(whiteWalk, white)).toBe(false);
    expect(isBongcloudMove(whiteHome, { ...white, isOverride: true })).toBe(false);
  });
});
