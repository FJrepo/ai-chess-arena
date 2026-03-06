# Third-Party Notices

This project distributes third-party software as part of its released artifacts.

## Stockfish

- Component: Stockfish chess engine
- Upstream project: https://stockfishchess.org/
- Source repository: https://github.com/official-stockfish/Stockfish
- License: GNU General Public License v3.0
- Included in:
  - released backend Docker image
  - local backend Docker builds

### Bundled version

The backend Docker image currently bundles the official unmodified Stockfish 18 release asset:

- Release tag: `sf_18`
- Release page: https://github.com/official-stockfish/Stockfish/releases/tag/sf_18
- Source tree for that release: https://github.com/official-stockfish/Stockfish/tree/sf_18
- Bundled binary asset: `stockfish-ubuntu-x86-64-avx2.tar`
- Asset download URL: https://github.com/official-stockfish/Stockfish/releases/download/sf_18/stockfish-ubuntu-x86-64-avx2.tar
- Asset SHA256: `536c0c2c0cf06450df0bfb5e876ef0d3119950703a8f143627f990c7b5417964`

### Distribution note

The backend image runs Stockfish as a separate external process via the UCI protocol. The Stockfish binary bundled in the image is an official upstream release asset and is not modified by this project.

The GNU GPL v3 license text for Stockfish is included in:

- `backend/licenses/stockfish/COPYING.txt`
- the released backend image under `/usr/share/licenses/stockfish/COPYING.txt`

The backend image also includes a Stockfish notice file at:

- `backend/licenses/stockfish/NOTICE.txt`
- `/usr/share/licenses/stockfish/NOTICE.txt` inside the image

If the bundled Stockfish version changes, update this file, the Dockerfile metadata, and the bundled notice files together.
