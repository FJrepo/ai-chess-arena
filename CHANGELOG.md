# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- Tournament setup and the API accept 1–20 attempts per move. Games with older zero or negative limits get one attempt.
- Attempt history attributes timeouts caused by provider retries to the provider, so they do not count against the model.
- Reliability and comparison count each played ply once, even in older games with duplicate moves. Spend includes every paid answer.

## [1.7.0] - 2026-11-17

Zero-config start: download two files, start with generated secrets, then connect OpenRouter via OAuth in the app instead of adding a key to `.env`. The Club Scoresheet theme brings a new look and board colours, while analytics are organised into four tabs. Choose a banter level, get a bracket when creating a tournament, and use the more compact setup page (round 3).

### Upgrade notes
- Back up the database before upgrading (see `docs/deployment.md`, *Backups and rollback*); migrations V21 and V22 run automatically on start.
- Connect OpenRouter (v1.7): existing `OPENROUTER_API_KEY` / `OPENROUTER_API_KEY_FILE` installs keep working and display as operator-managed (read-only in the UI). Rebuild/recreate backend and frontend with the updated Compose file (backend secrets mount becomes writable). Keep existing DB credentials and volumes. UI keys/claims are private files; no database migration or backfill, and persisted games remain unchanged.
- Keep `DB_PASSWORD` unchanged in an existing `.env`: an initialized PostgreSQL volume retains its password, and an explicit value wins for both database and backend. The init service generates persistent secrets in `secrets_prod` on first start and never overwrites existing files. On upgrades with an explicit `DB_PASSWORD`, the generated password file is unused for database authentication; removing the existing value would not change PostgreSQL's stored password. Retain/back up `secrets_prod` with `pgdata_prod`. Recreate services with the updated Compose file and images; no schema migration or backfill, and existing database contents remain unchanged.
- Flyway `V21__banter_level.sql` adds nullable, checked `tournaments.banter_level`; existing tournaments keep Original (`null`) and all existing prompt texts, versions and hashes. Existing games, moves, results and spend remain unchanged; no backfill. Rebuild/recreate backend and frontend; restore the pre-upgrade backup to roll back.
- Flyway `V22__creation_bracket_intent.sql` stores the original creation draw choice for safe retries. Existing tournaments, brackets, games and seeds stay unchanged; no generation backfill. Rebuild/recreate backend and frontend to activate creation-time brackets. Restore the pre-upgrade backup to roll back.

### Added
- [Design system](docs/design-system.md): the meaning and rules of the UI tokens (colours, typography, tables, components, board themes); the tokens in `styles.scss` stay the source of truth.
- Board colours in the game view: **Board** next to flip/export chooses Green (default), Blue-grey or Wood; the choice is saved in the browser ([how it works](docs/how-it-works.md#board-colours)).
- Connect OpenRouter with browser-bound, single-use PKCE S256 or a validated write-only key field, masked status, Test/Delete controls and local/LAN browser claiming. Missing-key Create, Run, Battle and Resume actions share a connection prompt.
- First-start production secret generation: persistent 0600 database password and instance key files, shared with the database read-only and backend writable after successful initialization. The instance key is reserved for future encryption/signing.
- Backend `DB_PASSWORD_FILE` and `OPENROUTER_API_KEY_FILE` support with trimmed file contents, explicit environment precedence and sanitized failures. Keys may also be connected in the UI after startup.
- Per-tournament Banter tone: Original, Polite, Playful or Spicy for Optional/Mandatory chat, with setup rule previews and a live game label. Changes apply next turn; Off ignores the retained tone and legacy full prompt overrides remain unchanged. Tone guidance combines with resignation and custom instructions; invalid API values return 400.

### Changed
- Tournament setup is more compact: a smaller header, the model search first with its filters below and the Add button next to the fields, all match settings in one row, and an estimate panel that leads with spend, runtime and games while the assumptions fold into a closed section. The participant count reads "4 participants · min. 2", and the Low risk band says when the runtime is open-ended because no move time limit is set.
- New app look ("Club Scoresheet"): paper-white light mode and warm charcoal dark mode, green as the chess and primary accent, logo blue for information, links and focus, Archivo headings, monospace numbers for moves, KPIs, ratings and costs, and ruled, striped tables. Table headers and footnotes are larger and dark-mode secondary text has more contrast. The OpenRouter banners and the connect callback page use the same styles; their behaviour is unchanged.
- OpenRouter credentials are optional at startup. Precedence follows Quarkus: system property, explicit environment, operator secret file, UI key, then none; UI changes and file rotation apply on the next request. The public catalog cache is independent of credentials. Release setup now downloads, starts, opens, then connects.
- Creating a tournament from setup now generates its bracket, with Bracket draw (Random by default) under Tournament Settings → Matches. The create API accepts `drawMode` (default ORDER) and `generateBracket`; only explicit `generateBracket: true` generates at creation; omitted, null or false retains draft creation for API clients. Repeated `clientRequestId` requests preserve the original choice and bracket, including recovery after interrupted generation. Existing drafts retain manual generation.
- Analytics page in four tabs, Overview, Play quality, Behaviour and Cost & reliability, instead of one long comparison tab; all tables and explanations stay, and `?tab=` links open a tab directly ([how it works](docs/how-it-works.md)).
- Make the two-file release-asset setup the README quick start, with latest-release download URLs, version pinning, and PowerShell and source-checkout instructions in the deployment guide.
- New production installs need no `DB_PASSWORD`; image overrides remain optional in release assets. Quick starts wait for healthy services with `docker compose up -d --wait`. Compose accepts an empty OpenRouter setting; connect in System after startup, or use an operator env value/file.

## [1.6.0] - 2026-11-17

Hands-off tournaments: auto-run a whole bracket with a concurrency and budget cap, an all-time Elo leaderboard, PGN/CSV/JSON export, cache-friendly prompts that roughly halved Sonnet's cost per answer in a live test, a two-file setup from release assets, a redesigned setup page and a new logo.

### Upgrade notes
- Back up the database before upgrading (see `docs/deployment.md`, *Backups and rollback*); new migrations run automatically on start.
- Back up before upgrading. Flyway `V20__tournament_auto_run.sql` adds run status (existing tournaments `OFF`), concurrency (default 2), nullable budget and pause reason. Existing games, results and spend remain unchanged; no backfill. Rebuild/recreate backend and frontend. Backend restarts pause active runs with “Backend restarted”; resume the run and its paused games explicitly. Restore the pre-upgrade backup to roll back.

### Added
- Tournament downloads in PGN, CSV and versioned JSON, plus complete single-game PGN with standard tags, termination details, opening/book markers, evaluation comments and opt-in escaped chat. CSV includes spend and move-quality summaries with spreadsheet formula protection; JSON includes settings, participants, bracket/game history, chat and attempt usage/cost summaries while excluding credentials, prompts and raw provider payloads. No migration or backfill required.
- Cross-tournament Leaderboard with all-time Elo per OpenRouter model id, provisional and moving-alias badges, bounded rating-history sparklines and an include-forfeits toggle. Ratings replay finished model-vs-model outcomes on each request through `GET /api/analytics/leaderboard`; no migration or backfill.
- GitHub Release setup assets from v1.6.0: `docker-compose.yml` defaults both application images to the release's version tag, alongside `env.example`. Both assets are attached when the immutable release is created and linked under Verification / Assets in its notes.
- Python 3.12 script tests in reusable CI, gating pull requests and releases with the same dependency installation and unittest discovery.
- Auto-run tournaments from the bracket page or `/api/tournaments/{id}/run`, with 1–8 concurrent games (default 2), optional USD budget, pause/resume, editable limits, spend and attention banner. Ready model games start in bracket order through the atomic start path; paused and human games need manual attention. Budget checks before each request/retry and after every attempt pause the run and its running games at the cap; in-flight answers may overshoot. Manual play without a run ignores run limits. Cancellation stops scheduling and restart recovery requires explicit resume.

### Changed
- Model requests now grow from a stable append-only history anchor per side instead of sliding every turn: at most 12 prior messages by default, re-anchoring to up to four messages in complete turns when exceeded. Original prompt/answer bytes are replayed, retries append unchanged, and every new turn still includes full PGN/book history and position. Anthropic and Google model IDs, including their `~` aliases, receive one OpenRouter `cache_control` breakpoint before the current turn only when the cached catalog advertises a valid cache-write price; other models use plain messages for automatic caching. Backend `openrouter.prompt-caching=false` restores the legacy six-message sliding window and plain messages; maximum/retained history limits are configurable. More history can mean more input tokens, discounted reads depend on provider cache hits, and prompt-affecting settings changes reset the prefix. Backend rebuild/recreation required; no migration or backfill, existing data unchanged.
- Shorten the README to a feature overview, move details into the docs and add documentation guardrails to keep it concise.
- New app logo, the "circuit knight": an original knight in the style of the board pieces, with circuit traces in the mane. The header mark is larger and follows the light/dark theme; the favicon is a simplified knight (SVG that follows the browser colour scheme, plus a `favicon.ico` fallback). The old `logo-chess-circuit.svg` is removed.
- Keep the v1.5.0 source-checkout quick start in the README; document the two-file 5-minute start in the deployment guide for v1.6.0 onward. The production image namespace defaults to `fjrepo`; explicit namespace/tag overrides and existing database volumes remain supported. The repository's `docker-compose.prod.yml` still requires an explicit image tag.
- Tournament setup page: the add-participant form comes first, and the tournament name, seeded lineup, readiness checklist and Create button sit together in the right column, with the estimate below it. The column stays sticky only when its measured height fits the window with space above and below; without ResizeObserver support, it uses normal page scrolling. Below 1200px, settings precede the lineup to match keyboard navigation. Participant reasoning-effort overrides remain visible in roster rows. Participant and shared custom instructions and the system rules are collapsed by default; collapsing participant instructions preserves the draft when adding. Long helper texts are now one line with details in a "?" tooltip. The estimate reads as a rough heuristic ("Tournament Estimate", "about $X", "can be higher with reasoning models"), and the risk bands are Low risk / Moderate risk / High cost.

### Fixed
- Stop the owning game loop before evicting retained conversations on tournament deletion, preventing a concurrent human-turn handoff from restoring deleted history. Refuse retention for missing/inactive games or terminal tournaments, and sweep stale entries on the existing ten-second scheduler tick even without auto-run. Backend rebuild/recreation required; no migration or backfill, persisted data unchanged.
- Keep cache-control rejection attribution when the plain fallback exhausts the original deadline: pause for a retryable provider problem instead of assigning a model timeout loss. Tournament deletion and terminal game transitions stop loops and evict retained conversations after commit; rollback preserves them. With prompt caching disabled, prune the retained backing history to the legacy six-prior-message window across human turns. Backend rebuild/recreation required; no migration or backfill, existing persisted history unchanged.
- Preserve per-side model exchanges across human-turn waits, reloading the committed board on continuation. Resolve prompt hashes each model turn so shared/participant instructions, templates and other rendered changes clear the affected side's history once. Pause/resume, cancellation, completion, loop failure and backend restart discard in-memory conversations. Explicit-cache 400/404 rejections retry once with plain messages inside the same deadline and disable markers for that exact model ID until restart; Gemma and models without catalog cache-write evidence receive plain messages. Backend rebuild/recreation required; no migration or backfill.
- Clarify the auto-run banner's current running-game count, concurrency limit and spend. Before starting, preview the selected limit and budget; while running or paused, retain the applied values and mark unsaved edits. Show Save limits only for running or paused runs and enable it only for valid changes; reverting edits disables Save again.
- Correct PGN per-move `TimeControl` (`1/N`), numeric bracket `Round` with separate label/series tags, and chat directive neutralization. Wrap movetext/comments below 80 columns. Export with lean scalar projections and cursor-based bulk reads instead of loading raw provider answers and querying attempts per game; JSON/CSV contracts and persisted data are unchanged.
- Reject invalid `includeForfeits` leaderboard query values with HTTP 400 and a JSON error. Only case-insensitive `true` or `false` are accepted; omitting the parameter still includes forfeits.
- Complete accepted terminal moves and eligible stored-evaluation adjudications before attempt budget checks stop a game loop, preserving results, series/bracket advancement and after-commit updates. Resignations retain the same completion precedence; cancellation still prevents advancement.
- Preserve budget input text so malformed amounts such as `1e` block Run, Resume and Save limits with a validation message. Only an explicitly empty field removes the cap.
- Generate and upload the release template explicitly as `env.example`, with one canonical download link in release notes, avoiding GitHub's renaming of dot-prefixed assets.
- Create the downloaded setup's `.env` with mode `0600` before editing on macOS/Linux; document keeping it private in the Windows user profile. Source-checkout `make setup` continues to enforce `0600` while preserving existing credentials.

## [1.5.0] - 2026-11-17

Variety and robustness: opening variety (standard, random book line or a fixed opening), optional win adjudication with missed-mate markers and a Conversion table, cancelling a tournament, a random bracket draw, prompt-caching metrics, drawn SVG chess pieces, Stockfish 19, and release images for amd64 and arm64 (Apple Silicon runs natively). Release notes now come from this changelog.

### Upgrade notes
- Back up the database before upgrading (see `docs/deployment.md`, *Backups and rollback*); migrations V17–V19 run automatically on start.
- Flyway `V19__opening_variety.sql` adds tournament opening settings (default Standard), immutable game opening metadata and `moves.is_book` (false for existing rows). Existing positions, history, results and spend stay unchanged; no backfill. Back up before upgrading; rollback requires restoring the backup because older versions do not distinguish book moves in analytics.
- Flyway `V18__adjudication.sql` adds `tournaments.adjudication_enabled` (not null, default false). Existing tournaments keep adjudication off; existing results and evaluations stay unchanged. Missed mates and conversion metrics derive from stored evaluations without backfill. Back up before upgrading.
- Flyway migration `V17__cache_tokens.sql` adds nullable `cached_prompt_tokens` and `cache_write_tokens` columns to `move_attempts` on backend startup. Back up before upgrading. Existing rows and costs remain unchanged; historical cache usage is not backfilled.

### Added
- Per-tournament opening variety: Standard (default), Random book or Fixed from 42 original short lines with ECO/name/SAN. Regular color-reversed series partners share the same opening; tiebreaks may choose anew. Stored book moves appear in PGN/prompts and the game UI but never count as model moves, attempts, costs, quality, talkativeness, Conversion or adjudication-window plies. Opening changes affect newly created games only. Includes setup controls, book markers and the read-only `/api/openings` catalog.
- Cancel a tournament from its bracket page or `POST /api/tournaments/{id}/cancel`: unfinished games end as Cancelled without a result, running loops stop, and finished results, history and reported spend stay. Cancelled games cannot resume or advance the bracket and are excluded from analytics; finished games still count. Repeated cancellation returns 409. No migration or backfill is needed.
- Optional tournament win adjudication for model-vs-model games: at the start of a turn, ten fully evaluated consecutive plies with forced mate or at least +1000 cp for the same side end the game as a decisive win (`ADJUDICATION`). Includes overrides; missing evaluations never block play. Default off, changes apply next turn. Setup switch, game result panel and normal bracket/series progression.
- Always-on missed-mate move markers and live quality fields; compact per-model Conversion table with winning positions reached (at least 90% winning chances), converted wins, adjudicated wins, thrown-away games and missed mates, with n/a for missing evaluation data.
- Prompt caching metrics: nullable OpenRouter cache-read and cache-write tokens persisted for every attempt with usage, exposed in the attempts API, and a compact per-model *Prompt caching* analytics table for finished, non-bye games. The table shows prompt tokens, cache reads, hit rate, writes and estimated USD read savings using current catalog prices; incomplete counters and unknown prices show n/a. OpenRouter's reported cost remains authoritative. Measurement only: no prompt changes or `cache_control`.
- Bracket draw choice on the tournament page: Random draw (UI default) or In added order, with stored seeds shown after generation. Random draws shuffle once and reassign seeds so byes and Higher seed advances follow the drawn order. The bracket-generation API accepts an optional `drawMode=ORDER|RANDOM` query parameter and defaults to ORDER for existing clients; repeated generation still returns 409. No migration or backfill is required.

### Changed
- Build GitHub Release notes from the released version's CHANGELOG entry: highlights, upgrade notes first, then the remaining sections in changelog order. Verified image index digests and a Full Changelog link appear at the bottom; automatic PR lists are disabled. Tag validation rejects missing changelog entries before CI or image builds.
- Publish backend and frontend release images as multi-arch OCI indexes for `linux/amd64` and `linux/arm64`, including Apple Silicon. Maven and Angular compile on the native builder; release checks verify both platforms, backend license files and labels per platform, and an emulated ARM64 Stockfish UCI handshake before promoting full indexes to version and `latest` tags.
- Bundle Stockfish 19 (`sf_19`) using checksum-pinned universal `.tar.gz` assets selected by Docker target architecture (amd64 or arm64). The local installer now defaults to Stockfish 19 on Linux and macOS. Existing stored evaluations are unchanged; no migration or backfill is required.

### Fixed
- Preserve the backend Dockerfile's `MIT, GPL-3.0-or-later` licence label in release images instead of overriding it with the repository's MIT licence, so Stockfish licence checks pass. Frontend and other generated OCI labels are unchanged.
- Start/resume model prompts name the final persisted move, including the last book ply, and restore only that ply's message; missing move context no longer prints `null`. Resignation eligibility and its prompt hint count each side's completed non-book moves, first allowing resignation on its own tenth played move. No migration or backfill is needed.
- Keep repository-relative documentation links and images working in GitHub Release notes by pinning them to the released tag. Preserve used Markdown reference definitions from anywhere in the changelog and keep fenced examples intact when extracting and demoting headings.
- Replace font-dependent Unicode chess pieces with Cburnett SVG images on the board, capture strips and promotion picker. Board images announce piece colour, name and square; captured pieces and promotion buttons retain accessible names. Vendored images include the BSD licence notice.
- Game view preserves the selected historical position during background refreshes, including committed-answer notifications and reconnects. Viewers at the latest position still follow new moves, and late discarded answers still update attempts and spend after cancellation. No migration or backfill is needed.
- Cancellation remains final across concurrent tournament settings and participant effort writes; all existing-tournament mutations use the tournament row lock and fresh state. Cancelled and completed tournaments reject settings changes with 409. The bracket ignores obsolete polls after cancellation, open games refresh attempts and spend when a late paid answer commits, and terminal game updates close promotion pickers and prevent stale human move submissions. No migration or backfill is needed.
- Adjudication, move quality (including missed mates) and Conversion use one canonical move per game/move number/color, keeping the earliest `created_at` and breaking ties by `id`, as talkativeness already does. Legacy duplicate rows can no longer inflate the ten-ply adjudication window, change the previous ply's evaluation or invent winning positions. Live quality updates follow canonical plies. Stored rows and completed results are unchanged; no migration or backfill is needed.
- Stockfish cleanup terminates owned child processes before the engine so inherited output pipes do not leave protocol and stream-closing threads blocked after timeout or shutdown. Stream closing stays off callers.
- Concurrent catalog lookups now check freshness and return model data from the same snapshot, rechecking after acquiring the refresh lock. A caller no longer returns an earlier empty or expired catalog when another caller has published a fresh one; the 60-second failure back-off and stale-data reuse are preserved.
- Analytics comparisons use one cached catalog price snapshot for all model rows and refresh prices in the background, avoiding repeated synchronous OpenRouter timeouts. Failed catalog refreshes back off for 60 seconds and keep stale prices when available; missing prices leave estimated prompt caching savings as n/a.
- Bound Stockfish UCI initialization (10 seconds by default) and evaluations (30 seconds) with configurable deadlines, cancellable shutdown and no-score queue draining after timeout. Report recovery as unavailable until `uciok`. Correct Stockfish licensing to `GPL-3.0-or-later` and compare release image version, source and licence labels separately against exact expected values.
- An engine exit, EOF or broken pipe discards the interrupted evaluation and returns no score. The next queued evaluation attempts one engine restart without replaying the failed input; requests arriving during the replacement handshake wait in the queue and run after recovery. Failed restarts leave evaluation unavailable until backend restart. Mate scores remain normalized to White's perspective for both colors.

## [1.4.0] - 2026-11-16

Personality and honest play: move quality (blunders, mistakes, inaccuracies), chat modes with a talkativeness metric, optional resignation, reasoning effort per model, a new model picker, and no time limit by default. Also fixes models getting an outdated move list in their prompt (all earlier versions), writes and live updates failing on any address but localhost:4200, and a rare game-loop commit failure.

### Upgrade notes
- Migration V15 runs automatically: it adds the nullable columns `tournaments.reasoning_effort`, `tournament_participants.reasoning_effort` and `move_attempts.reasoning_effort` (each limited to `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max`). Existing tournaments and participants get no level, so every model keeps its own default and requests stay exactly as before; earlier attempts record no level.
- Migration V14 runs automatically: it adds `games.status_revision` (0 for existing games), a counter of status changes used to order live updates.
- Migration V13 runs automatically: it adds `tournaments.resignation_allowed` (false for every existing tournament, so they keep playing every game to the end).
- Migration V12 runs automatically: it adds `tournaments.chat_mode` (backfilled from `trash_talk_enabled`: false → `OFF`, true → `OPTIONAL`) and `moves.chat_mode`, the mode that applied to each model move. Existing moves are backfilled from the strongest evidence: a `-silent` prompt version → `OFF`; a stored message for the move → `OPTIONAL`; any other default prompt version (`v1`, `v2`, with or without `+custom-instructions`) or none → `OPTIONAL`, because a game with chat switched off always got the `-silent` prompt; only moves with a legacy custom template fall back to the tournament's current mode (`OPTIONAL` outside a tournament). Human moves and overrides stay empty. `trash_talk_enabled` stays for now and is kept in sync (false only for `OFF`); a later release drops it.
- Back up the database before upgrading (see *Backups and rollback* in [docs/deployment.md](docs/deployment.md#backups-and-rollback)). Rolling back to a version before 1.4 requires restoring that backup: an older backend still starts on the migrated database, but it only writes `trash_talk_enabled` and stores moves without a chat mode, so the data is inconsistent when you upgrade again.
- The frontend now answers only to `localhost`, `127.0.0.1` and `[::1]` (any port) and returns 421 for other host names. If you open the UI by a LAN host name or IP, add it to `ALLOWED_HOSTS` in `.env` (space-separated, e.g. `ALLOWED_HOSTS="chess-server 192.168.1.20"`) and recreate the frontend. Network exposure remains unsupported.

### Added
- Model picker in tournament setup: one *Model* field that suggests models while you type. Every word must match the model's name, id or provider, in any order ("anthropic sonnet", "sonnet 5.5", "gpt 6"); "-", "." and spaces are interchangeable. Suggestions are grouped by provider and show the release date, input/output price per 1M tokens, context window, a *Reasoning* marker and the Artificial Analysis Intelligence Index ("AA 61.2") when known. Filters for provider (featured providers first), *Reasoning only* and *Free only*, and sorting by *Newest* (default), *Cheapest* (input + output price) or *Intelligence Index*; models without the value go last. Arrow keys and Enter select; editing or clearing the field clears the selection.
- A details card for the selected model: release date, input, output and cache-read price per 1M tokens, context window, max output tokens, reasoning (supported, always on or optional, effort levels), Intelligence Index (by Artificial Analysis, via OpenRouter) and a short description; missing values show "n/a".
- `GET /api/models` returns more per model, all null (or an empty list) when OpenRouter does not provide them: `releasedAt` (YYYY-MM-DD), `cacheReadPricePerMillion`, `maxCompletionTokens`, `reasoningSupported`, `reasoningMandatory`, `reasoningEfforts`, `intelligenceIndex` (Artificial Analysis Intelligence Index from the OpenRouter catalog) and `description` (at most about 400 characters). The response also lists the configured `featuredProviders`.
- `GET /api/models?limit=0` returns every matching model without a cap; the default (120) and the maximum for other values (300) are unchanged.
- OpenRouter's moving "latest" aliases (ids starting with "~", such as `~anthropic/claude-sonnet-latest`) belong to their provider (`provider` is `anthropic`, not `~anthropic`) and keep their full id. The picker lists them after the provider's concrete models, marks them *Latest alias*, and the details card notes that OpenRouter routes them to the provider's newest model, so results can't be tied to a fixed model version.
- A model with an out-of-range `created` timestamp gets no release date instead of breaking the model list and model validation on tournament create.
- Move quality: every move with a Stockfish evaluation before and after it is judged by the drop in the mover's winning chances, with the win-probability model Lichess uses (centipawns capped at ±10 converted with `2 / (1 + exp(−0.00368208 · cp)) − 1`, a mate counts as a certain win or loss). A drop of at least 0.1 / 0.2 / 0.3 on the −1…+1 scale (5 / 10 / 15 percentage points of winning chances) is an inaccuracy `?!`, a mistake `?` or a blunder `??`. The centipawn loss per move is the mover's evaluation drop, capped at 1000 (a mate counts as ±1000). The start position counts as equal. Moves without both evaluations (Stockfish unavailable or still running), operator overrides and forced moves (the only legal move) are not judged and are left out of every average. Derived on read from the stored evaluations; no migration.
- Game view: the move list and the game feed annotate moves with `?!`, `?` and `??` in their colours, with a tooltip such as "Blunder: winning chances 74% → 22% (−6.2)" (the mover's winning chances and the loss in pawns); forced moves and overrides say they are not judged. Under each player: average centipawn loss (ACPL) and the `??` / `?` / `?!` counts. Both update live as evaluations arrive.
- API: moves in `GET /api/games/{id}` carry `quality` (`judgement`, `centipawnLoss`, `winChanceBefore`, `winChanceAfter`, `excluded`; null until both evaluations are known) and the game carries `qualitySummary` with `analyzedMoves`, `averageCentipawnLoss`, `inaccuracies`, `mistakes` and `blunders` per side. The live `evaluationUpdate` message carries `qualities` (the evaluated move's and the next move's, whose "before" it is) and the updated `qualitySummary`; the `move` message is sent before Stockfish runs and has no quality.
- Analytics: a *Move Quality* table under the model comparison with ACPL, blunders per game, the `??` / `?` / `?!` counts and the number of judged moves per model, from finished games only (byes excluded), with a one-line explanation. Blunders per game counts the games in which the model has at least one judged move. Comparison API rows carry `analyzedMoves`, `analyzedGames`, `averageCentipawnLoss`, `inaccuracies`, `mistakes`, `blunders` and `blundersPerGame`.

- Chat mode per tournament: *Off* (models are told to stay silent; messages are dropped, as with trash talk switched off before), *Optional* (the default and the previous behaviour: models may send a message) or *Mandatory* (the prompt asks for one short message, at most about 200 characters, with every move). In *Mandatory* a missing or empty message is recorded for the talkativeness metric but never rejects the move, costs an attempt or counts against reliability. The mandatory rules have their own prompt version `v2-chat-required` (`v2+custom-instructions-chat-required` with custom instructions): rule 4 makes `message` required, rule 5 asks for the short message (banter welcome, stay civil, no revealing plans), the response format says "short message", and the turn prompt ends with "Respond with your move and a short message as JSON". *Off* and *Optional* keep their exact text and versions (`v2-silent`, `v2`), so prompt hashes of existing tournaments do not change. A legacy custom system prompt is used as is and cannot ask for a message, so for such a model *Mandatory* counts as *Optional*.
- A mode change applies to running games from their next turn: both system prompts are rebuilt, and every model move stores the mode that applied to it (`MoveDto.chatMode`).
- Setup page: a compact *Chat* control (Off / Optional / Mandatory, Optional by default) with a one-line hint for the selected mode replaces the *Trash Talk Enabled* switch; presets use *Optional*, and the *System Rules* preview and template version follow the selected mode. The game view's chat panel shows the tournament's chat mode next to "Newest first" and an empty-state text that fits it; the live turn update of each model turn (`gameStatus`) carries the tournament's configured `tournamentChatMode`, so a mode changed during play shows up without a reload.
- `GET /api/config/prompt-template` also returns `chatModes` with the rules, version and hash of every chat mode.
- Analytics: a *Talkativeness* table per model with the talk rate (share of its moves with a non-empty message) and the average message length in characters, separately for chat *Optional* (the model's own choice) and *Mandatory* (compliance), "n/a" for a mode without moves, and a one-line explanation. Same scope as the other tables: model moves in finished, non-bye games; moves made with chat *Off* are left out. Derived from the stored messages joined to moves by game, move number and colour; a ply stored twice (possible in databases from before V8) counts once, with its first move and first message. Comparison API rows carry `optionalChat` and `mandatoryChat` with `moves`, `messages`, `talkRate` and `averageMessageLength`.
- Resignation per tournament (off by default): with *Allow resignation* in the setup page (next to *Chat*; API `resignationAllowed`), models may answer `{"resign": true, "message": "..."}` instead of a move from their own 10th move on (fixed minimum). The system prompt gets rule 9, which says that both players may resign from their 10th move on, so the opponent can resign too, stated neutrally as a rule; from move 10 the turn prompt adds "You may resign instead of moving (rule 9)." Prompt versions get the suffix `-resign` (`v2-resign`, `v2-silent-resign`, `v2-chat-required-resign`, with `+custom-instructions` before the suffixes); prompts without resignation keep their exact text, versions and hashes. Legacy custom system prompts are not offered resignation. A change applies to running games from their next turn.
- An allowed resignation ends the game as COMPLETED with result reason `RESIGNATION` for the opponent, counts like a checkmate win in Best-of-N series and tiebreaks, stores the message as last words under the chat-mode rules, and is recorded as attempt outcome `RESIGNED` with tokens and cost. A move sent along with `"resign": true` is ignored. A resignation that is not allowed (not offered, or before move 10) plays a legal move sent along and otherwise is an invalid answer (`RESIGN_NOT_ALLOWED`), retried with a short corrective message and counted against reliability like an illegal move. Failed attempts before an allowed resignation count in both reliability views (comparison and `GET /api/analytics/reliability`) as one turn with that many retries; a resignation on the first attempt changes nothing.
- Game view: "Black resigned; White wins" and a panel with the resigning side's winning chance at that moment ("at 46% winning chances", left out while Stockfish's evaluation is missing), its last words and the opponent's last message before it; the bracket notes who resigned. `GET /api/games/{id}` carries `resignation`; `GET /api/config/prompt-template` carries `resignationChatModes`.
- Reasoning effort per tournament, with an optional override per participant: OpenRouter's `reasoning.effort` (`none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max`). Some reasoning models think without end at their default: in a test tournament one model with mandatory reasoning and default effort `max` spent 29,998 reasoning tokens on a single move and was cut off at a 30,000-token output limit, and another exceeded 16,384. A lower level makes such models faster and cheaper; raising the output limit cannot fix it. *Model default* (the default) sends nothing, so requests are unchanged.
  - Setup page: a *Reasoning effort* select (*Model default*, then the levels) under *Moves* with the hint "Lower effort makes heavy thinkers faster and cheaper; some models think without end at their default", and a per-participant select that offers only the levels the selected model lists in the catalog (*Tournament setting* by default; "n/a" for models without effort levels). The participant list shows an override. Presets use *Model default*.
  - The request carries `"reasoning": {"effort": "<level>"}` only when a level applies (the participant's override, else the tournament's) and the cached OpenRouter catalog says the model supports reasoning with effort levels. A level the model does not list is sent as the nearest listed one (order none < minimal < low < medium < high < xhigh < max; a tie goes to the lower level; `none` only when asked for, since it switches reasoning off, and never for models with mandatory reasoning) and the mapping is logged. Models without reasoning, or whose catalog entry lists no effort levels, get nothing. With the catalog unavailable, or a model it does not list, the level is sent as is.
  - Every attempt records the level actually sent (`move_attempts.reasoning_effort`, `reasoningEffort` in `GET /api/games/{id}/attempts`; null when none was sent).
  - Changes apply from the next turn of running games, like the other settings. A participant's override can be changed at any time, also after the bracket was generated and while a game is paused: `PUT /api/tournaments/{id}/participants/{pid}` with `{"reasoningEffort": "low"}` (`null` follows the tournament's level again; omitted keeps it; other fields are rejected; 404 for a participant of another tournament).
  - Pause banner: when an answer was cut off at the output-token limit, the game view offers a reasoning-effort select for the cut-off side's participant next to the limit, with the levels its model supports (every level while the catalog cannot tell) and `low` (or the nearest supported level) pre-selected unless the side already asks for less. *Save and resume* saves the limit, then the participant's override, then resumes the game. A cut-off answer that was mostly reasoning now says "Raise the limit or lower the reasoning effort and resume."
  - If OpenRouter rejects a request that carried a level (HTTP 400 or 422), the game pauses as before and the reason adds: the request asked for reasoning effort "…"; if the model does not accept it, choose another level for the participant (or *Model default* for the tournament) and resume.
  - Game view: next to each side's model id, "· effort low" when the side asks for a level (the participant's override or the tournament's).
  - The model details card shows the model's *Default effort* (e.g. `max`), "n/a" when the catalog does not say.
  - API: `POST`/`PUT /api/tournaments` accept `reasoningEffort` (a level, case-insensitive, or `default` for each model's default; anything else is a 400; omitted keeps the model default on create and the current level on update); participants on create accept `reasoningEffort` (a level, or null to follow the tournament's; not for human participants). `TournamentDto`, `ParticipantDto`, `MoveAttemptDto` carry `reasoningEffort` (lower case, omitted when null), `GameDto` carries `whiteReasoningEffort` and `blackReasoningEffort`, and `GET /api/models` adds `defaultEffort`.
- Analytics: a *Resignations* table per model with resignations, "resigned while not losing" (at least 35% winning chances in the position given up, by the move-quality win-probability model; resignations without an evaluation are not judged) and wins by resignation. Finished, non-bye games, model sides only. Comparison API rows carry `resignations`, `resignationsEvaluated`, `resignationsWhileNotLosing` and `wonByResignation`.

### Changed
- The final v1.4.0 default is **no time limit per move** on the setup page, in every preset, for API creates with omitted/null `moveTimeoutSeconds`, and for games outside a tournament. This supersedes the earlier 120-second release-candidate default: four of seven games in a real candidate tournament with that limit ended by forfeit on time. Enabling the setup switch prefills 120 seconds; explicit limits remain hard deadlines. API `0` still removes the limit, 10–3600 seconds remains valid, and omitted/null on update keeps the stored value. Existing tournaments keep their limits; no migration. The 10-minute per-request safety cap still retries/pauses provider problems rather than forfeiting.
- Output-token limits accept 128–262144 (previously 128–32768), with a default of 16,384 instead of 4,096 for new tournaments and `game.default-max-tokens`. Quick Start Arena and Balanced Ladder use 16,384; Series Showcase uses 32,768. Reasoning answers were often cut off at the old maximum. Requests clamp `max_tokens` to the cached catalog's `top_provider.max_completion_tokens` when known and log reductions; unknown models or limits keep the configured value. The truncation pause control supports the new maximum. Cut-off attempts record and report the effective request cap, explain when the model's own maximum prevents raising it (suggesting lower reasoning effort when available), and bound new pause-control suggestions to the known model maximum without lowering the current tournament limit. Setup and README explain that OpenRouter checks the credit balance against `max_tokens` × output price before requesting, so a very high cap on an expensive model can fail on a low balance even if actual usage would be much smaller. Existing tournament token limits are unchanged.
- Setup page: below 90 seconds per move, the field warns that reasoning models often need more than a minute and running out of time forfeits. The preview's *Move Pace* reads *No limit* by default, *Tight* below 90 seconds, *Balanced* at 90–180 seconds or *Generous* above 180 seconds. The runtime estimate assumes a typical 30-second answer with an open upper bound when unlimited. With a limit it uses the shorter of 30 seconds and a quarter of the limit for likely runtime, and half the limit for the upper estimate. No limit adds no risk surcharge; bracket size, series length, estimated runtime and pricing still affect the rating.
- API compatibility for chat modes: `POST` and `PUT /api/tournaments` accept `chatMode` (`OFF`, `OPTIONAL`, `MANDATORY`, case-insensitive; any other value, including an empty one, is a 400). The older `trashTalkEnabled` is still accepted: on its own, false means `OFF` and true means `OPTIONAL`; when both are sent, `chatMode` wins; without either, create uses `OPTIONAL` and update keeps the mode. `TournamentDto` carries the new `chatMode` and still `trashTalkEnabled` (true for `OPTIONAL` and `MANDATORY`).
- The setup page loads the whole OpenRouter catalog (all text-output models) once and searches it in the browser. The separate *Search Models* field, the *OpenRouter Model* dropdown and the *Show all models* switch are gone, and no model is silently left out by a result cap anymore.
- `GET /api/models` reports a free model's prices as `0` instead of `null`, so free models count as priced in the setup estimate; unknown and variable prices (OpenRouter's `-1`) stay `null`.

### Fixed
- Effort-only recovery from a cut-off answer preserves the tournament's output-token limit when it exceeds the model maximum (for example, 32,768 for the tournament and 8,192 for the model). The pause banner keeps the tournament value visible, explains the model cap, and skips the tournament update unless the field changes; explicit limit edits still save before the participant's reasoning-effort override and resume.
- A game could pause with "The game loop stopped unexpectedly (ArcUndeclaredThrowableException); resume to retry" and lose the move just played (rare, timing-dependent; the backend log shows ARJUNA012094 "multiple threads active" and "Enlisted connection used without active transaction"). Sending a live update inside a transaction (a move is announced in the transaction that records it) carried that transaction over to the send's completion callback on the WebSocket event loop. When that callback ran just as the transaction committed, the commit failed and the move was rolled back. Live update sends no longer carry a transaction. The fault existed since the first release; resuming the game retried the move.
- Game status updates over the live connection (game over by checkmate, draw, forfeit or resignation, pause, start) and evaluation updates are sent only after the change is committed. Before, they went out from inside the transaction, so a client that reloaded the game on such an update could still read the old state. Status updates sent after their commits can arrive out of order (a delayed pause update after the resume that followed it), so every status change now raises a per-game status revision in the same transaction; status-change updates (`gameStatus` with `statusRevision`) and `GET /api/games/{id}` carry it. The game view ignores an update or a reload with an older revision than one it has seen (keeping the status and asking again a few times; a status change announced while the game is first loading is kept and applied when it is newer than the loaded game; per-turn updates, which carry no revision, never change the status and only update a running game), never replaces a finished game with a reload that still shows it running, and keeps an evaluation that arrived live when a reload does not have it yet.
- Models were given an outdated move list in their turn prompt. The *PGN* line stayed at its value from when the game was started or resumed: for a new game it said "(starting position)" on every move, after a resume it listed only the moves up to the resume. The FEN, the board diagram and the legal moves in the same prompt were current, so models could still play legal moves, but without the game's move history. Every game played with an earlier version, back to the first release, was played under that handicap; keep this in mind when comparing results across versions. The cause: the game loop kept the game and tournament it loaded first for the whole game instead of reading the moves, costs and settings that were saved meanwhile. Each turn now reads them fresh, which also fixes:
  - the live game cost shown to viewers at the start of each turn, which stayed at its value from the start or resume of the game (the stored total and the cost after the game were correct);
  - tournament settings changed while a game is running (*max retries*, *max output tokens*, the time limit per move) now apply from the next turn instead of only after a pause and resume. A changed chat mode already applied from the next turn and still does; the system prompts rebuilt for it now also use the tournament's current custom instructions.
- An answer with `"message": null` no longer stores and shows the text "null" as a chat message.
- Creating tournaments, starting games and every other change (POST/PUT/DELETE) failed with an empty 403 when the Docker stack was opened on any address other than `http://localhost:4200`, for example after changing `FRONTEND_PORT`, or via `127.0.0.1`, a hostname or an IP. Live updates (`/ws/games`) were rejected the same way. The bundled nginx dropped the port from the `Host` header it forwards to the backend, so the backend's CORS filter did not recognise the browser's request as same-origin. nginx now forwards the `Host` header unchanged, so the app works on any port and on every allowed host name (see *Security*) without adding that address to `quarkus.http.cors.origins`. Other origins are still rejected.
- The release smoke test runs the stack on a non-default port and checks that a same-origin POST and the WebSocket handshake through the frontend proxy are not rejected, and that requests for an unknown host name are.

### Security
- DNS rebinding is blocked: the frontend's nginx serves the UI, `/api` and `/ws` only for the host names `localhost`, `127.0.0.1`, `[::1]` and those listed in the new `ALLOWED_HOSTS` setting, and answers 421 Misdirected Request for any other name. Before, a web page whose domain was made to resolve to 127.0.0.1 could read data through `GET` requests, and on port 80 also change it; with the `Host` fix above it could have changed data on any port. A request whose `Host` header names a different host than its absolute-form request line gets 421 too, so a foreign `Host` cannot reach the backend that way. Invalid `ALLOWED_HOSTS` entries (with a scheme, a port, commas, a bare `*` or a wildcard in the middle, malformed IPv6 and so on) stop the frontend container at start with a clear message.
- The frontend service has a container healthcheck in both Compose files.

## [1.3.1] - 2026-11-16

Patch release: the per-move time limit is now optional and, when set, actually enforced.

### Upgrade notes
- Migration V11 runs automatically: it makes `tournaments.move_timeout_seconds` nullable (null = no time limit per move). Existing tournaments keep their values.
- The move timeout is now enforced: models that used to answer after the limit (for example slow reasoning models in a 30-second preset) now forfeit on time. Switch the limit off or raise it for such tournaments.

### Fixed
- The move timeout is enforced as a hard deadline for the whole answer, from the start of the turn to the last byte of the response, across all attempts of the turn. Before, it only limited the time until OpenRouter started its response, so a model that took 45–70 s per move under a 30-second limit was never timed out while the UI timer ran down to zero. Running out of time forfeits on time, unless an OpenRouter problem happened earlier in the same turn (then the game pauses, as before). Retries get only the time that is left (no grace period), and an OpenRouter error status whose body arrives too slowly stays a provider problem instead of a model timeout.
- The pause reason for an answer cut off at the output-token limit ends with a full stop.
- The configured fallback output-token limit is 4096 as documented (was still 500); unused `game.default-*` properties removed.

### Changed
- The time limit per move is optional per tournament: the setup page has a *Limit time per move* switch (on by default and in every preset) with the seconds field shown while it is on, and the estimate treats runtime without a limit as open-ended (it assumes about 30 s per answer). Without a limit the game view shows whose turn it is without a countdown.
- API: `moveTimeoutSeconds` on create and update accepts `0` for "no time limit" (stored as null) and otherwise 10–3600 seconds (400 for other values); omitting it or sending null keeps today's meaning (create: 60 seconds, update: unchanged). `TournamentDto.moveTimeoutSeconds` is null for a tournament without a limit, and live turn updates then carry no `turnDeadlineAt`.
- Every model request, with or without a move time limit, is bounded by a technical safety cap (`openrouter.request-safety-cap`, default 10 minutes). Reaching it counts as an OpenRouter problem (retried, then the game pauses), never as the model's timeout.

## [1.3.0] - 2026-11-16

"Trustworthy results": the app no longer blames models for its own limits, keeps every model attempt as evidence, scores reliability fairly, and gets a desktop polish of every page.

### Upgrade notes
- Migration V10 runs automatically: it adds the `move_attempts` table and `tournaments.max_output_tokens` (default 4096) and widens `games.total_cost_usd` to 8 decimal places. Existing tournaments get the 4096-token default.
- Reasoning models can now use up to the tournament's *Max output tokens per answer*, which can raise cost per move compared with the old fixed 500-token cap; lower the limit per tournament if needed.
- The API field `maxRetries` keeps its name but is labelled *Max attempts per move* (it counts the first attempt).

### Added
- Tournament setting *Max output tokens per answer* (`maxOutputTokens`, 128–32768, default 4096) on create and update, in the setup page and its presets. It is sent to OpenRouter as `max_tokens` and includes reasoning tokens.
- Every model attempt is stored in the new `move_attempts` table (migration V10), including illegal, unparseable, empty, cut-off, timed-out and provider-failed answers, and legal answers discarded because the position or game status changed meanwhile (`DISCARDED`), with tokens, reasoning tokens, cost, finish reason and the raw answer. Only illegal, unparseable, empty and timed-out answers count as the model's failures; cut-off answers are listed separately in the game feed.
- `GET /api/games/{id}/attempts` returns a game's attempts ordered by move number; the game feed shows failed attempts per move, and plies that ended without a move.
- Live retry events carry `outcome` and `proposedMove`; the retry banner names the rejected move.
- When a game is paused because an answer was cut off at the output-token limit, the game view offers a *Max output tokens per answer* field (prefilled with double the current limit) and a *Save and resume* button that updates the tournament and resumes the game.
- Analytics: a *First-try legal* column (share of turns answered with a legal move on the first attempt) in the reliability and comparison tables, a matching sort option, and a one-line explanation of the score under the reliability table. The reliability and comparison API rows carry `firstTryLegalRate`.
- Game view: the board marks the squares of the move being viewed with the classes `last-move-from` and `last-move-to` (derived from the stored positions, including castling, en passant and promotion, and only when the two positions are exactly one legal-looking move apart); there is no mark before the first move. The squares are not styled yet.
- Games in the API (`GameDto`) carry `whiteParticipantId`, `blackParticipantId` and `advancedParticipantId` (null for a side without a participant, such as a bye).

### Changed
- Desktop layout and visual polish for every page (tournament list, setup, bracket, game view, analytics, system status) in light and dark themes, with readable status labels, a theme-aware surface shadow and fluid board squares; the UI targets desktop browsers.
- The default output-token limit is 4096 instead of 500 (`game.default-max-tokens`).
- Only the system prompt, the last six prior messages and the current turn are sent to a model, instead of the whole game's conversation.
- The cost of rejected answers is added to the game's (and so the tournament's) total cost.
- `games.total_cost_usd` now has 8 decimal places (`NUMERIC(12,8)`, migration V10), like per-attempt costs, so small rejected charges are no longer rounded away.
- *Replay game* for a single-game match (best of 1) no longer wipes and replays the drawn game. The drawn game is kept with its moves, chat and cost, and a tiebreak game with swapped colors is added to the match; the first decisive tiebreak game decides, as in longer series.
- Live move updates now carry an `isOverride` flag, and moves set by an operator override are evaluated by Stockfish like regular moves.
- Reliability score: 50% first-try legal moves, 30% games not forfeited, 20% few retries (`100 - 50 × average retries per move`). Latency is no longer scored (it gave no points above 11.5 s and penalised reasoning models) but is still shown. Reliability and comparison are both based on finished games only. Low samples are pulled toward 60 until 60 moves instead of 20 games, which brackets rarely reach; *Insufficient data* still applies below 3 games. `GET /api/analytics/reliability/{modelId}` returns `firstTryLegalScore` instead of `latencyScore` and no longer returns `completionScore`.
- Per-model cost in analytics (comparison, health, reliability and the tournament cost summary) now includes rejected attempts from `move_attempts`; accepted attempts are not counted twice, and a model whose only answers were rejected (for example before an invalid-move forfeit) still shows its total cost. The health total and the tournament's average cost per move include them too, and the average cost per game ignores byes.
- *Max Retries* is labelled *Max attempts per move* in the setup page and README; the API field stays `maxRetries` for compatibility.
- The setup estimate prices a move with about 1,400 prompt tokens (system prompt, last six prior messages and the current turn, measured by a backend test) instead of 900, and its footnote warns that reasoning models can use far more output tokens, up to the tournament's max output tokens.
- Analytics page: a compact header with the sync action, left-aligned tabs, one card surface in both themes, and reliability grades as tone-coded badges next to the score (A green, B neutral, C/D orange, too few games dashed). The *Most Reliable* card leads with the score and its grade, and a leader picked from too few games is marked *Low sample* with the reason. Numbers are right-aligned; latency has thousands separators, average retries two decimals and cost per move four. The comparison table fits a 1280 px window.
- Deleting a tournament asks for confirmation first; for a tournament in progress the prompt warns that all its games, moves, chat and history are deleted.
- Game feed: a ply's failed or cut-off answers are shown on their own line (class `feed-attempts`) instead of being appended to the player name.
- The pause reason for a cut-off answer is short and user-facing ("…'s answer was cut off at the 4096-token output limit (3900 of them reasoning). Raise the limit and resume"); it no longer names the API route or field, which stay documented in the README.

### Fixed
- Reasoning models no longer lose games because a 500-token limit cut off their answer: an answer cut off at the limit without a usable legal move pauses the game with a hint instead of counting as a failed attempt; a cut-off answer with a legal move is played.
- Failed attempts and their cost are no longer lost.
- The tournament's trash talk switch is honoured: with it off, models get a prompt variant (version suffix `-silent`) asking them to leave the message empty, and any message they send is dropped.
- Game view: the "First" history button shows the start position instead of the current one.
- Game view: moves that arrive over the live connection while the game is still loading are merged into the loaded move list instead of being lost or shown twice; a live move that is already listed is ignored.
- Game view: live override moves are marked as overrides immediately instead of only after a reload.
- Game view: reviewing earlier moves no longer changes whose turn the page shows when the server has not reported it.
- Analytics no longer count byes (a side without a participant; a real participant named "BYE" still counts) or waiting, running and paused games as games played, so unfinished games no longer lower completion; finished games are selected by completion time instead of creation time, and moves follow their games.
- Setup estimate: the final is no longer counted twice when no separate final format is set (4 players, best of 3: 6–9 games instead of 8–12).
- Bracket: a waiting series with a missing player says "Waiting for the previous round." instead of offering to start the game.
- Bracket: the Final's result says "*name* wins the tournament" instead of "advances", also when the draw policy decided it ("wins the tournament on the draw policy (1-1)").
- Bracket: in a best-of series, a ready or running later game can open the previous finished game with *Review Previous*, and the result text and *Review* use the last finished game instead of the next waiting one.
- Bracket: a series card shows the colours of the current game when a later game swaps them; series wins stay with the right participant, identified by participant id, so two participants with the same name are no longer mixed up (names are only compared for data without ids).
- Bracket: the score in draw-policy results, such as "(0-0)", no longer wraps across lines.
- Bracket: a completed Final shows its result ("*name* wins the tournament 2-0"), also when it ended by forfeit, not only when it was level.
- Bracket: sudden-death tiebreak games beyond best of N no longer count toward the series score (1-1 plus a tiebreak showed 2-1); a series decided by a tiebreak says "*name* wins the tiebreak (1-1 after 3 games)" instead of naming the draw policy.
- Bracket: only a side without a participant is a bye; a real participant named "BYE" can play and shows a score.
- Bracket: a pending card shows "Waiting for the previous round." instead of "Waiting for an opponent" or "Waiting for both players".
- System status: a failed refresh no longer leaves the page loading, and while the backend is unreachable the OpenRouter and Stockfish cards show *Unknown* instead of a failure.
- Moves that share a move number are ordered White first everywhere (`Game.moves`, the moves and attempts endpoints (attempts of a ply by time, since attempt numbers restart after a resume), the board rebuild on resume, and the game view), also when they were stored Black-first or with equal timestamps.
- Game view: the evaluation bar no longer shows 0.0 for positions without a Stockfish evaluation (not evaluated yet, Stockfish unavailable, or the start position); it shows a dash on a neutral bar instead, titled "Evaluation unavailable". The bar is exposed to screen readers as an image named after its state (for example "Evaluation: +0.4"). The final position of a game won by checkmate shows the result (1-0 or 0-1) with the bar fully on the winner's side and the label "White wins by checkmate" or "Black wins by checkmate", also when Stockfish reported it as an unsigned "mate 0"; a final stalemate or insufficient-material position shows 0.0, also when a different score was stored for it.
- Third-party notices now list chesslib (Apache License 2.0); the security-advisory link in the issue template points to this repository; the CI workflow runs with a read-only `contents` token permission.

## [1.2.0] - 2026-11-16

Runtime release: the images now run on the current LTS versions of Java and Node.js. No configuration changes are needed; existing `.env` files and databases work as they are.

### Changed
- README rewritten: project motivation, features, a copy-paste quick start with the published images, how tournaments work, and condensed development notes.
- Runtime upgrade to current LTS versions: the backend builds and runs on Java 25 (Eclipse Temurin 25 build and JRE images, bytecode level 25) and the frontend builds on Node.js 26; CI uses the same versions. Local development needs Java 25+ and Node.js 24.15+ (26 recommended).

## [1.1.0] - 2026-11-15

Maintenance and reliability release: fixes tournament-integrity and concurrency defects, moves to supported dependency versions, and hardens the release pipeline and default deployment.

### Upgrade notes
- Docker Engine 28.0+ is required for the localhost-only port bindings.
- `.env` for `docker-compose.prod.yml` must set `IMAGE_TAG` (for example `v1.1.0`) and `DB_PASSWORD`; there are no more `latest`/password fallbacks. Volumes created with the former default password need the one-time rotation in `docs/deployment.md`.
- The released-image stack no longer publishes a backend port; use `http://localhost:${FRONTEND_PORT}/api`. `BACKEND_PORT` is no longer used there.
- Database migrations V8 and V9 run automatically. If they log that a unique index was skipped because of old duplicate data, see the troubleshooting section of `docs/deployment.md`.

### Changed
- The released-image stack (`docker-compose.prod.yml`) no longer publishes a backend host port; the frontend proxies `/api` and `/ws`, so the API is at `http://localhost:${FRONTEND_PORT}/api`. `BACKEND_PORT` is no longer used there.
- `POST /api/tournaments` accepts optional `participants` and `clientRequestId` fields and returns `200` with the existing tournament when the same `clientRequestId` is repeated.
- Dependabot no longer proposes Angular/TypeScript/ESLint majors or Java/Node base-image version jumps; those upgrades are done deliberately.
- Development and released-image Compose ports now bind to loopback by default on the documented Docker Engine 28.0+ baseline, preserving local access without exposing the unauthenticated control plane to the surrounding network.
- Production Compose now requires an explicit database password, includes a one-time rotation path for volumes created with the former fallback, and `make setup` creates or tightens `.env` to owner-only permissions.
- Backend upgraded from Quarkus 3.21.3 to the 3.40 LTS stream; the CORS switch is now `quarkus.http.cors.enabled` and the Hibernate schema setting uses `schema-management.strategy`.
- Frontend upgraded from Angular 21.1 to the latest 21.2.x patch release, including Angular Material, CDK and angular-eslint.
- Container base images re-pinned to current digests.
- CI and release workflows moved to Node 24-based action majors after GitHub removed the Node 20 Actions runtime; frontend CI now builds on Node 24 to match the container image.
- Dependabot now proposes weekly Maven, npm and Docker updates and monthly GitHub Actions updates.
- Backend database integration tests (`*IT`, run by `mvn verify` and in CI) use a Quarkus Dev Services PostgreSQL; `mvn test` still runs only Docker-free unit tests. The datasource URL and credentials are now configured per profile (`prod`, `dev`).
- Releases now run the full CI suite on the tagged commit, accept only exact `vX.Y.Z` tags from `main`, push images as commit-addressed candidates first, smoke-test both candidates together via `docker-compose.prod.yml`, and only then promote both digests to the version tag and `latest`. A failed release no longer leaves a mismatched `latest` pair.
- `docker-compose.prod.yml` now requires `IMAGE_TAG` instead of defaulting to `latest`; set a release version such as `v1.1.0` in `.env`.

### Fixed
- OpenRouter infrastructure failures no longer decide games. Rate limits, server errors and network failures are retried with backoff without using up the model's attempts; invalid keys, missing credits, unknown models, or retries that run out pause the game with a visible reason instead of recording a forfeit. Resume the game once the problem is fixed.
- Finished or running games can no longer be started again: `POST /api/games/{id}/start` only accepts `WAITING` and `PAUSED` games (atomically) and returns `409` otherwise, and a finished game's result can no longer be recorded or advanced twice.
- Concurrent human or override moves can no longer write two moves for the same ply: move writes lock the game row and are rejected with `409` when the position changed, and the database enforces one move per ply (migration V8).
- Best-of series now end after at most N games: a draw counts as a game without a winner, a series ends as soon as the lead can no longer be caught, and a series that is level after N games is decided by the tournament's draw policy (White advances = white in game 1, Black advances, Higher seed, Random). Only `Replay game` adds sudden-death tiebreak games until one is decisive; the setup estimate now shows that maximum as open-ended and the bracket labels those games as tiebreaks.
- The game that decides a series records who advanced, so draw-policy decisions (including random ones) are kept: the bracket highlights and names the right player, and a completed tournament shows its champion (`winnerName` in the tournament API, `advancedPlayerName` per game).
- Generating a bracket twice (double click, retry, concurrent requests) no longer creates duplicate brackets: generation locks the tournament, only runs for `CREATED` tournaments without games, and returns `409` otherwise; the button is disabled while the request runs. Migration V9 adds a unique bracket-slot index (skipped with a notice if old duplicates exist).
- Extra series games created by older versions no longer count toward the result; only the best-of games (and `Replay game` tiebreaks after a level series) do.
- Creating a tournament is now one request: the tournament and all participants are stored together or not at all, a retry with the same client request id returns the first tournament, the Create button is disabled while saving, and errors are shown instead of silently leaving a partial tournament. Participants can no longer be added after the bracket is generated (`409`).
- With `openrouter.model-validation-fail-open=true`, adding participants now works while the OpenRouter model catalog is unavailable; previously every model was rejected.
- Bracket advancement no longer rewrites the pairing of a next-round game that has already started, and concurrent advancements into the same game are serialized.
- Pausing, completing and forfeiting lock the game row, so they can no longer overwrite a concurrently recorded move; repeated pause requests can no longer let a resumed game run two loops at once.
- Games left in progress by a backend restart, or by an unexpected game-loop failure, are now paused with a visible reason instead of being stuck; resume them from the game view. Open game views reload their state after the live connection reconnects.
- A paused game can no longer be forfeited, and pause requests that coincide with a human move or a loop handing over to a human turn are no longer lost.
- `POST /api/games/{id}/start` returns the game state after the start instead of the previous snapshot.
- WebSocket game subscriptions now reject missing games, move a connection cleanly between games, and remove empty subscription state on unsubscribe or disconnect.

### Security
- Live-update WebSockets now limit message size (4 KB), cap simultaneous connections (`game.websocket.max-connections`, default 256; extra connections close with 1013), and close a connection that sends more than 30 subscribe requests in 10 seconds (the frontend reconnects and resubscribes). Periodic pings keep idle connections open; subscriptions from connections that closed while subscribing are dropped.
- Default Docker networking now matches the documented localhost-only product boundary for the unauthenticated application, with Docker Engine 28.0+ required for loopback isolation.
- Frontend runtime dependencies no longer carry known vulnerabilities (previously seven high-severity advisories in `@angular/core` and `@angular/compiler`); remaining moderate advisories are confined to build tooling and resolve with Angular 22.

## [1.0.0] - 2026-03-08

### Added
- Local human participants can now be included in tournaments, with live move submission from the game view and normal bracket progression after human turns.

### Changed
- README and deployment docs now focus on the supported local-first product scope, streamlined setup, and `v1.0.0` release guidance.
- Game prompts and UI labels now use neutral tournament wording so model-vs-model and human-participant matches read consistently.

### Fixed
- Pausing a game while it is waiting on a local human move now transitions the game to `PAUSED` immediately instead of leaving it live with a stale engine marker.
- Model analytics now ignore human moves without a model ID instead of aggregating them under a fake `unknown` model row.

## [0.9.10] - 2026-03-08

### Changed
- Release image publishing now explicitly sets up a Buildx builder before backend and frontend publish jobs, so GitHub Actions can use the `gha` cache backend correctly.

## [0.9.9] - 2026-03-08

### Changed
- Backend release-image builds now retry whole Maven `go-offline` and package steps instead of failing on a single transient invocation error.
- Release image publishing now uses GitHub Actions build cache for backend and frontend Docker builds to reduce repeated cold-start dependency work.

## [0.9.8] - 2026-03-08

### Changed
- Prompt resolution and conversation initialization now live in a dedicated backend conversation service instead of being embedded directly in `GameEngineService`.
- Backend refactor coverage now includes focused tests for conversation prompt selection and board replay initialization.

## [0.9.7] - 2026-03-08

### Changed
- Single-turn retry execution, timeout handling, and invalid-move retry flow now live in a dedicated backend turn service instead of being embedded directly in `GameEngineService`.
- Backend refactor coverage now includes focused tests for turn execution outcomes.

## [0.9.6] - 2026-03-07

### Changed
- Game lifecycle state transitions now live in a dedicated backend service instead of being embedded directly in `GameEngineService`.
- Move recording, override application, chat persistence, and evaluation-update handling now live in a dedicated backend service instead of being embedded directly in `GameEngineService`.
- Backend refactor coverage now includes focused tests for game lifecycle and move-recording behavior.

## [0.9.5] - 2026-03-07

### Changed
- Analytics window loading and terminal-game filtering now live in a dedicated backend loader instead of being embedded directly in `AnalyticsService`.
- Analytics game-outcome interpretation now lives in a dedicated backend classifier instead of being mixed into analytics aggregation logic.
- Backend analytics refactor coverage now includes focused tests for outcome classification behavior.

## [0.9.4] - 2026-03-07

### Changed
- Analytics reliability scoring now lives in a dedicated backend collaborator instead of being duplicated across analytics code paths.
- Backend analytics scoring behavior now has focused unit coverage outside the broader analytics service tests.

## [0.9.3] - 2026-03-07

### Changed
- Game view live turn and timer state now lives in a dedicated frontend service instead of being embedded directly in `GameView`.
- Game view now surfaces load, pause, and resume failures to the operator instead of failing silently.

## [0.9.2] - 2026-03-07

### Changed
- Frontend live-update handling now uses concrete websocket message variants instead of broad catch-all typing.
- Live-update and tournament-load failures now surface through the UI instead of relying on console-only diagnostics.

## [0.9.1] - 2026-03-07

### Changed
- Default backend runtime configuration now requires explicit database credentials instead of shipping committed fallback values.
- Quarkus `dev` profile keeps the previous local `chess/chess` database defaults for local development convenience.

## [0.9.0] - 2026-03-07

### Added
- New comparison-first analytics endpoint at `GET /api/analytics/comparison`.
- Analytics dashboard now identifies strongest, most reliable, fastest, and best-value models for the selected sample window.

### Changed
- Analytics now exposes win/draw/loss records, forfeit behavior, white-vs-black splits, spend, and cost-per-win in one sortable model comparison view.
- Pricing-aware analytics now render incomplete price coverage explicitly instead of implying full cost comparability.

## [0.8.0] - 2026-03-07

### Added
- New toolbar brand mark with a cleaner chess-and-arena visual identity.

### Changed
- Desktop pages now share a more consistent shell, hero treatment, and card language across tournament list, analytics, system status, and bracket views.
- Tournament, analytics, and system headers now use a unified desktop title hierarchy instead of separate page-specific patterns.

## [0.7.0] - 2026-03-06

### Added
- Game view now includes a unified event timeline for moves, chat, retries, overrides, and result events.

### Changed
- Timeline entries can jump directly to the associated move context when available.
- The game timeline now complements the SAN move list with broader match context instead of relying on the move list alone.

## [0.6.0] - 2026-03-06

### Added
- Bracket cards now present richer live matchup state with cleaner hierarchy, including live, paused, ready, pending, complete, forfeit, and bye states.

### Changed
- Bracket cards now prioritize state, score, and current series context over duplicated result badges.
- In-progress series can expose a direct `Review Previous` action for the prior game in the series.

## [0.5.0] - 2026-03-06

### Added
- Tournament history now shows a first-run onboarding flow with starter presets for the first launch.
- Tournament setup now includes a confidence panel with projected games, runtime, and rough spend heuristics.

### Changed
- Tournament setup presets can prefill starter configurations through onboarding links.
- The frontend component-style warning budget was raised to reflect the richer onboarding layout.

## [0.4.0] - 2026-03-06

### Added
- Immutable system rules are now separated from optional prompt customization.
- Tournament setup now supports shared custom instructions for all participants.
- Tournament setup now supports participant-specific instruction overrides.

### Changed
- New tournaments no longer edit the full system prompt directly.
- Prompt resolution now applies a stable precedence model: legacy full-template override, participant instructions, shared instructions, then base rules only.

## [0.3.0] - 2026-03-06

### Added
- Dedicated `/system` dashboard for operator visibility into backend, OpenRouter, and Stockfish status.
- Backend system-status payload now includes backend version and status check timestamp.

### Changed
- Runtime application metadata is aligned with the current release line so the system dashboard reflects shipped versions accurately.

## [0.2.1] - 2026-03-06

### Added
- Backend image now includes explicit Stockfish notice/license artifacts for distribution compliance.
- Game view now shows explicit evaluation engine status.
- Tournament list now supports filtering and sorting in the UI.

### Changed
- Release/deployment policy is now documented explicitly for maintainers.
- Stockfish evaluation is queued only after move persistence commits successfully.

### Fixed
- Backend now degrades gracefully when Stockfish is unavailable instead of leaving evaluation behavior ambiguous.
- Advantage-bar updates no longer race move persistence and get dropped intermittently.
- Frontend game view makes evaluation availability clearer when Stockfish is offline.

## [0.2.0] - 2026-03-06

### Added
- Best-of tournament matchup series with optional finals-specific override.
- Release workflow for version tags that publishes frontend/backend images to GitHub Container Registry.
- Production deployment assets:
  - `docker-compose.prod.yml`
  - `docs/deployment.md`
  - expanded `.env.example`
- Repo workflow policy in `AGENTS.md`.

### Changed
- README now distinguishes released-image deployment from local source-build development.
- Tournament brackets and setup now document released behavior for best-of series.

### Fixed
- Stockfish evaluation scores are normalized to White's perspective before persistence/rendering.
- Bound-only Stockfish scores are ignored so the advantage bar does not treat them as exact evaluations.

## [0.1.0] - 2026-02-25

### Added
- Initial public project structure for AI Chess Arena.
- Quarkus backend with REST and WebSocket endpoints for tournaments, games, and analytics.
- Angular frontend with tournament setup, bracket view, live game view, and analytics dashboard.
- PostgreSQL + Flyway migrations for persisted game/tournament data.
- Docker Compose local stack (`db`, `backend`, `frontend`).
- Branding/legal inventory file: `LOGO-LICENSES.md`.
- Repository governance and quality files:
  - `LICENSE` (MIT)
  - `CONTRIBUTING.md`
  - GitHub issue templates and PR template
  - Baseline GitHub Actions CI workflow (`.github/workflows/ci.yml`)

### Changed
- Standardized core naming to **AI Chess Arena** across key metadata and UI labels.
- Migrated Java package namespace from `com.example.chess` to `dev.aichessarena`.
- Updated project metadata versions to `0.1.0` (`backend/pom.xml`, `frontend/package.json`).
- Renamed Angular project key to `ai-chess-arena` and aligned Docker dist output path.
- Added root `.gitignore`, `.env.example`, and Docker ignore files for backend/frontend.

### Security
- Added environment template and ignore rules to reduce accidental secret commits.
- Parameterized database credentials in `docker-compose.yml`.
