# Design system: Club Scoresheet

The source of truth for token values is [`frontend/src/styles.scss`](../frontend/src/styles.scss).
This guide defines their meaning and use; do not duplicate colour or measurement values here.

## Principles

- A chess club scoresheet: readable paper, warm charcoal, clear notation and ruled tables.
- Desktop only. Pages must fit a 1280 px window; wide data tables scroll inside their own wrapper.
- Light and dark are one system. Change semantic tokens, keeping structure and interactions consistent.
- Unity comes from the green accent, board, table rules and typography, rather than tinted card surfaces.
- Green identifies chess and primary actions. Logo blue identifies information, links and keyboard focus.
- Prefer a short label and one-line hint with a `?` tooltip over explanatory paragraphs in controls.
  Longer reference material belongs in documentation.

## Tokens and roles

Material's `--mat-sys-*` roles supply controls, text and surfaces. App roles extend them where
chess or shared page patterns need a name. Consume semantic roles rather than palette colours.

| Token or family | When to use |
| --- | --- |
| `--mat-sys-primary`, `--mat-sys-on-primary` | Primary actions, selected tabs and chess accents. Always pair foreground and background roles. |
| `--mat-sys-primary-container`, `--mat-sys-on-primary-container` | A bounded selected or live state; never an entire page or ordinary card. |
| `--mat-sys-surface*` | Neutral page, card and inset layers. Use hierarchy rather than adding a colour wash. |
| `--mat-sys-on-surface` | Main content. |
| `--mat-sys-on-surface-variant` | Secondary text, labels, captions and metadata; do not lower its opacity. |
| `--mat-sys-outline`, `--mat-sys-outline-variant` | Control boundaries and quieter dividers. |
| `--mat-sys-error`, `--mat-sys-on-error-container`, `--mat-sys-error-container` | Errors and destructive outcomes, with a label or icon explaining the problem. |
| `--app-accent-info` | Logo-blue informational text, ordinary links and focus rings. Keep primary actions green. |
| `--app-info-fill`, `--app-info-border` | Informational banners and connection hints. |
| `--app-success-*` | Success text, border and fill for a bounded outcome or health indicator. |
| `--app-warning-*` | Warning text, border and fill for pauses or recoverable operator action. |
| `--app-quality-inaccuracy`, `--app-quality-mistake`, `--app-quality-blunder` | Move judgement glyphs; retain the glyph and accessible explanation. |
| `--app-font-heading`, `--app-heading-stretch` | Shared heading family and width. |
| `--app-font-numeric` | Moves, KPIs, ratings, costs and other values that need easy comparison. |
| `--app-text-secondary-size` | Minimum secondary text and footnote size. |
| `--app-table-heading-size`, `--app-table-heading-tracking` | Compact but readable table headers. |
| `--app-table-rule`, `--app-table-zebra` | Header rule and alternate body rows. |
| `--app-row-hover` | Subtle neutral hover surface for selectable moves, table rows and timeline entries; preserves text contrast. |
| `--app-rule-width`, `--app-focus-offset` | Shared rule and keyboard focus geometry. |
| `--app-hero-bar`, `--app-hero-bar-width`, `--app-hero-glow` | Page introduction accent and restrained glow, softer in dark mode. Independent of the board preference. |
| `--app-card-bg`, `--app-card-border`, `--app-card-shadow`, `--app-divider` | Shared neutral cards and internal boundaries. |
| `--app-surface-bg`, `--app-surface-border`, `--app-surface-shadow` | Page hero and other raised surface treatment. |
| `--app-surface-radius-lg`, `--app-surface-radius-md` | Shared surface corners. |
| `--app-page-max-width`, `--app-page-gap` | Page width and main content rhythm. |
| `--app-space-*` | Shared spacing scale for compact card content, fields and supporting metrics. |
| `--app-banner-gap`, `--app-banner-padding`, `--app-banner-radius` | Shared connection banner geometry. |
| `--app-board-light-square`, `--app-board-dark-square`, `--app-board-last-move` | Board squares, captured-piece trays, promotion backplates and last-move wash. |
| `--app-board-last-move-from` | The last move's from square: a lighter tint of the same wash, derived from `--app-board-last-move`. |
| `--app-board-ring-edge`, `--app-board-selection`, `--app-board-drop-target`, `--app-board-hover` | Board interaction cues that remain visible over both square tones and highlights. |
| `--app-board-swatch-size`, `--app-board-control-size` | Board chooser preview and compact desktop controls. |
| `--app-segmented-height`, `--app-segmented-selected-bg`, `--app-segmented-selected-text` | Segmented controls (`mat-button-toggle-group`): shared height, and a primary fill with a check for the selected option where it switches a view, so selection never rests on a tint. |
| `--app-game-record-min-width`, `--app-game-chat-min-width` | Widths beside the board: the Moves \| Feed column keeps this width; the chat column starts at its minimum and takes the remaining space. |
| `--app-game-record-stacked-max-height`, `--app-game-chat-stacked-max-height` | Height caps of those columns when the game view stacks them in one column. |
| `--app-game-stacked-panel-height` | Shared height of Moves \| Feed and chat side by side under the board. |
| `--app-side-card-header-height` | Header block of the game view's side cards (segmented control with its border), so their content starts level. |
| `--app-move-link` | A chat message's move number that shows its position: chess green with an underline; ordinary text links stay blue. |
| `--app-piece-white`, `--app-piece-black` | Neutral side markers and evaluation bar tones. They do not follow the board preference. |
| `--app-eval-white` | White's share of the evaluation bar and its label background: the white piece tone in light mode, a softer cream in dark mode. |
| `--app-brand-*` | Logo artwork only. Do not reuse these as interface status colours. |

## Typography

Archivo is the only heading family. It gives page headings, card titles and section kickers a
consistent character. Set heading size and line height locally when needed; avoid a `font`
shorthand that resets the global family or stretch. Roboto remains the body and control family.
The font families use local fallbacks while the existing runtime Google Fonts link loads.

Use JetBrains Mono through the shared `.num` class for move notation, KPI values, Elo ratings,
counts and currency. Do not make prose or model names monospace just because they sit next to
a number. Tabular numerals keep columns stable. The body fallback remains readable if fonts
cannot load.

Table headers use `--app-table-heading-size` and `--app-table-heading-tracking`; secondary text,
captions and footnotes use at least `--app-text-secondary-size`. Never shrink a note below these
tokens to make it fit.
All text must meet at least 4.5:1 contrast in both themes, including secondary text and text on
banners, zebra rows or selected states. Do not fade text with opacity.

## Tables

Use a ruled table with a stronger header rule and a quiet alternate-row fill. The shared global
rules apply to native table headers and Material header cells. Keep ordinary row dividers quiet.
Row headers receive zebra fill too, so a model identity stays visually attached to its metrics.

Right-align numeric values and their leaf headers. Centre a group header over the columns it
labels. Apply `.num` to numeric values and retain tabular numerals. Identity and explanatory
columns stay left-aligned. A header may wrap before the table forces page overflow.
Keep footnotes outside the scrolling wrapper when possible.

```html
<th class="num" scope="col">Cost per move</th>
<td class="num">{{ cost | currency: 'USD' }}</td>
```

## Components and patterns

**Cards.** Use neutral surface roles, shared corners and a quiet border. Shadows describe depth;
they do not replace boundaries. Reserve primary fill for actions or small selected states.

**Heroes and toolbar.** Page heroes use `.page-hero-surface` for the surface and token-driven
glow, plus `.page-hero` for the left accent bar and layout. Compact analytics headers use the
same bar. The toolbar has a matching bottom rule. A board colour change leaves these accents green.

**Pause and information banners.** Information uses the blue info roles. A pause or failed
connection uses warning roles; an unrecoverable error uses error roles. Keep the message and
its recovery action together. `.openrouter-status.info` and `.openrouter-status.warn` share the
same pattern across setup, onboarding, System settings and connection dialogs. Existing
conditions, actions and connection state handling determine which banner appears.
A spend-check notice keeps the **Paused for a spend check** heading, full reason, OpenRouter
activity hint and single primary **Spend checked, resume** action together.

**Live games lamp.** While games run, show the live lamp with the **Live games** label and
running count. Animate the glow only when reduced motion is disabled. Use a steady warning marker for paused games.

**Connection fallback.** The callback card uses a neutral surface and info rule. Its progress
message is informational; failure text uses the error role. Retry and navigation remain the
existing actions.

**Move quality.** Preserve `?!`, `?` and `??`, with the shared quality tones and an accessible
label or tooltip. Colour supplements notation; it never carries the judgement alone.

**Status chips.** Use short state words, a neutral base and bounded success/warning/error
accents. Preserve a textual status even when a coloured dot is present.

**Empty states.** State what is missing and the next useful action in one short sentence. Use
secondary text on a neutral surface. Do not present missing samples as a zero metric.

**Hints.** Keep control hints to one line at desktop width. Put optional detail behind a named
`?` help control with a tooltip that can be reached by keyboard. Link to reference documentation
when the explanation is longer than a tooltip.

**Focus and links.** Use the info accent for visible keyboard focus and ordinary text links.
Material focus indicators consume the same role. Button links retain their action styling.

## Board themes

The game toolbar's **Board** menu offers Green, Blue-grey and Wood. Green is the default;
Wood preserves the earlier board appearance. A two-square swatch previews each choice and a
check marks the current selection. The menu uses radio semantics for assistive technology.

`BoardThemeService` owns the preference, stores it as `boardTheme` in browser local storage
and applies `data-board` to `html`. The app applies the saved setting at startup. Missing or
unknown saved values fall back to Green. Storage errors leave the selection usable for the
current session. Board preference and app light/dark mode are independent.

Each board theme changes only `--app-board-light-square`, `--app-board-dark-square` and
`--app-board-last-move`. Boards, mini boards and captured-piece trays must consume these roles
rather than define their own colours. Swatches share the same token definitions. The evaluation
bar and piece-side markers stay neutral.

## Rules for changes

- Add new colours, fonts or spacing as semantic tokens in `styles.scss`, then consume them.
- Keep theme differences in token blocks; do not introduce theme-specific structural CSS.
- Do not use `!important` or page-specific overrides to simulate an app theme.
- Pair foreground/background roles and check text contrast in both themes: at least 4.5:1.
- Check a 1280 px desktop window with long model names, dense tables and the board controls.
- Keep table overflow local, preserve numeric alignment and keep hints on one line.
- Test changed interactions and preference failure cases; keep component style budgets intact.
