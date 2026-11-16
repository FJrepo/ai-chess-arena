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

## chesslib

- Component: chesslib Java chess library (`com.github.bhlangonijr:chesslib`)
- Version: `1.3.7`
- Source repository: https://github.com/bhlangonijr/chesslib
- License: Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0)
- Included in:
  - released backend Docker image (as a library jar of the Quarkus application)
  - local backend builds

### License notice

Copyright 2017 Ben-Hur Carlos Vieira Langoni Junior. chesslib is licensed under the Apache License, Version 2.0. You may not use it except in compliance with the License. Unless required by applicable law or agreed to in writing, it is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.

chesslib is used unmodified. If its version in `backend/pom.xml` changes, update this section.
