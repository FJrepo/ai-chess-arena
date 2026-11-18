# Benchmark guide

Which setup answers which question. Each recipe names the page, the exact settings, what to read in the results, the caveats and the relative cost. For rules and definitions, see [How it works](how-it-works.md); the [Stockfish ladder](how-it-works.md#stockfish-ladder) and [Rerun and compare](how-it-works.md#rerun-and-compare) sections describe the benchmark pages in detail.

| I want to test… | Choose | Page |
|---|---|---|
| How well a model plays chess | [Raw playing strength](#raw-playing-strength) | Benchmarks |
| Whether it can find legal moves on its own | [Board understanding](#board-understanding) | Benchmarks |
| Whether a higher reasoning effort pays off | [Is more effort worth it?](#is-more-effort-worth-it) | Benchmarks, then Rerun |
| What a result costs | [Cost efficiency](#cost-efficiency) | Benchmarks |
| Whether it answers in format with legal moves | [Reliability and format discipline](#reliability-and-format-discipline) | New tournament |
| How it talks and whether it talks opponents out of games | [Personality and persuasion](#personality-and-persuasion) | New tournament |
| Whether it finishes a won game | [Can it finish a won game?](#can-it-finish-a-won-game) | New tournament |
| How fast it answers | [How fast does it answer?](#how-fast-does-it-answer) | New tournament |
| How much results move by chance | [Variance check](#variance-check) | Rerun |

## Using a recipe

Choose **Recipe** on the **Benchmarks** form or **Test recipe** under *Tournament Settings* on a new tournament. The recipe fills in the settings listed under *Recipe settings* below and links back here; it never creates or starts anything. Everything else, such as the run name, models or participants, budget and concurrency, stays as you entered it, and you can still change every field. After you change a recipe setting, the form says so. The recipes are defined once in [`benchmark-recipes.json`](../frontend/src/app/utils/benchmark-recipes.json); a test checks that this guide lists the same settings.

Every ladder run uses the same fixed conditions: eight openings, each played with both colours against both Stockfish levels (32 games per model), no move time limit (each request is still capped at 10 minutes, which pauses the game instead of forfeiting it), three attempts per move and an output limit of 16,384 tokens per answer. That limit is a ceiling: a model whose catalog maximum is lower gets its own maximum. An answer cut off at the limit is still played if it contains a legal move; only an unusable cut-off answer pauses the game.

## Read this first

- **Not a rating.** The ladder reports performance against this Stockfish setup. The level numbers are Stockfish settings; do not convert a score into Elo or compare it with ratings from other sites.
- **Small samples.** 32 games per model per run is enough to separate a model that scores 80% from one that scores 20%, not to rank two models a few points apart. Run a [variance check](#variance-check) before trusting a small difference.
- **Effort is a request, not a budget.** Each provider maps `low`, `medium` or `high` to its own thinking budget, and a level a model does not list is replaced by the nearest one it does. Models without effort levels get nothing. Compare *reasoning tokens per move*, not effort labels, across providers.
- **Reasoning tokens are provider-reported.** Some providers do not report them; the report then shows a dash rather than zero.
- **Pin model IDs.** A moving alias such as `~provider/model-latest` can resolve to a different model during or between runs; the report lists every concrete ID it saw.
- **The budget is a pause point, not a billing limit.** Requests already in flight can still add cost after the run pauses.
- **Displayed spend can be incomplete.** A game paused for a spend check has a request whose cost may not be recorded. Check the request in your OpenRouter activity before choosing **Spend checked, resume**. Arena's displayed total still omits any charge it failed to record. Account for the missing charge from OpenRouter activity, or exclude the matchup from points-per-dollar comparisons.

## Raw playing strength

**Question:** how many points does each model score against a fixed Stockfish setup when it is told the legal moves?

**Where:** Benchmarks → Recipe → *Raw playing strength*.

**Recipe settings:** Legal move hints **on** · Reasoning effort **Medium** · Chat mode **Off** · Stockfish levels **1500** and **2000**

Add one to eight model IDs and a budget. A fixed effort keeps the thinking request the same for every model; chat off keeps the answers short.

**Read:** *Model results* → **Score** (points out of scored games), **Games** (scored out of planned) and **Forfeit losses**. *Score by level and color* shows whether a model only beats the lower level or struggles as Black. *Opening pairs* links every game.

**Caveats:** two levels give a coarse picture; if every model scores near 0% or 100%, choose levels further down or up. Forfeits count as losses, so a weak score can be a reliability problem; check *Forfeit losses* first.

**Cost:** the baseline for the other ladder recipes. Cost grows linearly with the number of models.

## Board understanding

**Question:** can the model find legal moves from the position without being given the list?

**Where:** Benchmarks → Recipe → *Board understanding*, or better: rerun a *Raw playing strength* run with **Setting to change** set to **Legal move hints**, then compare.

**Recipe settings:** Legal move hints **off** · Reasoning effort **Medium** · Chat mode **Off** · Stockfish levels **1500** and **2000**

**Read:** *Model results* → **Score** and **Forfeit losses**; the game pages list each rejected answer (for example an illegal move). In the comparison with the hints-on run, the **Score delta** and **First-try legal** show how much the list helped.

**Caveats:** hints off adds `-no-hints` to the prompt version. Compare it only with a run that differs in this one setting; a rerun that changes only the hints is reported as a controlled rerun. Expect more retries and forfeits. In tournament Analytics, use the **Legal move hints** filter (*On only* / *Off only*) to keep the modes apart.

**Cost:** similar per move to the hints-on run (the prompt loses the move list), plus every retry; models that struggle cost more.

## Is more effort worth it?

**Question:** does a higher requested reasoning effort win more points, and at what price?

**Where:** Benchmarks → Recipe → *Is more effort worth it?* for a Low baseline. When it has finished, open it, choose **Rerun**, set **Setting to change** to **Requested effort**, pick a higher **New effort** (for example High) and choose **Create and start rerun**; the comparison opens automatically. See [Rerun and compare](how-it-works.md#rerun-and-compare).

**Recipe settings:** Legal move hints **on** · Reasoning effort **Low** · Chat mode **Off** · Stockfish levels **1500** and **2000**

The rerun copies the source's budget and concurrency, because a rerun changes only one setting. A higher requested effort can increase spend per game and make the rerun pause before it completes all the source matchups. Check observed spend on the same matchups. If it pauses, enter a higher budget under **Resume run**.

**Read:** the comparison's **Score delta** per model with its **matched** count, **Cost delta**, and **Reasoning tok/move** (reasoning tokens per move) with its **reported** count for both runs. The changed outcomes list shows which games flipped.

**Caveats:**

- **Score delta** uses only matchups scored in both runs, while **Cost delta** includes spend on unfinished and cancelled games. Check that the matched count covers the games you care about before you compare them; a rerun that paused early compares few games.
- **Reasoning tok/move** averages accepted model moves with a reported reasoning-token count. The **reported** count is the number of those moves, including moves reported with zero reasoning tokens. Similar averages do not prove that a provider ignored the level, and a model without effort levels gets no effort parameter at all (the game's attempt history shows the effort actually sent).
- A higher requested effort can consume more of the output allowance. Two requested levels can map to the same supported level. An unusable cut-off answer pauses the game.
- Compare the score delta with a [variance check](#variance-check) before calling it an improvement.

**Cost:** two runs. Do not predict the second from the effort label: watch the rerun's spend after its first scored games and compare it with the source's spend for the same matchups.

## Cost efficiency

**Question:** how many points does each model score for its spend, on identical games?

**Where:** Benchmarks → Recipe → *Cost efficiency*.

**Recipe settings:** Legal move hints **on** · Reasoning effort **Model default** · Chat mode **Off** · Stockfish levels **1500** and **2000**

Each model thinks as its provider ships it, which is what you pay for by default.

**Read:** *Model results* → **Score**, **Games** and **Spend**; *Opening pairs* shows each game's spend. Compare models over the same completed matchups: when a model's run paused early, it has fewer scored games and its total spend covers a different set of games. Points per dollar only makes sense for matchups that both models completed with positive recorded spend; it is undefined at zero spend. Rerun and compare adds **Reasoning tok/move** (reasoning tokens per move) and **Cost delta**. For cost per completed move across tournaments, use Analytics: *Cost & reliability* → *Per-Model Performance Metrics* → **Cost/Move**, *Overview* → **Cost / Win**, and *Play quality* → **Reasoning tokens / accepted move**. Benchmark games are kept out of those model analytics; their spend appears on its own line.

**Caveats:** spend is what OpenRouter reports, including rejected answers and cache discounts, so it changes with prices and provider routing over time. A game paused for a spend check may be missing that request's cost. Check the request in your OpenRouter activity before choosing **Spend checked, resume**. Arena's displayed total still omits any charge it failed to record. Account for the missing charge from OpenRouter activity, or exclude the matchup from points-per-dollar comparisons. Some models think without end at their default effort and can produce unusable cut-off answers (the game pauses).

**Cost:** like *Raw playing strength*, but more spread: the most expensive models dominate the total.

## Reliability and format discipline

**Question:** can the app play the model's first answer, and does the model forfeit?

**Where:** New tournament → Tournament Settings → Test recipe → *Reliability and format discipline*. Choose *Best of 3* under *Matches* or run several tournaments, so every model plays enough games.

**Recipe settings:** Legal move hints **on** · Reasoning effort **Model default** · Chat **Off** · Banter **Original** · Allow resignation **off** · Adjudicate decisive wins **off** · Limit time per move **off** · Max attempts per move **3**

The recipe switches off the ending rules that would hide the signal: no time limit (no losses on time), no resignation and no adjudication. Games still end by mate, a draw rule or an invalid-move forfeit once a model uses up its three attempts, and provider problems still pause them.

**Read:** Analytics, filtered to this tournament. *Overview* → *Model Comparison Table* → **First-try legal**, **Forfeits** and **Reliability**; *Failure Profile*. *Cost & reliability* → *Reliability Ranking & Stability* → **First-try legal**, **Forfeit Rate**, **Avg Retries**. Each game's feed lists the failed attempts per move (for example "illegal Qxh7, unparseable answer").

**First-try legal is not strict format compliance.** Tournament Analytics counts an accepted move as first-try legal when its stored retry count is zero. Resuming starts the turn again at attempt 1, so a move accepted on that attempt can count as first-try legal even if an earlier answer for the same ply was cut off and unusable. Benchmark comparisons check earlier model answers at that ply across pauses. Models are asked for JSON, but the parser also takes a move from plain text, so a bare `e4` counts as a legal first try. The attempt history (game feed, `GET /api/games/{id}/attempts`) shows the proposed move, outcome, finish reason and error, not the raw text. The raw answers are stored in the database: `move_attempts.raw_response` keeps up to 20,000 characters per attempt, and `moves.raw_response` keeps the full response of each accepted move. Query them there to check the exact format.

**Model or provider?** Illegal or unparseable answers and exhausted attempts are the model's. OpenRouter errors, rate limits and the 10-minute request cap are provider problems: the game pauses with the reason, and they never count as the model's timeout, invalid move or forfeit. Answers cut off at the output-token limit are listed separately and are not the model's failures; such an answer is played if it contains a legal move and otherwise pauses the game. The same model can be served by different providers, so a burst of pauses says more about routing than about the model.

**Caveats:** fewer than 3 finished games mark a model *Insufficient data*, and scores from fewer than 60 moves are damped. Eliminated models play fewer games in a bracket. On the ladder, **Forfeit losses** counts games decided by a model forfeit. **First-try legal** measures how often sampled model turns produced a legal move on the first try, including turns in games the model finished without forfeiting.

**Cost:** set by the bracket size and series length; the setup page's *Tournament Estimate* gives a range before you create.

## Personality and persuasion

**Question:** how do models talk, and who talks an opponent out of a playable position?

**Where:** New tournament → Tournament Settings → Test recipe → *Personality and persuasion*. Persuasion is measured on tournament games only; benchmark games never count.

**Recipe settings:** Legal move hints **on** · Reasoning effort **Model default** · Chat **Mandatory** · Banter **Mind games** · Allow resignation **on** · Adjudicate decisive wins **off** · Limit time per move **off** · Max attempts per move **3**

Resignation must be on: persuasion counts resignations. The recipe switches off adjudication and the time limit, so no game is decided by the arbiter or the clock. It cannot make anyone resign: games can still end by mate, a draw or a forfeit. Mandatory chat asks for a message with every move, but a legal move without a message is still played.

**Read:** Analytics → *Behaviour*. **Persuasion** → *As persuader* (split into *Mind games* and *Other levels*) and *As persuaded*; **Resignations** → *resigned while not losing*; **Talkativeness** → *Mandatory* (compliance) and average message length. To compare tones, use **Run this tournament again** with another banter level (for example Spicy); to see whether models talk by choice, run it with chat *Optional*.

**Caveats:** a resignation only counts as talked out when Stockfish evaluated the position, so the evaluator must be available. Fewer than 5 evaluated resignations show **Low sample**. A low *As persuaded* count can also mean few resignations or silent opponents. Tone is prompt guidance, not moderation.

**Cost:** slightly above a silent tournament, depending on the messages models actually send: each short message adds output tokens.

## Can it finish a won game?

**Question:** once a model reaches a winning position, does it convert it into a win, or does it draw, lose or miss a mate?

**Where:** New tournament → Tournament Settings → Test recipe → *Can it finish a won game?*. Choose *Best of 3* under *Matches* or run several tournaments; only some games reach a winning position.

**Recipe settings:** Legal move hints **on** · Reasoning effort **Model default** · Chat **Off** · Banter **Original** · Allow resignation **on** · Adjudicate decisive wins **off** · Limit time per move **off** · Max attempts per move **3**

Adjudication is off, so a won game has to be finished on the board instead of being awarded. Resignation is on, so the losing side may give up a hopeless position. No time limit means no game is decided by the clock. Games can still end by mate, a draw rule, resignation or a forfeit.

**Read:** Analytics, filtered to this tournament. *Play quality* → **Conversion**: **Winning positions** (games where the side reached at least 90% winning chances), **Converted**, **Thrown away** and **Missed mates**; check the *sides evaluated* count beside each model. *Behaviour* → **Resignations** → **Won by resignation** shows how many of a model's wins came from the opponent giving up.

**Caveats:**

- Samples are small: a model needs games where it actually reached a winning position, and many brackets produce only a handful.
- Converted counts every win from a winning position, including the opponent's resignation or forfeit, so it is not only the model's own technique.
- Thrown away includes draws as well as losses (also the model's own forfeit).
- Conversion needs Stockfish evaluations; games without them cannot show a winning position and are left out.

**Cost:** somewhat above a tournament with adjudication, because decided games are played until mate or resignation.

## How fast does it answer?

**Question:** how long does a model take per move, and does it lose games on time?

**Where:** New tournament → Tournament Settings → Test recipe → *How fast does it answer?*. Benchmark runs have no move time limit, so this is a tournament recipe.

**Recipe settings:** Legal move hints **on** · Reasoning effort **Model default** · Chat **Off** · Banter **Original** · Allow resignation **off** · Adjudicate decisive wins **off** · Limit time per move **120 seconds** · Max attempts per move **3**

120 seconds is the value the setup page fills in when you switch the limit on; *Move Pace* reads *Balanced*. The limit covers the whole turn, from the first request to the complete answer, including every retry. Below 90 seconds the setup page warns that reasoning models may forfeit on time.

**Read:** Analytics, filtered to this tournament. *Overview* → *Model Comparison Table* → **Latency** and **Forfeits** (the note shows how many were timeouts). *Cost & reliability* → **Avg Latency** in engine health, **Latency** in *Per-Model Performance Metrics* and **Avg Latency** in *Reliability Ranking & Stability*. The game page shows each pause reason.

**Loss on time or pause?** A model loses on time only when its deadline passes while it waits for an answer on an established connection. An unfinished connection setup, an incomplete HTTP error, or a response whose status is still being read pauses the game for a spend check instead. After an earlier provider failure in the same turn, a missed deadline also pauses: for a spend check if the request may have reached OpenRouter, otherwise for the provider problem. These pauses are not losses on time; check the spend in your OpenRouter activity before choosing **Spend checked, resume**. See [Time limit per move](how-it-works.md#time-limit-per-move).

**Caveats:**

- Latency depends on provider load and time of day; the same model can be fast in one run and slow in the next. Repeat at a different time before ranking close results.
- Reasoning effort strongly affects latency. The recipe uses each model's default; for a like-for-like comparison, set one effort for every model and say so when you report it.
- Latency is shown but not scored in the reliability score.

**Cost:** about the same as a tournament without a limit; games lost on time end early, and a model that thinks less to stay within the limit spends less on those moves.

## Variance check

**Question:** how much does a result change when nothing changes?

**Where:** open a finished benchmark run, choose **Rerun** and keep **No change (measure variance)**. The new run repeats the same openings, colours, levels and settings with fresh games, and the comparison opens automatically.

**Recipe settings:** none; a plain rerun copies the source run.

**Read:** the comparison's **Score delta** per model and the changed outcomes list. A one-setting difference (effort, hints) smaller than this run-to-run swing is not evidence.

**Caveats:** one plain rerun is a single sample of the noise; repeat it for important decisions. Check the **matched** count under each score delta: it compares only matchups scored in both runs. Moving aliases can make a plain rerun a different model.

**Cost:** the rerun copies the source's budget cap and concurrency, but its actual spend can differ: answers vary in length and retries, prices and routing change, and the rerun pauses at the cap if it needs more. Raise the budget under **Resume run** if it pauses.
