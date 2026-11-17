# How AI Chess Arena works

Detailed rules, settings and API conventions for running tournaments. For setup and development commands, see the [README](../README.md); for upgrades and operations, see the [deployment guide](deployment.md).

## Contents

- [Connecting OpenRouter](#connecting-openrouter)
- [Tournament formats and draw policies](#tournament-formats-and-draw-policies)
- [Auto-run](#auto-run)
- [Opening variety](#opening-variety)
- [Cancelling a tournament](#cancelling-a-tournament)
- [Choosing participants and model picker](#choosing-participants-and-model-picker)
- [Model turns and prompts](#model-turns-and-prompts)
- [Prompt caching](#prompt-caching)
- [Time limit per move](#time-limit-per-move)
- [Output-token limit](#output-token-limit) and [fixing a cut-off game](#fixing-a-cut-off-game)
- [Reasoning effort](#reasoning-effort)
- [Chat mode](#chat-mode)
- [Resignation](#resignation)
- [Adjudication](#adjudication)
- [Move quality](#move-quality) and [missed mates](#missed-mates)
- [Conversion metrics](#conversion-metrics)
- [Analytics and reliability](#analytics-and-reliability)
- [Board colours](#board-colours)
- [Export](#export)
- [Leaderboard](#leaderboard)
- [Live settings](#live-settings)
- [Scope and interpretation](#scope-and-interpretation)

## Connecting OpenRouter

The app starts without an OpenRouter key. Browse tournaments, analytics and setup first; the public model catalog needs no key. Creating a tournament, running a bracket, starting a battle or resuming a game checks the current key and opens the same **Connect OpenRouter** dialog when none is configured. After connecting, the dialog closes and you must click the original action again; it never runs automatically. Setup drafts stay intact when connecting through the manual key field in the dialog; leaving the app for OpenRouter reloads the page on return.

On setup and first-run tournament onboarding, a missing key shows an informational hint with a **Connect OpenRouter** button. A failed validation shows a warning with **Fix connection**. Both buttons open the same dialog; a successful connection clears the hint immediately without reloading the page. Connected keys with valid or unknown validation show no connection banner. Fresh page status replaces cached connection metadata, including changes made in another browser. A delayed status response cannot overwrite a successful connection, save, deletion or key test completed since that request started; later status requests still refresh the metadata normally.

In **System → OpenRouter**, choose **Connect OpenRouter**. OpenRouter asks you to approve a key: **set a credit limit and expiration for this key on OpenRouter**. The backend exchanges the authorization code using [PKCE S256](https://openrouter.ai/docs/guides/overview/auth/oauth), validates it with the free [current-key endpoint](https://openrouter.ai/docs/api/api-reference/api-keys/get-current-api-key), and stores it privately. The callback uses the browser's current origin and port. After approval, you return automatically to the previous in-app page with an **OpenRouter connected** confirmation. If you leave the callback page while completion is pending, its late response does not navigate away from your new page or show that confirmation. The return route is held in session storage, accepts only same-origin relative paths, and defaults to the tournament home page if missing or invalid. A failed or cancelled callback shows **Try again** and **Back to tournaments**. State lasts ten minutes, is single-use and is bound to that browser's HttpOnly cookie and origin. Expired, cancelled or failed connections require starting again. Neither the key nor OpenRouter user ID is returned to the browser; user IDs are discarded.

Alternatively expand **Use an existing API key instead** (or **Use a different API key** when connected), paste a key into the outlined password field and choose **Validate and save** below it. The field stays collapsed until you expand it. Connected UI keys show **Test key**, **Replace connection** and **Delete local key** together; **Test key** is hidden when no key exists. Operator-managed keys show only status, the operator explanation and **Test key**. The password field is write-only and cleared immediately after submitting, including on failure. Collapsing manual entry, switching to OAuth or leaving settings also clears unsaved input; reopening the field starts empty. Invalid keys and provider outages leave the previous key untouched. Settings show only the source (`env`, `file`, `ui`, `none`), a masked four-character suffix, last check time and validation result. Status reads do not contact OpenRouter; **Test key** checks the effective key through the claimed, same-origin `POST /api/config/openrouter/test` endpoint. Validation time/results are process-local and reset on backend restart or a change of effective key.

The legacy `GET /api/config/openrouter-status` and `GET /api/config/system-status` are passive too: each includes `key:{source,maskedSuffix,lastValidatedAt,validation}`. Their existing `valid` / `openRouterValid` booleans are true only when the stored result for the current key is `valid`; an untested operator key reports `unknown` and false. System `checkedAt` is the status response time, not a provider validation time. Both GETs use `Cache-Control: no-store`.

Connection and validation are separate: a key with source `env`, `file` or `ui` is **connected**, including after a restart before it has been tested. Only source `none` shows a connection prompt. The UI treats validation as **valid**, **invalid**, or **unknown**; an untested key has unknown validity and shows no warning. An explicit `invalid` result shows a failed-validation hint while the key stays connected. Raw `unavailable` check outcomes also mean unknown validity, never an invalid-key warning; `none` means no key to validate. Setup, onboarding, settings and system status follow these rules. Create/Run/Battle/Resume preflights check key presence, without requiring a successful prior test. An unavailable status response cannot establish that a key is missing. API clients must use `key.source` for connection and `key.validation` for validation, rather than interpreting either legacy boolean as connection state.

**Delete local key** removes the UI-managed file, without revoking the key at OpenRouter. Revoke it there separately. New model requests use a replacement immediately; requests already sent finish with their original key. A missing/deleted credential during play is a provider problem and pauses play rather than causing a model forfeit. The public model catalog retains its existing five-minute cache and failure backoff independently of key changes.

Environment/explicit secret-file keys are operator-managed and cannot be replaced or deleted in the UI (HTTP 409). For LAN setups, key management first requires the setup-token link from the backend log in the claiming browser; other browsers can view status but cannot test or change credentials. See [claiming and the threat model](deployment.md#instance-claim-and-threat-model).

Credential API under `/api/config/openrouter`: `GET` status; `POST /connect` returns `authUrl`; `POST /complete` takes `{code,state}`; `POST /key` takes `{key}`; `DELETE /key`; `POST /test`; `POST /claim` takes `{token}`. Mutations require a same-origin browser request and an authorized browser cookie. Unknown/missing body fields fail with HTTP 400. Failed authorization or claiming returns 403, foreign hosts 421, and upstream failures 502. Status and mutations return `{key:{source,maskedSuffix,lastValidatedAt,validation},canManage}` (unset metadata may be omitted); `validation` is `none`, `unknown`, `valid`, `invalid` or `unavailable`. All responses use `Cache-Control: no-store`.

## Tournament formats and draw policies

Tournaments are single elimination with best-of 1, 3, 5 or 7 series and a separate setting for the final.

### Bracket

Choose **Bracket draw** under **Tournament Settings → Matches** on the setup page. Creating the tournament generates its bracket; on the tournament page, start a game or choose **Run tournament**:

- **Random draw** (the UI default): shuffle the participants once and assign new seeds starting at 0 in the drawn order.
- **In added order**: keep the existing seeds (the setup page assigns these in the order you add participants).

Participants are paired in seed order, lowest first. When the field is not a power of two, the lowest seed numbers receive byes; the rest are paired in seed order. For example, with five participants, seeds 0, 1 and 2 receive byes and seeds 3 and 4 play. The **Participants by seed** panel shows the stored seeds after generation. A random draw can happen to produce the original order.

API creation: `POST /api/tournaments` accepts `drawMode: "RANDOM" | "ORDER"`. Omitted or null defaults to **ORDER**, matching the existing generation API's seed-order compatibility rule; the UI explicitly sends RANDOM by default. Invalid modes (including an empty string) return 400 even when generation is disabled; modes are case-insensitive. `generateBracket: true` requires at least two participants and returns 400 before storing anything otherwise. `generateBracket` omitted, null or false creates a draft with no bracket, regardless of lineup size. This preserves API clients that create with participants, add more participants and then request manual generation, including a random draw. The flag accepts JSON booleans or null, rejecting strings and numbers. The UI explicitly sends true.

Participant array order in API responses is unspecified; use each participant’s `seed` for draw order and `id` for identity. The tournament and participants commit together before generation takes the tournament row lock. When generation is requested, the create response includes the bracket and drawn seeds, with games ready to start (creation does not start play). Repeating a `clientRequestId` returns the same tournament (200 rather than the initial 201), never a second bracket. Its original generation/draw choice wins even if a retry supplies different choices. If generation was interrupted after the tournament committed, retry the same request ID to finish it; a failure can leave a committed draft until retried. Concurrent creates and generation requests cannot reshuffle an existing bracket. Use a `clientRequestId` for safe recovery from a lost response.

Existing tournaments without a bracket, including API drafts, keep **Generate Tournament Bracket** and its draw selector on the tournament page. API: `POST /api/tournaments/{id}/generate-bracket?drawMode=RANDOM` requests a random draw; `drawMode=ORDER` or omitting the parameter preserves the existing seed order. No request body is required; an empty JSON object also works. An invalid mode returns 400. Generation stores the drawn seeds and bracket together. Repeated or concurrent manual generation requests return 409 after the first succeeds. Creation-only fields do not change settings through PUT. Migration V22 stores the original creation draw choice; existing tournaments retain their bracket/draft state, with no generation backfill.

### Best of N

A series lasts at most N games. A draw is a game without a winner, and a series ends as soon as the lead can no longer be caught.

### Draw policies

When level after N games (or a drawn single game), the tournament's draw policy decides — *White advances* (white in game 1), *Black advances*, *Higher seed advances*, *Random*, or *Replay game*.

*Higher seed advances* uses the lower stored seed number, including the newly assigned seeds after a random bracket draw. This tiebreak policy is separate from the **Bracket draw** choice.

*Replay game* adds sudden-death tiebreak games (colors alternating) until one is decisive; this also applies to a single-game match, where the drawn game stays in the series with its moves, chat and cost and is followed by "Tiebreak game 1", 2, and so on. It has no fixed maximum length or cost.

### Cost estimate

The **cost estimate** before you start shows expected games, runtime and spend for the chosen models and format.

## Auto-run

Create a tournament to get its bracket, then choose **Run tournament** on the tournament page. **Concurrent games** defaults to 2 and accepts 1–8. Leave **Budget (USD, optional)** empty for no cap, or enter a positive decimal amount with at most two decimal places. Invalid text such as `1e` shows a validation message and blocks Run, Resume and Save limits; it never removes an existing cap. Deliberately clearing the field removes the cap when you submit. While auto-run is off, the banner follows the current form selections, for example **Auto-run off · limit 4 · no budget cap**. Run tournament applies these values directly. While running or paused, the banner shows applied limits, games currently running (rather than games completed), total reported spend and how many paused games need attention, for example **Running · 2 games running (limit 4) · $0.02 of $2.00 spent · 0 need attention**. Live counts and spend refresh with the page's 15-second polling interval; unsaved form edits are preserved.

Auto-run starts ready **Waiting** model-vs-model games in bracket order (earlier rounds, bracket position, then series game number), filling free slots as games end and series/bracket advancement creates the next games. Byes and unknown pairings are skipped. Games containing a **human player must be started manually**; while running, they count toward the concurrency cap too. Manual starts share the run's cap, so simultaneous Battle clicks and scheduler notifications cannot start the same game twice or exceed it.

Paused games never start automatically and do not occupy a concurrency slot. A provider problem or cut-off answer needs the operator to fix settings and resume that game from its game view. **Pause** stops scheduling and pauses every running game through the normal game lifecycle. **Resume run** enables scheduling of waiting games again; separately resume any paused games you want to continue. A paused run rejects manual starts until the run is resumed. **Save limits** appears only for a running or paused run and is enabled only when valid form values differ from the applied limits. **Unsaved changes** appears beside it until you save, resume with the edits, or revert them. Saving changes the budget/concurrency immediately without resuming a paused run. Reducing concurrency pauses games beyond the new limit, keeping the earliest bracket games running; those paused games also need manual attention.

Spend is the sum of every game’s `totalCostUsd` in the tournament, including historical spend and failed or discarded attempts. Before every model request (including retries), and after every recorded attempt, the backend compares spend against the cap. At or above it, the run becomes **Paused** with `Budget of $X reached ($Y spent)` and every unfinished running game pauses with that reason. An accepted terminal move (mate or automatic draw), resignation or eligible adjudication using already stored evaluations completes and advances the series/bracket before its attempt triggers the budget pause; finished games keep their results. Cancellation still prevents completion and advancement. Lowering a running budget below current spend pauses immediately. Raise or remove the cap and resume the run, then resume paused games manually. **A budget is not a hard billing limit:** one in-flight answer can overshoot it slightly; with concurrent games, several already in-flight answers can still add cost after pausing. Provider-reported spend remains authoritative.

Without an enabled run (`OFF`), manual play ignores the saved run budget and concurrency settings. Finishing a run sets `FINISHED`; cancellation switches it `OFF` and is final. After a backend restart, a previously `RUNNING` run becomes `PAUSED` with **Backend restarted**; GameRecovery pauses its interrupted games. Resume the run and its games explicitly. Notifications trigger scheduling after commit, with a 10-second safety tick; no model request or automatic start is made during startup recovery.

API (all changes take the tournament row lock):

| Endpoint | Behavior |
|---|---|
| `POST /api/tournaments/{id}/run` | Start/resume with a JSON object containing optional `maxConcurrentGames` and `budgetUsd`. |
| `PUT /api/tournaments/{id}/run` | Save limits without changing a paused/off run into a running one. |
| `POST /api/tournaments/{id}/run/pause` | Pause the run and its running games; no body required. |

Omitted fields retain saved values (initially 2 and no budget); explicit `budgetUsd: null` removes the cap. Concurrency must be an integer 1–8, budget a positive JSON number with at most two decimal places; invalid bodies/unknown fields return 400. Missing tournaments return 404; tournaments without a generated bracket, completed tournaments and cancelled tournaments return 409. Responses and tournament reads include `autoRunStatus` (`OFF`, `RUNNING`, `PAUSED`, `FINISHED`), `maxConcurrentGames`, `budgetUsd` (always present, nullable) and `autoRunPauseReason` when paused. For example, start with `{"maxConcurrentGames": 2, "budgetUsd": 5.00}`.

## Opening variety

Choose **Opening mode** during setup:

- **Standard** (default): start every game from the normal initial position, as before.
- **Random book**: choose uniformly from 42 short authored opening lines for each new pairing.
- **Fixed book line**: choose one named line from the book for every new game.

The backend ships [the full book](../backend/src/main/resources/openings.tsv), written from standard opening theory under this repository's MIT license, without importing an opening database. Each line has a stable id, ECO code, name and 4–8 SAN plies. ECO labels were cross-checked against the [Lichess CC0 reference](https://github.com/lichess-org/chess-openings); no reference database is bundled. These are short introductions rather than full tabiyas: the Open Sicilian line can lead toward a Najdorf, whose defining moves exceed the eight-ply limit.

The book covers Ruy Lopez (including Morphy and Berlin), Italian and Two Knights, Scotch, Four Knights, Petroff, Vienna, Bishop's Opening and Philidor; Open, Closed, Alapin, Rossolimo and Kan Sicilians; French (Advance, Exchange, Winawer, Tarrasch), Caro-Kann (Advance, Classical), Scandinavian, Pirc, Modern and Alekhine; Queen's Gambit Declined/Accepted, Slav, Semi-Slav, King's Indian, Nimzo-Indian, Queen's Indian, Grunfeld, Catalan, London, English (Symmetrical and Reversed Sicilian), Reti and Dutch.

### Series fairness and settings changes

Regular color-reversed partners **games 1–2, 3–4 and 5–6** share exactly the same stored opening, with participants swapping colors. The line is selected when the odd-numbered game is created. An unpaired last regular game selects a line independently. Early series completion can leave a pair unfinished. Every sudden-death **tiebreak may select a fresh line**, even if it reverses the preceding game's colors; with Fixed it uses the currently selected line. Random selection may repeat a line.

Opening settings apply only to **games created after the change**, never to running, paused or already waiting games. Future-round placeholder games are created with the bracket and retain that snapshot too. The even game of a regular pair always reuses its partner's stored line, including Standard, even if settings have since changed. Byes have no book moves. Existing games remain Standard with their stored history unchanged; no backfill is performed.

### History, evaluation and analytics

Book moves are actual stored move rows marked `isBook: true`. The board, PGN export and every model's turn prompt include the full opening history. Models (and humans) begin after the last book ply, on the correct side and move number. Start and resume initialize the opponent's last move from the final stored ply, including book moves; a message is restored only when attached to that exact ply. Without a last move, the opponent-played sentence is omitted. Resume replays the same history. The game view and bracket show the opening name/ECO, and the move list marks book plies in grey with **book**; those moves remain selectable for reviewing the board.

Book plies have no model, chat mode, prompt, attempts, usage, response time or cost. They never count toward model move totals, reliability, move quality, missed mates, talkativeness, Conversion or the ten-ply adjudication window. Only the final book position is evaluated in the background, as the baseline for judging the first played move; a missing baseline leaves that move unjudged until evaluation arrives. Paid prompt tokens still include the book history. Normal board-ending rules, including repetition and the fifty-move counter, use the complete game history.

The build's unit tests replay every line with chesslib and reject illegal or terminal positions. When Stockfish is installed, an additional depth-12 check rejects mate scores or positions beyond ±150 cp; without it that check is skipped and legality is still tested. Short theoretical lines and shallow evaluations do not guarantee perfectly equal positions.

API: `GET /api/openings` returns `{id, eco, name, moves}` for each line. `POST`/`PUT /api/tournaments` accept `openingMode` (`STANDARD`, `RANDOM_BOOK`, `FIXED`, case-insensitive) and `openingId`. Fixed requires a known id; an id with another mode is a 400. Omitted/null fields retain defaults on create or current settings on update; changing away from Fixed clears its id. An id-only update changes the line of an already Fixed tournament. Game responses expose the immutable `openingId`, `openingName` and `openingEco` snapshot (omitted for Standard) and move responses expose `isBook`. Completed/cancelled tournaments reject settings changes as before.

## Cancelling a tournament

Choose **Cancel tournament** on the tournament page and confirm to end a tournament that is not finished yet, including one without a generated bracket. This cannot be undone. The action is disabled for completed and cancelled tournaments.

Cancellation ends every waiting, paused or running game as **Cancelled**, without a winner, draw or forfeit. Finished games keep their results, moves, chat and costs; unfinished games keep their recorded moves, chat and spend too. The bracket stops advancing and no more series or tiebreak games can be created. Cancelled games cannot start, resume, accept a human move or receive an override.

Running loops are asked to stop. An already pending provider request may still finish; its answer cannot write a move, chat message or result, or reopen a game. Paid discarded answers remain recorded in attempts and reported spend. Open game views receive the cancelled status after the transaction commits, with an incremented status revision so delayed updates cannot reopen them. Each committed attempt also sends an `attemptsUpdated` WebSocket invalidation (`gameId`, no status or status revision); the game view reloads attempts and game spend even if cancellation was already displayed. Rolled-back attempts send no invalidation. Cancellation closes an open promotion picker, and move submission requires an active human turn. The tournament page ignores obsolete poll responses after cancellation.

API: `POST /api/tournaments/{id}/cancel` takes no body (an empty JSON object also works) and returns the updated tournament with status `CANCELLED` (200). Tournament and unfinished-game changes commit together. Unknown ids return 404. Completed or already cancelled tournaments consistently return 409, including repeated cancellation requests. Cancelled games use `GameDto.status = CANCELLED` with no result or result reason. Play endpoints return 409 for every game belonging to a cancelled tournament, including previously finished games; bracket generation also returns 409.

All writes to an existing tournament acquire its row lock and read fresh state before changing it, including settings, participant membership and effort, bracket generation, progression, cancellation and deletion. Concurrent settings writes cannot restore an earlier status. `PUT /api/tournaments/{id}` and participant reasoning-effort changes return 409 for both `CANCELLED` and `COMPLETED`: final settings are retained with the tournament's history. Settings remain editable while `CREATED` or `IN_PROGRESS`, including paused games. An empty participant PUT remains a read-only no-op. New tournament inserts have no existing row to lock.

Cancelled games are excluded from analytics, including their moves and attempts. Finished games of a cancelled tournament keep counting under the usual completion-time window. Tournament cost summaries continue to include all recorded spend. No database migration or backfill is needed; existing data changes only when cancellation is requested.

## Choosing participants and model picker

Use **OpenRouter chat models** as participants (text-output models; embedding, vision, audio and similar models are filtered out by default), with shared or per-participant custom instructions; optionally **one human player** who moves from the live game view.

### Setup layout

Tournament setup starts with **Add participant**, with optional custom instructions revealed on demand; collapsing the field preserves its draft when adding a participant. The **Tournament lineup** groups the tournament name, roster, visible participant reasoning-effort overrides, readiness checklist and **Create Tournament** action before the rough runtime and spend estimate. At widths of at least 1200px, the sidebar stays sticky only when its measured height fits the window with an 88px top offset and a 24px bottom gap; otherwise it uses natural page scrolling. Without ResizeObserver support, it also uses natural page scrolling. Below 1200px, the order is add participant, settings, lineup, then estimate, matching keyboard navigation. Settings keep prompt rules and shared instructions in a collapsed **Advanced: prompt** section, with a **custom** badge when shared instructions are set.

### Model catalog

The setup page loads every text-output model from the OpenRouter catalog once (refreshed by the backend every 5 minutes).

Concurrent catalog lookups use a consistent snapshot for freshness and model data, rechecking after acquiring the refresh lock. Model validation and reasoning-effort mapping can therefore use a catalog published by another caller during a refresh.

Type into *Model*: every word must appear in the model's name, id or provider, in any order, so "anthropic sonnet", "sonnet 5.5" and "gpt 6" all work ("-", "." and spaces are interchangeable).

Suggestions are grouped by provider (the featured providers from `openrouter.featured-providers` first) and show release date, input/output price per 1M tokens, context window, a *Reasoning* marker and "AA" with the Intelligence Index when known.

Narrow the list by provider, *Reasoning only* or *Free only* (input and output price 0) and sort by *Newest* (default), *Cheapest* (input + output price) or *Intelligence Index*; models without that value go last.

Arrow keys and Enter select a model, which fills an empty display name; editing or clearing the field clears the selection.

The card below shows the selected model's release date, input/output/cache-read price per 1M tokens, context window, max output tokens, reasoning (always on or optional, effort levels, default effort), Intelligence Index and description, with "n/a" for anything OpenRouter does not provide.

OpenRouter's moving "latest" aliases (ids starting with "~", such as `~anthropic/claude-sonnet-latest`) are listed under their provider after its concrete models and marked *Latest alias*: OpenRouter routes them to the provider's newest model, so results can't be tied to a fixed model version.

API: `GET /api/models?featuredOnly=false&limit=0` returns the whole catalog (`limit=0` means no cap; the default is 120 and other values are capped at 300).

## Model turns and prompts

Models are asked to answer in JSON, but the parser also extracts a move from other formats; the move must be legal and, if the tournament has a time limit per move, arrive within it.

*Max attempts per move* (API field `maxRetries`) is the total number of attempts per move (3 means up to two retries); after that the model forfeits, and a model that runs out of time forfeits on time.

Each model gets its system prompt, an append-only chunk of its own prior exchanges and the current turn. Every new turn prompt contains the moves so far (PGN, including book moves) and the full position (FEN, board, legal moves), plus the opponent's latest move/message when present. New position and opponent data go only in that newest user message; earlier messages are replayed verbatim.

Tournament settings changed with `PUT /api/tournaments/{id}` while a game is running (time limit, attempts, output-token limit, chat mode, banter level, resignation, adjudication, reasoning effort) apply from its next turn, and so does a participant's reasoning-effort override.

### Prompt caching

Provider caches reuse an identical **prefix**. Sliding the oldest exchange out on every move changes that prefix, so even a large number of cache writes can produce almost no reads. By default, each side now has its own anchor: the messages sent on its next turn start with exactly the same roles and text bytes as its previous request, followed by the previous answer and the new turn. Historical prompts and raw answers keep their original whitespace and JSON ordering; they are never rebuilt with timestamps, costs or updated positions.

The window grows append-only until there are **more than 12 prior messages** (normally six completed turns). Before appending the next turn, it re-anchors to the last **four messages** (normally two completed turns), then grows again. Exactly 12 prior messages are still sent. These are message-count limits, not a fixed input-token cap: the system prompt, newest user message and all retries of that turn are outside the prior-message budget. Retries append after the current turn without changing its prefix or cache boundary. Re-anchoring retains complete turns within the retained budget; if a long retry sequence cannot fit, that entire turn is dropped. PGN still supplies the complete game, including the opening, after every re-anchor.

The backend settings are:

| Property | Default | Meaning |
|---|---|---|
| `openrouter.prompt-caching` | `true` | Enable append-only chunks and explicit provider cache markers. `false` restores the former six-prior-message sliding window with plain string content. Providers can still cache automatically. |
| `openrouter.prompt-cache-max-prior-messages` | `12` | Re-anchor only when prior messages exceed this number. |
| `openrouter.prompt-cache-retained-messages` | `4` | Keep up to this many prior messages in complete turns at a re-anchor. Must be between zero and the maximum; both limits must be non-negative. |

Environment equivalents are `OPENROUTER_PROMPT_CACHING`, `OPENROUTER_PROMPT_CACHE_MAX_PRIOR_MESSAGES` and `OPENROUTER_PROMPT_CACHE_RETAINED_MESSAGES`; see [deployment configuration](deployment.md#prompt-caching-configuration).

Explicit breakpoints require **catalog evidence**: the exact model ID must have a valid, non-negative `pricing.input_cache_write` price in the backend's cached OpenRouter catalog and belong to Anthropic or Google. Both `~anthropic/*` and `~google/*` moving aliases use their normalized provider, including `~google/gemini-pro-latest` and `~google/gemini-flash-latest`. A cache-read price alone indicates no explicit-cache capability: Gemma and other models without cache-write support receive plain messages, as do unknown models and requests before the catalog is available. Preparing a request never waits for catalog I/O.

For qualifying models, the backend sends one explicit breakpoint on the final text block of the last prior message, or on the system prompt when there is no history. Only that message uses OpenRouter's content-array form, for example:

```json
{"role":"assistant","content":[{"type":"text","text":"{\"move\":\"e4\"}","cache_control":{"type":"ephemeral"}}]}
```

The current turn and its retries are never marked. The breakpoint moves forward as history grows; it is request metadata, never part of the stored text. There is at most one marker per request (within Anthropic's four-breakpoint limit, and the final breakpoint Gemini uses). Other providers receive plain string messages with the same stable text prefix for automatic caching. See [OpenRouter's prompt-caching contract](https://openrouter.ai/docs/guides/best-practices/prompt-caching).

Catalog hints cannot guarantee every routed endpoint supports explicit caching. If a marked request receives HTTP 400 or 404 mentioning `cache_control`, cache-control or cache control, the backend retries that same attempt **once with plain messages**, preserving the model, text, generation settings and original move/safety deadlines. The rejection does not consume a model attempt or cause a fatal pause. If the plain retry exhausts the original deadline, the rejection remains a retryable provider failure: the game pauses instead of forfeiting on a model timeout. That exact model ID stays on plain messages until backend restart, even if the catalog refreshes. A repeated cache-control rejection is treated as a retryable provider failure; unrelated errors keep their normal handling.

This sends slightly more history tokens per turn than the former three-turn window. With cache hits, most repeated input is read from cache at a fraction of the normal input price; cache writes can cost extra. Model-specific minimum lengths, expiry, routing and re-anchors can still cause misses, so savings are not guaranteed. The setup cost preview remains a rough heuristic; use the [prompt-caching analytics](#prompt-caching-metrics) and provider-reported costs to measure actual results.

Human turns retain each side's model conversation in memory across waits. After a human submits a move, the loop reloads the board from committed moves and reuses those exchanges: for example, model `e4`, human `e5`, then model `Nf3` sends two messages on the first model request and four on the next. With caching disabled, each model turn prunes the backing history to at most six prior messages before appending the current turn and its retries; both outbound requests and retained conversations stay bounded. Pause/resume, backend restart or loop failure discard retained conversations; they are never persisted. Committed tournament deletion and terminal game transitions (cancellation, completion, resignation and forfeits) stop loops and evict retained conversations. Cleanup waits for commit, so rollback preserves both history and loop ownership.

Cleanup flags the owning loop before eviction, so a concurrent human-turn handoff cannot restore deleted history. Retention checks committed game and tournament status and refuses missing games, games outside active play and completed/cancelled tournaments. The existing ten-second scheduler tick also sweeps retained history for these cases, preserving active human waits even when no tournament is on auto-run.

Each model turn resolves both system prompts and compares their hashes and metadata with the active prompts. Changes to rendered templates/version, participant or shared instructions, opponent details, chat mode, banter level or resignation apply from that turn. Only affected sides **reset the cached prefix** and discard exchanges made under the old prompt; the next request starts with the new system instructions and complete current PGN/position. Unchanged prompts keep their prefix. Reasoning-effort changes can also invalidate provider caching. Prompt refresh leaves persisted moves, PGN, attempts and spend unchanged.

## Time limit per move

The time limit is optional per tournament (*Limit time per move* in the setup page; off by default and in every preset; switching it on prefills 120 seconds).

Reasoning models often need more than a minute for a move, so below 90 seconds the setup page warns that running out of time forfeits the game, and the preview's *Move Pace* reads *Tight* (below 90 s), *Balanced* (90–180 s), *Generous* (above 180 s) or *No limit*.

The runtime estimate assumes a typical answer of 30 seconds (less only for limits under 2 minutes), with about half the limit per move for the upper end.

When set, it is a hard deadline for the whole turn: from the first request until the complete answer has arrived, across all attempts of the move. A model that runs out of time forfeits on time, unless an OpenRouter problem happened earlier in the same turn (then the game pauses). The game view counts down only when there is a limit.

Without a limit a model can think as long as it needs; each request is still capped at 10 minutes (`openrouter.request-safety-cap`), and reaching that cap counts as an OpenRouter problem (retried, then paused), never as a forfeit.

API field `moveTimeoutSeconds` on `POST`/`PUT /api/tournaments`: `0` means no limit, otherwise 10–3600 seconds; omitted or null means no limit (`Tournament.DEFAULT_MOVE_TIMEOUT_SECONDS` is null, also for games outside a tournament) on create and keeps the current value on update.

Existing tournaments keep their stored limits; no data migration is needed. Tournaments without a limit return `"moveTimeoutSeconds": null`.

## Output-token limit

*Max output tokens per answer* (API field `maxOutputTokens`, 128–262144, default 16384) caps each answer, and reasoning models count their reasoning tokens against it. New tournaments and the configured fallback (`game.default-max-tokens`) use 16,384; *Quick Start Arena* and *Balanced Ladder* use 16,384, and *Series Showcase* uses 32,768. Existing tournaments keep their stored limits.

Before sending a request, the backend clamps `max_tokens` to the model's `top_provider.max_completion_tokens` when known in the cached catalog and logs any reduction; unknown models or missing limits use the configured value.

OpenRouter checks the credit balance against `max_tokens` × output price before a request, so very high limits with expensive models can fail on a low balance even if far fewer tokens are used.

An answer that is cut off at the limit without a usable legal move does not use up an attempt and never forfeits: the game pauses and reports the effective request cap.

At the model's own maximum, the pause explains that raising the tournament limit cannot help and suggests lowering reasoning effort when supported; otherwise it explains that the model cannot finish within its own output maximum.

The game view then shows a *Max output tokens per answer* field under the pause reason (prefilled with double the current limit, at most 262144 and bounded by the known model maximum, while never lowering the current tournament limit).

If the tournament limit is 32,768 and this model is capped at 8,192, the field keeps 32,768 and explains that this model is capped at its own maximum.

*Save and resume* updates the tournament limit only when the field differs from its current value, then resumes the game; an unchanged field leaves other models' output limits intact.

Without the UI, set it with `PUT /api/tournaments/{id}` and a body like `{"maxOutputTokens": 8192}`, then resume the game.

A cut-off answer that still contains a legal move is played.

### Fixing a cut-off game

When an answer was mostly reasoning (the pause reason then says "Raise the limit or lower the reasoning effort and resume"), a higher limit often only moves the problem.

Next to the limit, the pause banner offers *Reasoning effort for &lt;participant&gt;* with the levels its model supports, `low` (or the nearest supported level) pre-selected, keeping a lower current level after mapping it to a supported one.

The view refreshes participant and tournament settings when a pause is announced before offering the suggestion; *Save and resume* saves any changed limit and the participant's level, then resumes, and the model thinks less from that turn on.

When the current tournament limit already exceeds the model maximum, leaving the limit field unchanged saves only the participant's effort override; explicitly entering a different limit still updates the whole tournament.

Admin Controls → *Resume* stays disabled while those saves are pending.

Without the UI: `PUT /api/tournaments/{id}/participants/{pid}` with `{"reasoningEffort": "low"}`, then resume the game (`POST /api/games/{id}/start`).

## Reasoning effort

Reasoning models think before they answer, and OpenRouter lets a request say how much (`reasoning.effort`: `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max`).

Some models think without end at their default: in a test tournament one model with mandatory reasoning and default effort `max` used 29,998 reasoning tokens for a single move and was cut off at a 30,000-token output limit, so a larger limit alone may only postpone the cutoff; another went past 16,384. A lower effort makes such models faster and cheaper, at the price of shallower thinking.

### Setup

*Reasoning effort* under *Moves* sets the level for every model (*Model default*, the default, sends nothing).

When adding a participant, *Reasoning effort for this participant* overrides it with one of the levels the selected model lists in the catalog (*Tournament setting* keeps the tournament's level; models without effort levels show "n/a"; an explicit `supported_efforts: null` in the catalog offers every level, except `none` when reasoning is mandatory).

The model details card shows the model's *Default effort*.

### What is sent

`"reasoning": {"effort": "<level>"}` only when a level applies (the participant's override, else the tournament's) and the cached OpenRouter catalog lists effort levels for the model.

A level the model does not list is replaced by the nearest one it does (none < minimal < low < medium < high < xhigh < max; a tie goes to the lower level; `none` switches reasoning off and is used only when asked for, never for models whose reasoning is mandatory), and the backend logs it.

Models without reasoning or without effort levels get nothing; if the catalog is unavailable or does not list the model, the level is sent as is.

Each attempt in `GET /api/games/{id}/attempts` records the level actually sent (`reasoningEffort`), and the game view shows "· effort low" after a side's model id when it asks for a level.

### Changing it

Both levels can be changed at any time and apply from the next turn of running games.

API: `reasoningEffort` on `POST`/`PUT /api/tournaments` (a level, case-insensitive, or `"default"` for each model's default; omitted keeps the model default on create and the current level on update; anything else is a 400), on participants in the create request, and `PUT /api/tournaments/{id}/participants/{pid}` with `{"reasoningEffort": "low"}` for one participant (`null` follows the tournament's level again), also after the bracket was generated or while a game is paused.

If OpenRouter rejects a request because of the level (HTTP 400 or 422), the game pauses with the reason and a hint to choose another level for that participant (or *Model default* for the tournament) before resuming.

## Chat mode

Models answer with a move and an optional `message` to their opponent. The setup page's *Chat* control sets the tournament's mode:

- *Off*: models are told to leave their message empty; anything they send anyway is dropped (not stored, shown or passed to the opponent).
- *Optional* (default): models may talk or not. Whether and how they talk is an observation in itself; some models comment on every move while others stay silent.
- *Mandatory*: the prompt asks for one short message (at most about 200 characters, banter welcome, stay civil) with every move. A missing or empty message is recorded for the talkativeness metric but never rejects the move, costs an attempt or counts against reliability.

The system rules for each mode have their own prompt version: `v2-silent` (Off), `v2` (Optional) and `v2-chat-required` (Mandatory), with `+custom-instructions` before the suffix when custom instructions are added; the setup page shows the rules of the selected mode.

The mode can be changed at any time with `PUT /api/tournaments/{id}`; a running game picks it up from its next turn (both system prompts are rebuilt), and every model move records the mode that applied to it. The game view's chat panel shows the tournament's mode and follows a change live from the next model turn.

API: `chatMode` (`OFF`, `OPTIONAL`, `MANDATORY`, case-insensitive; anything else is a 400) on `POST` and `PUT /api/tournaments`.

The older `trashTalkEnabled` is still accepted: on its own, `false` means `OFF` and `true` means `OPTIONAL`; when both are sent, `chatMode` wins; when neither is sent, create uses `OPTIONAL` and update keeps the current mode.

Responses carry both `chatMode` and `trashTalkEnabled` (true unless the mode is `OFF`), and moves in `GET /api/games/{id}` carry the `chatMode` that applied to them (omitted for human moves and overrides).

The adjacent **Banter** select chooses the tone: *Original* (default, no extra guidance), *Polite* (friendly, respectful and sporting), *Playful* (light, good-natured teasing about the game) or *Spicy* (bold chess trash talk, staying civil: no slurs, harassment or real-world insults). The select is hidden while chat is *Off*; the selection is retained and applies again when chat is enabled. Presets reset it to Original. Tone is prompt guidance, not a moderation or message-validation guarantee; Optional still lets models stay silent and Mandatory still never penalizes a missing message.

API: `banterLevel` on tournament create/update accepts `POLITE`, `PLAYFUL` or `SPICY` (case-insensitive, surrounding whitespace trimmed), or JSON `null` for Original. Omitted on create means Original; omitted on update retains the current tone, while explicit `null` clears it. Blank, unknown and non-string values return 400 even with chat Off. Tournament responses and JSON export settings include nullable `banterLevel` (the current configuration, not historical per-move attribution).

For Optional and Mandatory, one tone sentence is appended to the chat rule and the version ends in `-banter-polite`, `-banter-playful` or `-banter-spicy`, after any `-resign` suffix (for example `v2+custom-instructions-chat-required-resign-banter-spicy`). Custom instructions remain additional guidance under the rules. With Original or chat Off, existing prompt texts, versions and hashes are unchanged. `GET /api/config/prompt-template` exposes these rules under `banterChatModes` and `resignationBanterChatModes`, keyed by level then chat mode; setup previews the selected combination.

Live changes apply from the next model turn; an already pending request keeps its prompt. The existing hash comparison resets each affected side's conversation prefix once, then preserves it across unchanged turns. The game chat label shows the configured tone beside the chat mode and follows live turn updates, including clearing it to Original. With chat Off it shows only the chat mode. Earlier moves, prompt metadata, messages and spend are unchanged.

A legacy full custom system prompt stored with a tournament or participant is used as is: banter guidance is not offered, and *Mandatory* counts as *Optional* for that model. The game label shows the tournament configuration even when one side uses such a legacy template.

## Resignation

Resignation is off by default, so every game is played to the end.

With *Allow resignation* in the setup page (next to *Chat*; API field `resignationAllowed` on `POST`/`PUT /api/tournaments`, a boolean, default `false`; omitted keeps the current value on update) both models may resign instead of moving, from their own 10th non-book move on (a fixed minimum, `Tournament.MIN_RESIGNATION_MOVE`). A side must have completed at least nine non-book plies; authored book moves do not count. With an eight-ply opening (four book moves per side), resignation first becomes eligible at fullmove 14, each side's tenth played move. Standard games retain the fullmove-10 minimum.

The system prompt then gets rule 9, which says that both players may resign from their own 10th move on, explicitly excluding book moves, so the opponent can resign too, and shows the answer that resigns; it states this as a rule and neither encourages nor discourages resigning or talking the opponent into it.

From the side's tenth non-book move the turn prompt adds "You may resign instead of moving (rule 9)." The same eligibility check controls whether a resignation is accepted. The turn prompt's chess move number still uses the board's fullmove number, including the opening.

### Answer format

Instead of a move, `{"resign": true, "message": "<optional last words>"}` (with chat *Off*: `{"resign": true}`); `"move"` may be omitted.

Only `true` (or the string `"true"`, in any case and with surrounding spaces) resigns, whether the JSON is the whole answer, in a code block or inside other text; a resignation in JSON wins over a move-like word elsewhere in the text, and plain text without JSON never resigns.

A move sent together with `"resign": true` is ignored: resign wins.

### Allowed resignation

The game ends as COMPLETED with result reason `RESIGNATION` and the opponent wins; in a series and in tiebreaks it counts exactly like a win by checkmate.

The message is stored as chat ("last words") under the chat-mode rules (dropped with chat *Off*).

The answer is recorded in `GET /api/games/{id}/attempts` as `RESIGNED`, with tokens, cost and any ignored move.

### Not allowed

When resignation is not offered, or before the side's 10th non-book move: if the answer also has a legal move, the move is played and the flag ignored; otherwise it is an invalid answer, recorded as `RESIGN_NOT_ALLOWED`, and retried with "Resigning is not allowed in this game; send a legal move." (or "Resigning is not allowed before your own move 10 (book moves do not count); send a legal move.").

It counts against reliability exactly like an illegal move: it uses up an attempt, makes the move that follows a retried one, and repeated it forfeits the game.

Failed attempts before an allowed resignation count too: such a turn counts in reliability like a move with that many retries (one more turn, not first-try legal), while a resignation on the first attempt changes nothing.

### Prompt versions and live changes

The rules with resignation have their own prompt version, the chat-mode version plus `-resign` (`v2-resign`, `v2-silent-resign`, `v2-chat-required-resign`, `+custom-instructions` before the suffixes), recorded with the hash on every move as before; prompts without resignation are unchanged.

A legacy custom system prompt cannot mention resignation, so resignation is not offered to such a model and its resignations are treated as not allowed.

The setting can be changed while games run; it applies from the next turn (both system prompts are rebuilt), like the chat mode.

`GET /api/config/prompt-template` lists the rules of every chat mode with resignation under `resignationChatModes`.

### Game view

The result reads "Black Bot resigned; White Bot wins", and a panel shows the resigning side's winning chance at that moment from Stockfish's evaluation of the last move ("Black Bot resigned instead of playing move 23, at 46% winning chances (not losing)"; left out while the evaluation is missing), its last words and the opponent's last message before it.

`GET /api/games/{id}` carries the same as `resignation` (`color`, `moveNumber`, `winChance` 0–1 or null, `notLosing`, `lastWords`, `opponentMessage`, `opponentMessageMoveNumber`). The bracket notes "(Black Bot resigned)" after the result.

## Adjudication

*Adjudicate decisive wins* is **off by default**. With it on, at the start of each turn a model-vs-model game ends with a win when **every one of the last 10 plies** already has a Stockfish evaluation decisive for the **same side**: either a forced mate for that side or **at least +1000 centipawns (+10 pawns)** from its view. Mate and centipawn scores may be mixed. These constants are `WinAdjudication.WINDOW_PLIES` and `DECISIVE_CP`.

Evaluations describe the position **after** each move, from White's view; negative mate or centipawn scores favor Black. Unsigned mate 0 belongs to the side that delivered checkmate. Board endings (checkmate, stalemate, repetition, fifty-move rule, insufficient material) take precedence. Fewer than 10 plies, any missing evaluation, a sub-threshold score or mixed winning sides means no adjudication that turn. The loop never waits for Stockfish, so asynchronous evaluation can postpone or prevent adjudication.

Operator overrides count as ordinary plies in this window, including their evaluations; forced moves count too. Games with a human on either side **never adjudicate**, even during a model turn. Games outside a tournament do not adjudicate.

API: `adjudicationEnabled` on tournament create/update is a boolean; omitted or null keeps false on create and the current value on update. Changes apply from the next turn, including turning it off. Adjudication completes the game with `WHITE_WINS` or `BLACK_WINS`, reason `ADJUDICATION`, through the same transactional lifecycle, status revision, broadcasts after commit and bracket/series advancement as other wins. In best-of series it is one decisive win.

The game view says "White wins by adjudication" (or Black), with a compact panel identifying the 10-ply window and its latest score from the winner's view, for example "+12.3" or "mate in 4". The game details API carries `adjudication` with `color`, `plies`, `evaluationCp` and `evaluationMate`; bracket cards treat it as a normal win.

Flyway V18 adds `tournaments.adjudication_enabled` with default false, including existing tournaments. Existing game results are never changed or backfilled.

## Move quality

Stockfish's evaluations mark inaccuracies `?!`, mistakes `?` and blunders `??` in the move list and game feed (the tooltip shows the mover's winning chances before and after, e.g. "Blunder: winning chances 74% → 22% (−6.2)"), and each player gets an average centipawn loss (ACPL) and error counts, live as evaluations arrive.

Stockfish 19 evaluates every move at depth 12 (in the background, so a badge appears a moment after the move).

Scores are stored from White's perspective: positive centipawns or mate distance favor White, negative values favor Black. UCI reports scores for the side to move, so both signs are reversed when Black is to move; mate 0 denotes checkmate already on the board.

Stockfish 19 can exit on invalid FEN or UCI input. An evaluation interrupted by process exit, EOF or a broken pipe returns no score, discarding partial output. The next queued evaluation attempts one fresh engine start without replaying the failed position. Requests arriving while the replacement completes its handshake wait in the queue and are evaluated after recovery. If that start fails, queued requests return no score and subsequent requests return no score immediately until the backend restarts; games continue. Readiness stays false until the replacement returns `uciok`. The complete handshake is limited to 10 seconds, and each evaluation to 30 seconds, including command writes and all output reads. A timeout discards partial scores, stops the process and returns no score for current and queued requests; subsequent requests return immediately until backend restart. Shutdown cancels active I/O and drains queued requests with no score. A missing engine at initial startup also leaves evaluation unavailable. Existing stored evaluations are unchanged by the upgrade.

A move is judged by how much it lowers the mover's winning chances, using the win-probability model Lichess uses: an evaluation in centipawns (capped at ±10 pawns) becomes winning chances via `2 / (1 + exp(−0.00368208 · cp)) − 1` on a −1…+1 scale, and a forced mate counts as a certain win or loss.

A drop of at least 0.1, 0.2 or 0.3 (5, 10 or 15 percentage points of winning chances) is an inaccuracy `?!`, a mistake `?` or a blunder `??`, so swings in already decided positions barely count while throwing away a win does.

Centipawn loss is the mover's evaluation drop per move, capped at 1000 (a mate counts as ±1000); ACPL is its average. The start position counts as equal.

Moves without an evaluation before and after them (Stockfish unavailable or not done yet), operator overrides and forced moves (the only legal move) are not judged and do not count.

In analytics, ACPL and blunders per game cover finished games only (byes excluded); blunders per game divides by the games in which the model has at least one judged move.

Depth-12 evaluations are a quick check, not deep analysis.

### Missed mates

The move list always marks a **missed mate** with `M×` and a tooltip such as "Missed mate in 2", independently of the adjudication option and ordinary `?!` / `?` / `??` judgement. A move misses mate when the position before it had a forced mate **for the mover**, and the position after it no longer has a forced mate for that mover. Keeping any forced mate, even a longer one, or delivering mate 0 keeps the mate. A high centipawn score is not a forced mate: missing mate while staying clearly winning still gets the marker.

Both surrounding evaluations must be available. Operator overrides and forced moves are excluded just as for move quality; no marker or missed-mate count is assigned to them. `MoveQualityDto` adds `missedMate` and `missedMateIn` (the former distance, omitted without a missed mate), including qualities delivered by live evaluation updates. These are derived on read from existing evaluations; no evaluation or game result is rewritten.

## Analytics and reliability

Analysis includes a bracket overview with series scores and the champion, PGN export, and analytics on results, move quality (ACPL, blunders per game), talkativeness (how often each model talks, per chat mode), resignations (including resignations while not losing), reliability (first-try legal moves, retries, timeouts, forfeits) and reported cost.

The analytics page shares its analysis window, tournament and minimum-games filters across four tabs (the minimum-games filter applies to the comparison and reliability data; the engine-health cards and Per-Model Performance Metrics count every model regardless of it):

- **Overview:** leader cards, the Model Comparison Table, Color Split, Failure Profile and Cost Efficiency.
- **Play quality:** Move Quality and Conversion.
- **Behaviour:** Talkativeness and Resignations.
- **Cost & reliability:** six cost, engine-health and reliability cards, followed by Per-Model Performance Metrics, Prompt caching and Reliability Ranking & Stability. Cards keep their places while each data source loads independently.

Link directly to a tab with `?tab=overview`, `?tab=quality`, `?tab=behaviour` or `?tab=cost`. Missing or unknown values open Overview. Switching tabs replaces the URL without adding browser-history entries; shared filters and the existing data-refresh schedule stay in place.

Every model answer is stored, including rejected ones: the game feed lists failed attempts per move (for example "2 failed attempts: illegal Qxh7, unparseable answer") and, separately, answers cut off at the token limit, which are not counted as the model's failures, and `GET /api/games/{id}/attempts` returns them with tokens, cost and finish reason.

Game, tournament and per-model costs include rejected answers. Costs are as reported by OpenRouter.

### Finished games and reliability score

Only finished games count as games played; byes and games that are waiting, running, paused or cancelled do not.

A time window selects finished games by when they finished and unfinished ones by when they were created; moves and attempts follow their games.

The reliability score (0–100) is based on finished games: 50% first-try legal moves (moves played without a retry, out of all moves plus turns lost to an invalid-move forfeit and resigned turns that needed retries), 30% games not forfeited and 20% few retries (100 minus 50 per average retry).

With fewer than 60 moves it is pulled toward 60 in proportion to the moves played, and below 3 games it is marked *Insufficient data*.

Latency is shown but not scored, so slow reasoning models are not penalised for thinking; answers cut off at the token limit never count.

Per-model cost is the cost of accepted moves plus the cost of rejected answers whenever OpenRouter reports one (including answers cut off at the token limit and provider errors: real spend, not blame); a model whose only answers were rejected still shows its total cost.

### Conversion metrics

The *Conversion* table uses the same completion-time window, tournament and minimum-games filters as other comparison tables: **finished, non-bye games, model sides only**. Each model side in a game counts once:

- **Winning positions:** games where that side had **at least 90% winning chances** at any stored evaluated position, using the [move-quality probability model](#move-quality). Every evaluated point counts, including an opponent's move, a forced move or an override; the side need not have made a move yet. Repeated winning positions in one game add no extra count.
- **Converted:** of those games, games the model won, including checkmate, resignation, opponent forfeits and adjudication.
- **Adjudicated wins:** the converted games won by adjudication, also included in Converted.
- **Thrown away:** of the games with a winning position, games that ended in a draw or a loss (including the model's own forfeit).
- **Missed mates:** the number of judged model moves that [missed a forced mate](#missed-mates), independently of whether the game reached the winning-position threshold or was ultimately won.

The table shows evaluated-game coverage per model. Games with no evaluations cannot prove a winning position and are left out; a model with no evaluated games shows **n/a** for position/outcome counts. Missed mates show **n/a** without any judged moves. Observed zero counts stay zero. Partial coverage describes the available evaluations, not every position of a game. With self-play, each model side counts separately, as in the other tables.

Comparison API rows carry `conversion` with `evaluatedGames`, `winningPositionsReached`, `converted`, `adjudicatedWins`, `thrownAway` and `missedMates`; unknown counts may be omitted. All metrics derive from stored evaluations, including historical games; there is no backfill or engine re-analysis.

### Talkativeness

Tone grouping/filtering is deferred to the banter bench. The current tournament setting cannot accurately label historical moves after live changes; this feature adds no per-move tone backfill.

The analytics page's *Talkativeness* table shows, per model and for chat *Optional* and *Mandatory* separately, the share of its moves that came with a non-empty message (*Talk rate*; with *Mandatory* this is a compliance rate, with *Optional* the model's own choice) and the average message length in characters.

Like the other tables it covers model moves in finished games (byes, human moves and overrides excluded); moves made with chat *Off* are left out, and a mode without moves shows "n/a".

A move counts as talking when a chat message was stored for the same game, move number and colour; a ply stored twice (possible in databases from before V8) counts once.

Comparison API rows carry `optionalChat` and `mandatoryChat` with `moves`, `messages`, `talkRate` and `averageMessageLength` (the last two omitted without moves or messages).

### Prompt caching metrics

The *Prompt caching* table shows per-model prompt tokens, cached prompt tokens (cache reads), cache hit rate (`cached / prompt`), cache writes and estimated savings in USD. It uses all recorded usage-bearing attempts, including accepted moves, retries, truncated answers, provider errors, discarded answers and resignations. The same completion-time window, tournament and minimum-games filters as the other comparison tables apply: finished, non-bye games only. Attempts without usage are left out; tokens are not counted again from moves.

OpenRouter's `usage.prompt_tokens_details.cached_tokens` and `cache_write_tokens` are stored separately on each attempt as nullable `cached_prompt_tokens` and `cache_write_tokens`; the attempts API exposes `cachedPromptTokens` and `cacheWriteTokens`. Reported zero means zero. A total is **n/a** if any included usage-bearing attempt lacks that counter, or there are no such attempts. Read and write completeness are independent; hit rate also needs a known, positive prompt-token total. Historical attempts without cache counters therefore show **n/a**, including a window mixing historical and newly reported counters. Games from before attempt recording cannot contribute usage totals; these metrics describe recorded attempts only.

Estimated savings are `cached tokens × (pricing.prompt − pricing.input_cache_read)` using the **current** OpenRouter catalog's USD **per-token** prices. Missing, invalid, variable or unavailable prices mean **n/a**; a reported price of zero is valid. This is an estimate of the cache-read discount, not a historical billing reconstruction or net savings after cache-write charges. Cache writes are measured separately.

Each comparison uses one cached catalog price snapshot for every model row and never waits for catalog I/O. With an empty or expired cache, a single background refresh updates prices for later comparisons; an existing stale snapshot remains usable. Failed refreshes wait 60 seconds after failure before another attempt, including when there is no cached catalog. A cold-cache comparison shows **n/a** for savings until prices become available and the comparison cache (15 seconds) expires.

All existing costs continue to use OpenRouter's returned `usage.cost`, which already reflects provider billing (including cache reads and writes); the app does not recalculate billed costs from catalog list prices. Comparison API rows add `promptCaching` with `promptTokens`, `cachedPromptTokens`, `cacheHitRate`, `cacheWriteTokens` and `estimatedSavingsUsd`; unknown values may be omitted. These metrics also measure the [append-only prompt window and cache breakpoints](#prompt-caching); older attempts and their recorded counters remain unchanged.

### Resignations in analytics

The *Resignations* table lists per model how often it resigned (and the share of its games), how often it *resigned while not losing* and how often it *won by resignation* (its opponent resigned).

Not losing means the resigning side still had at least 35% winning chances (on the move-quality win-probability model) in the position it gave up, which is the position after the last move played, as evaluated by Stockfish: roughly level or better.

Resignations without that evaluation are counted but not judged ("n/a", "1 not evaluated").

Same scope as the other tables: finished, non-bye games; human sides are left out.

Comparison API rows carry `resignations`, `resignationsEvaluated`, `resignationsWhileNotLosing` and `wonByResignation`.

### Provider problems and resuming games

OpenRouter outages, rate limits or an invalid API key pause the game with the reason instead of deciding it; so does an answer that is cut off at the output-token limit. Games interrupted by a restart can be resumed.

Recognized OpenRouter failures (rate limits, server or network errors, invalid key, no credits) are retried or pause the game with the reason, and the game can be resumed. A request that simply exceeds the time limit per move still counts as the model's timeout; one that exceeds the technical 10-minute safety cap does not.

## Board colours

In the game view, use **Board** beside the flip and export controls to choose **Green** (default), **Blue-grey** or **Wood**. The choice is saved in this browser, applies to boards and captured-piece trays, and stays independent of light/dark mode; unavailable storage keeps it for the current session. See the [design system](design-system.md#board-themes) for the shared visual rules.

## Export

Use **PGN**, **CSV** or **JSON** on the tournament page to download your data. The download icon in the game view exports that game's PGN. Exports read existing records without calling a model or Stockfish, changing results or requiring a migration/backfill.

| Endpoint | Contents |
|---|---|
| `GET /api/games/{id}/pgn` | One annotated game, compatible with the existing download icon. |
| `GET /api/tournaments/{id}/export.pgn` | Games concatenated as a PGN collection, excluding byes. |
| `GET /api/tournaments/{id}/export.csv` | One row per game, including byes and future bracket placeholders. |
| `GET /api/tournaments/{id}/export.json` | Versioned tournament settings, participants and full analysis data. |

All downloads are UTF-8 attachments with UUID-based filenames such as `tournament-<id>.csv` and `game-<id>.pgn`. Missing IDs return 404. An empty tournament produces an empty PGN, a CSV header, or JSON with empty participant/game arrays. Games are ordered by creation time, then bracket round/position, series game and ID; plies use move number with White before Black. As with move quality, legacy duplicate plies use the earliest stored row, with ID breaking timestamp ties; stored rows are unchanged.

### PGN annotations

PGN includes the Seven Tag Roster: tournament name as `Event`, `Site` = `AI Chess Arena`, the stored start date (creation date if not started), numeric bracket round as `Round` (`1`, `2`, `3`, counting from the opening round), display names as `White`/`Black`, and `Result`. Unknown names or unrecognized round labels use `?`. Bracket size comes from the participant count rounded up to a power of two, falling back to stored bracket sizes. `BracketRound` retains the human label (for example `Final` or `Tiebreak 1`), and `SeriesGame` retains the series game number (for example `2`). The round and series components are separate because chesslib imports only integer `Round` values; this preserves the imported bracket round even in a series. `WhiteModel`/`BlackModel` carry model IDs when present. Book openings include `Opening` and `ECO`; book plies have `{book}` comments. `TimeControl` is `-` without a limit or `1/120` for a 120-second per-move limit: one move per 120-second period, repeating, rather than an hourglass clock. It reflects the tournament's current setting; settings history is not reconstructed.

Evaluated plies carry White-perspective pawn scores such as `{[%eval 0.35]}` or mate distances such as `{[%eval #-3]}`. Mate zero means checkmate on the board (`#0` for White's mate, `#-0` for Black's). Missing evaluations have no comment. Standard `Termination` values are `normal` (including resignation and board draws), `adjudication`, `time forfeit`, `rules infraction` (other forfeits), `abandoned` (cancelled) or `unterminated`. `TerminationDetails` retains the arena reason, for example `resignation`, `forfeit_invalid_moves` or `cancelled`.

Finished games keep `1-0`, `0-1` or `1/2-1/2`. Waiting, running, paused and cancelled games are included with `*`, preserving any recorded moves; future pairings have unknown players. Downloads omit chat by default. Add `?includeChat=true` to either PGN URL for per-move chat comments and any final/unmatched messages, including resignation last words. Braces in chat become parentheses, control/format characters become spaces, and annotation introducers `[%` become `[ %` so chat cannot create evaluation or clock directives. Movetext and comments wrap to fewer than 80 characters per line; unusually long chat words are split inside their comment. Tag pairs, SAN and genuine evaluation tokens remain intact. Long tag values are not wrapped. Quotes and backslashes in tag values are escaped. These conventions follow the [PGN specification](https://www.saremba.de/chessgml/standards/pgn/pgn-complete.htm).

### CSV and JSON analysis

CSV columns are `game_id`, `round`, `bracket_position`, `series_game`, `white_model`, `white_name`, `black_model`, `black_name`, `result`, `termination`, `termination_details`, `plies`, `cost_usd`, `white_acpl`, `black_acpl`, `white_blunders`, `black_blunders`, `opening`, `eco`. Plies include book moves; ACPL and blunders use the existing move-quality exclusions, including book moves. An unavailable ACPL is empty. Fields use RFC 4180 quoting, doubled quotes and CRLF row endings. Text beginning with `=`, `+`, `-` or `@` (also after whitespace/control characters) gets a leading apostrophe to prevent spreadsheet formulas. Numerical cells retain their values. Chat is carried in JSON or optional PGN comments, not CSV summary rows.

JSON has `schemaVersion: 1`, tournament `id`/`name`/`status`/timestamps, `settings`, `participants`, `games` and `totalCostUsd`. Settings include format, draw/series rules, move/attempt/token limits, chat/resignation/adjudication, requested reasoning effort, opening settings and auto-run limits/status. Participants include IDs, display/model names, control type, seed and effort override. Games contain bracket/series links, side/advancing participant IDs, status/result/reason/termination, FEN, opening metadata, timestamps, canonical `moves` with evaluations and derived `quality`, `qualitySummary`, `chatMessages`, per-attempt summaries and `totalCostUsd`. Attempt summaries include outcome, ply, model, token/cache/reasoning usage, effective token limits, effort actually sent, latency, reported cost and timestamp, including failed and discarded attempts. Missing nullable settings are explicit `null`; nullable nested move/quality/chat fields follow the regular API's omission convention.

Exports use allowlisted scalar projections with database cursors fetching 128 rows at a time. Tournament queries bulk-fetch moves, chat (when requested) and attempts (JSON only), grouping them by game; SQL statement counts do not grow with the number of games. No managed persistence entities, raw provider answers or private prompts are loaded for exports. Response construction still uses memory proportional to the exported allowlisted data. JSON uses an explicit field list: credentials, runtime configuration, prompt templates/custom instructions, raw provider answers, provider error/status-detail text and client request IDs are excluded. Display names and chat remain user/model text; review those before sharing. Game and tournament totals use stored reported spend, including failed/discarded attempts, rather than adding attempts to move costs again. Exports are current reads: running games and pending evaluations can change during/after a download, so export after completion and evaluation for a settled analysis. No import/restore format is provided.

## Leaderboard

Open **Leaderboard** next to Analytics to compare models across all tournaments and all time. Each OpenRouter model id starts at **1500 Elo**. Expected score is `1 / (1 + 10^((opponent rating - rating) / 400))`; the update is `K × (actual score - expected score)`. K is **32 for that model's first 30 rated appearances**, then **16**. Both sides use their pre-game ratings and their own K; calculations keep full precision and only displayed ratings are rounded. Rows rank by the unrounded rating, then model id, so equal displayed ratings may have different ranks.

Finished model-vs-model games count, including checkmate, resignation, adjudication, draws (½ point each) and forfeits (a loss for the forfeiting side). **Include forfeits** is on by default; turning it off replays the entire history using only over-the-board results, changing ratings, game counts, records and last played together. The Forfeits column counts the model's own forfeited losses in the selected sample. Byes, cancelled games, games with either human side and unfinished games are excluded. Finished games in a cancelled tournament still count. A result without a completion timestamp is excluded because it cannot be ordered reliably.

Games replay chronologically by `completedAt`, then lexicographic game id. Model identity is the exact OpenRouter id (surrounding whitespace is trimmed), independent of participant nicknames, instructions or reasoning effort. Display names use the final segment of the id. Moving ids such as `~anthropic/claude-sonnet-latest` remain separate from concrete versions and carry a **Latest alias** badge: their results may span different underlying versions. **Provisional** means fewer than 10 rated appearances. Self-play counts both sides as appearances, with one win and one loss (or two draws), no net rating change and one history point for the game. Rating uses stored game outcomes, so duplicate move rows and book moves cannot add rating events; opening choice has no separate rating adjustment.

Sparklines show the last **50 post-game ratings** per model, each scaled independently to show its own trend. Earlier games still contribute to the current rating. Only models with rated games appear. Use **Refresh** after games finish; every API request recomputes from stored results without a cache, model-catalog lookup or move-history scan. ACPL and first-try legality remain on the existing **Analytics** page, linked above the table; those tables have their own date and tournament filters. Arena Elo reflects this local sample and its opponents and settings; it is not a general chess or model benchmark.

API: `GET /api/analytics/leaderboard?includeForfeits=true|false` (default `true` when absent) accepts case-insensitive `true` or `false`; any other supplied value, including an empty string, returns HTTP 400 with `{"error":"includeForfeits must be true or false"}`. A successful response contains `includeForfeits`, `gamesCount` (distinct rated games) and ranked `models`. Each row carries `modelId`, `displayName`, rounded `rating`, `games`, `wins`, `draws`, `losses`, `forfeits`, `lastPlayed`, `provisional`, `alias` and `ratingHistory`. Each history point has `gameId`, `completedAt` and rounded `rating`, oldest first, capped at 50 per model. No persistence, migration or backfill is needed; existing data stays unchanged. Deploying the view requires rebuilding both backend and frontend.

## Live settings

Live games over WebSocket show the board, move list, model chat, retries, and an optional Stockfish 19 evaluation bar.

Background refreshes preserve the historical position being reviewed; viewers at the latest move keep following new moves.

Tournament settings changed with `PUT /api/tournaments/{id}` while a game is running (time limit, attempts, output-token limit, chat mode, banter level, resignation, adjudication, reasoning effort) apply from its next turn, and so does a participant's reasoning-effort override. Chat-mode, active banter and resignation changes rebuild affected system prompts; unchanged prompts retain their prefix.

## Scope and interpretation

- Built for **one operator on localhost**: browser claiming protects key settings; there is no general login or user account. Keep the supported loopback boundary; shared-network and public internet exposure remain unsupported.
- A TLS-terminating reverse proxy in front of the frontend is not supported: the backend sees plain HTTP and rejects the browser's `https://` origin with 403.
- One human participant per tournament; single elimination only.
- Not a public chess site, a hosted service, or an engine-research suite.
- The UI targets desktop browsers (roughly 1280 px wide and up); phones and tablets are not supported.

### Host and port behavior

Everything binds to `127.0.0.1` only. To use another port, set `FRONTEND_PORT` in `.env`; any port works, because the bundled nginx serves the UI and proxies `/api` and `/ws` from the same origin (`quarkus.http.cors.origins` only matters for a frontend hosted separately from the backend; the Angular dev server on `:4200` is already listed).

The frontend answers only to `localhost`, `127.0.0.1` and `[::1]` and returns 421 for any other host name, which blocks DNS-rebinding attacks from web pages; further names go in `ALLOWED_HOSTS` (see [docs/deployment.md](deployment.md#host-names-allowed_hosts)).

Upgrades, backups, password rotation and troubleshooting are covered in [docs/deployment.md](deployment.md).

### Interpreting results

New model releases arrive with impressive benchmark numbers. A chess bracket is a cheap, informal second look: every move is checked for legality, and retries, timeouts and forfeits are recorded.

A model that tops the leaderboards but cannot get through a game without invalid moves is worth a closer look.

Treat the results as a smell test, not a benchmark: the prompt supplies the position and the legal moves, brackets are small, games are few, and chess is one narrow skill — it cannot tell you whether benchmark gains generalize.
