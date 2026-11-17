# How AI Chess Arena works

Detailed rules, settings and API conventions for running tournaments. For setup and development commands, see the [README](../README.md); for upgrades and operations, see the [deployment guide](deployment.md).

## Contents

- [Tournament formats and draw policies](#tournament-formats-and-draw-policies)
- [Opening variety](#opening-variety)
- [Cancelling a tournament](#cancelling-a-tournament)
- [Choosing participants and model picker](#choosing-participants-and-model-picker)
- [Model turns and prompts](#model-turns-and-prompts)
- [Time limit per move](#time-limit-per-move)
- [Output-token limit](#output-token-limit) and [fixing a cut-off game](#fixing-a-cut-off-game)
- [Reasoning effort](#reasoning-effort)
- [Chat mode](#chat-mode)
- [Resignation](#resignation)
- [Adjudication](#adjudication)
- [Move quality](#move-quality) and [missed mates](#missed-mates)
- [Conversion metrics](#conversion-metrics)
- [Analytics and reliability](#analytics-and-reliability)
- [Leaderboard](#leaderboard)
- [Live settings](#live-settings)
- [Scope and interpretation](#scope-and-interpretation)

## Tournament formats and draw policies

Tournaments are single elimination with best-of 1, 3, 5 or 7 series and a separate setting for the final.

### Bracket

Before generating the bracket on the tournament page, choose **Bracket draw**:

- **Random draw** (the UI default): shuffle the participants once and assign new seeds starting at 0 in the drawn order.
- **In added order**: keep the existing seeds (the setup page assigns these in the order you add participants).

Participants are paired in seed order, lowest first. When the field is not a power of two, the lowest seed numbers receive byes; the rest are paired in seed order. For example, with five participants, seeds 0, 1 and 2 receive byes and seeds 3 and 4 play. The **Participants by seed** panel shows the stored seeds after generation. A random draw can happen to produce the original order.

API: `POST /api/tournaments/{id}/generate-bracket?drawMode=RANDOM` requests a random draw; `drawMode=ORDER` or omitting the parameter preserves the existing seed order for compatibility. No request body is required; an empty JSON object also works. An invalid mode returns 400. Generation stores the drawn seeds and bracket together. Repeated or concurrent generation requests return 409 after the first succeeds; they cannot reshuffle an existing bracket. Existing generated brackets remain unchanged, and no migration or backfill is needed.

### Best of N

A series lasts at most N games. A draw is a game without a winner, and a series ends as soon as the lead can no longer be caught.

### Draw policies

When level after N games (or a drawn single game), the tournament's draw policy decides — *White advances* (white in game 1), *Black advances*, *Higher seed advances*, *Random*, or *Replay game*.

*Higher seed advances* uses the lower stored seed number, including the newly assigned seeds after a random bracket draw. This tiebreak policy is separate from the **Bracket draw** choice.

*Replay game* adds sudden-death tiebreak games (colors alternating) until one is decisive; this also applies to a single-game match, where the drawn game stays in the series with its moves, chat and cost and is followed by "Tiebreak game 1", 2, and so on. It has no fixed maximum length or cost.

### Cost estimate

The **cost estimate** before you start shows expected games, runtime and spend for the chosen models and format.

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

The setup page loads every text-output model from the OpenRouter catalog once (refreshed by the backend every 5 minutes).

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

Each model gets its system prompt, its last few exchanges and the current turn; older turns are not resent because every turn prompt contains the moves so far (PGN) and the full position (FEN, board, legal moves).

Tournament settings changed with `PUT /api/tournaments/{id}` while a game is running (time limit, attempts, output-token limit, chat mode, resignation, adjudication, reasoning effort) apply from its next turn, and so does a participant's reasoning-effort override.

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

A legacy custom system prompt stored with a tournament or participant is used as is and cannot ask for a message, so for such a model *Mandatory* counts as *Optional*.

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

The analytics page's *Talkativeness* table shows, per model and for chat *Optional* and *Mandatory* separately, the share of its moves that came with a non-empty message (*Talk rate*; with *Mandatory* this is a compliance rate, with *Optional* the model's own choice) and the average message length in characters.

Like the other tables it covers model moves in finished games (byes, human moves and overrides excluded); moves made with chat *Off* are left out, and a mode without moves shows "n/a".

A move counts as talking when a chat message was stored for the same game, move number and colour; a ply stored twice (possible in databases from before V8) counts once.

Comparison API rows carry `optionalChat` and `mandatoryChat` with `moves`, `messages`, `talkRate` and `averageMessageLength` (the last two omitted without moves or messages).

### Prompt caching

The *Prompt caching* table shows per-model prompt tokens, cached prompt tokens (cache reads), cache hit rate (`cached / prompt`), cache writes and estimated savings in USD. It uses all recorded usage-bearing attempts, including accepted moves, retries, truncated answers, provider errors, discarded answers and resignations. The same completion-time window, tournament and minimum-games filters as the other comparison tables apply: finished, non-bye games only. Attempts without usage are left out; tokens are not counted again from moves.

OpenRouter's `usage.prompt_tokens_details.cached_tokens` and `cache_write_tokens` are stored separately on each attempt as nullable `cached_prompt_tokens` and `cache_write_tokens`; the attempts API exposes `cachedPromptTokens` and `cacheWriteTokens`. Reported zero means zero. A total is **n/a** if any included usage-bearing attempt lacks that counter, or there are no such attempts. Read and write completeness are independent; hit rate also needs a known, positive prompt-token total. Historical attempts without cache counters therefore show **n/a**, including a window mixing historical and newly reported counters. Games from before attempt recording cannot contribute usage totals; these metrics describe recorded attempts only.

Estimated savings are `cached tokens × (pricing.prompt − pricing.input_cache_read)` using the **current** OpenRouter catalog's USD **per-token** prices. Missing, invalid, variable or unavailable prices mean **n/a**; a reported price of zero is valid. This is an estimate of the cache-read discount, not a historical billing reconstruction or net savings after cache-write charges. Cache writes are measured separately.

Each comparison uses one cached catalog price snapshot for every model row and never waits for catalog I/O. With an empty or expired cache, a single background refresh updates prices for later comparisons; an existing stale snapshot remains usable. Failed refreshes wait 60 seconds after failure before another attempt, including when there is no cached catalog. A cold-cache comparison shows **n/a** for savings until prices become available and the comparison cache (15 seconds) expires.

All existing costs continue to use OpenRouter's returned `usage.cost`, which already reflects provider billing (including cache reads and writes); the app does not recalculate billed costs from catalog list prices. Comparison API rows add `promptCaching` with `promptTokens`, `cachedPromptTokens`, `cacheHitRate`, `cacheWriteTokens` and `estimatedSavingsUsd`; unknown values may be omitted. This step only measures caching: prompts, request messages and `cache_control` are unchanged.

### Resignations in analytics

The *Resignations* table lists per model how often it resigned (and the share of its games), how often it *resigned while not losing* and how often it *won by resignation* (its opponent resigned).

Not losing means the resigning side still had at least 35% winning chances (on the move-quality win-probability model) in the position it gave up, which is the position after the last move played, as evaluated by Stockfish: roughly level or better.

Resignations without that evaluation are counted but not judged ("n/a", "1 not evaluated").

Same scope as the other tables: finished, non-bye games; human sides are left out.

Comparison API rows carry `resignations`, `resignationsEvaluated`, `resignationsWhileNotLosing` and `wonByResignation`.

### Provider problems and resuming games

OpenRouter outages, rate limits or an invalid API key pause the game with the reason instead of deciding it; so does an answer that is cut off at the output-token limit. Games interrupted by a restart can be resumed.

Recognized OpenRouter failures (rate limits, server or network errors, invalid key, no credits) are retried or pause the game with the reason, and the game can be resumed. A request that simply exceeds the time limit per move still counts as the model's timeout; one that exceeds the technical 10-minute safety cap does not.

## Leaderboard

Open **Leaderboard** next to Analytics to compare models across all tournaments and all time. Each OpenRouter model id starts at **1500 Elo**. Expected score is `1 / (1 + 10^((opponent rating - rating) / 400))`; the update is `K × (actual score - expected score)`. K is **32 for that model's first 30 rated appearances**, then **16**. Both sides use their pre-game ratings and their own K; calculations keep full precision and only displayed ratings are rounded. Rows rank by the unrounded rating, then model id, so equal displayed ratings may have different ranks.

Finished model-vs-model games count, including checkmate, resignation, adjudication, draws (½ point each) and forfeits (a loss for the forfeiting side). **Include forfeits** is on by default; turning it off replays the entire history using only over-the-board results, changing ratings, game counts, records and last played together. The Forfeits column counts the model's own forfeited losses in the selected sample. Byes, cancelled games, games with either human side and unfinished games are excluded. Finished games in a cancelled tournament still count. A result without a completion timestamp is excluded because it cannot be ordered reliably.

Games replay chronologically by `completedAt`, then lexicographic game id. Model identity is the exact OpenRouter id (surrounding whitespace is trimmed), independent of participant nicknames, instructions or reasoning effort. Display names use the final segment of the id. Moving ids such as `~anthropic/claude-sonnet-latest` remain separate from concrete versions and carry a **Latest alias** badge: their results may span different underlying versions. **Provisional** means fewer than 10 rated appearances. Self-play counts both sides as appearances, with one win and one loss (or two draws), no net rating change and one history point for the game. Rating uses stored game outcomes, so duplicate move rows and book moves cannot add rating events; opening choice has no separate rating adjustment.

Sparklines show the last **50 post-game ratings** per model, each scaled independently to show its own trend. Earlier games still contribute to the current rating. Only models with rated games appear. Use **Refresh** after games finish; every API request recomputes from stored results without a cache, model-catalog lookup or move-history scan. ACPL and first-try legality remain on the existing **Analytics** page, linked above the table; those tables have their own date and tournament filters. Arena Elo reflects this local sample and its opponents and settings; it is not a general chess or model benchmark.

API: `GET /api/analytics/leaderboard?includeForfeits=true|false` (default `true`) returns `includeForfeits`, `gamesCount` (distinct rated games) and ranked `models`. Each row carries `modelId`, `displayName`, rounded `rating`, `games`, `wins`, `draws`, `losses`, `forfeits`, `lastPlayed`, `provisional`, `alias` and `ratingHistory`. Each history point has `gameId`, `completedAt` and rounded `rating`, oldest first, capped at 50 per model. No persistence, migration or backfill is needed; existing data stays unchanged. Deploying the view requires rebuilding both backend and frontend.

## Live settings

Live games over WebSocket show the board, move list, model chat, retries, and an optional Stockfish 19 evaluation bar.

Tournament settings changed with `PUT /api/tournaments/{id}` while a game is running (time limit, attempts, output-token limit, chat mode, resignation, adjudication, reasoning effort) apply from its next turn, and so does a participant's reasoning-effort override. Chat-mode and resignation changes rebuild both system prompts.

## Scope and interpretation

- Built for **one operator on localhost**: no authentication, no user accounts. Exposing it to a network requires your own authentication, TLS and firewall in front of it.
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
