# Third-Party Notices

This project distributes third-party software as part of its released artifacts.

## Stockfish

- Component: Stockfish chess engine
- Upstream project: https://stockfishchess.org/
- Source repository: https://github.com/official-stockfish/Stockfish
- License: GNU General Public License v3.0 or any later version (SPDX: GPL-3.0-or-later)
- Included in:
  - released backend Docker image
  - local backend Docker builds

### Bundled version

The backend Docker image currently bundles the official unmodified Stockfish 19 release assets, selected by image architecture:

- Release tag: `sf_19`
- Release page: https://github.com/official-stockfish/Stockfish/releases/tag/sf_19
- Source tree for that release: https://github.com/official-stockfish/Stockfish/tree/sf_19
- amd64 asset: `stockfish-linux-x86-64-universal.tar.gz`
- amd64 download: https://github.com/official-stockfish/Stockfish/releases/download/sf_19/stockfish-linux-x86-64-universal.tar.gz
- amd64 SHA256: `9defc0d4e55d49c65a6d042f3e571a39fcea499ade6dbe741b53b8c65e03611f`
- arm64 asset: `stockfish-linux-arm64-universal.tar.gz`
- arm64 download: https://github.com/official-stockfish/Stockfish/releases/download/sf_19/stockfish-linux-arm64-universal.tar.gz
- arm64 SHA256: `fe26cfd1d9db4c8af3d21e24d9ff34cacb31c1f940085a7583da11796f2bac01`
- Attribution: Copyright (C) 2004-2026 The Stockfish developers (see upstream AUTHORS).
- Corresponding source archive (including upstream build instructions): https://github.com/official-stockfish/Stockfish/archive/refs/tags/sf_19.tar.gz

Published images remain amd64-only; the Dockerfile also supports selecting the arm64 asset for source builds. Each image contains only its selected binary. Both upstream release assets include corresponding source and build instructions.

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

## Chess piece images (Cburnett set)

- Component: 12 SVG chess piece images, vendored as `frontend/public/pieces/cburnett/`, optimised with svgo (path data rewritten, comments and metadata removed, viewBox added); otherwise unmodified.
- Attribution: Colin M.L. Burnett, with later revisions by Wikimedia Commons contributors.
- Author: Colin M.L. Burnett (Wikimedia Commons user "Cburnett"), original uploads 2006-12-28 / 2007-01-03; later revisions on Commons by other contributors (symmetry, alignment and SVG validity fixes). We use the current revisions as of 2026-11-17.
- Source: Wikimedia Commons, files `File:Chess_klt45.svg`, `File:Chess_qlt45.svg`, `File:Chess_rlt45.svg`, `File:Chess_blt45.svg`, `File:Chess_nlt45.svg`, `File:Chess_plt45.svg` (white) and the matching `...dt45.svg` (black). [Example file page](https://commons.wikimedia.org/wiki/File:Chess_blt45.svg); the other eleven follow the same pattern. [Category: SVG chess pieces/Standard transparent](https://commons.wikimedia.org/wiki/Category:SVG_chess_pieces/Standard_transparent).
- Licence on Commons: the author's licence statement on each file page is `{{self|GFDL|migration=relicense|BSD|GPL}}`, i.e. the author offers GFDL, the BSD licence or the GPL at the licensee's choice (CC BY-SA 3.0 was added through the GFDL relicensing migration). **This project uses the BSD licence option.**
- Included in: the frontend build / released frontend Docker image. The notice also travels with the images as `pieces/cburnett/LICENSE.txt`.

### BSD licence text

"The author" is Colin M.L. Burnett.

```text
Copyright © The author

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
Neither the name of The author nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

This software is provided by The author and contributors "as is" and any express or implied warranties, including, but not limited to, the implied warranties of merchantability and fitness for a particular purpose are disclaimed. In no event shall The author and contributors be liable for any direct, indirect, incidental, special, exemplary, or consequential damages (including, but not limited to, procurement of substitute goods or services; loss of use, data, or profits; or business interruption) however caused and on any theory of liability, whether in contract, strict liability, or tort (including negligence or otherwise) arising in any way out of the use of this software, even if advised of the possibility of such damage.
```

Commons reference link for the template: http://opensource.org/licenses/bsd-license.php

## BusyBox secret initializer

Production Compose and its release setup asset reference the official `busybox:1.37.0` image for first-start secret generation. BusyBox is licensed under GPL-2.0-only; the image is pulled from Docker Hub and is not bundled in the application images. See the [BusyBox license](https://busybox.net/license.html), [1.37.0 source archive](https://busybox.net/downloads/busybox-1.37.0.tar.bz2) and [official image packaging](https://github.com/docker-library/busybox).
