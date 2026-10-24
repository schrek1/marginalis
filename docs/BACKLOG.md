# Marginalis — shipped record and decision log

Open work lives in
[GitHub issues](https://github.com/MuhammadFarag/marginalis/issues)
(migrated 2026-07-25). This file keeps the shipped history and the
decision log — the record of what was built and what was deliberately
declined, captured from real use.

## Shipped (2026-07-19)

- ✅ Guided tours — `comment_add order`/`tour`, compact `(A1/4)` prefixes,
  directory hierarchy in Guided sections, walking-order sort
- ✅ Tool window: anchor left, native per-filetype icons
- ✅ "Send" → "Submit"; context-menu action icon
- ✅ Edit-before-seen — composer-based editing; read receipt = edit boundary
- ✅ Markdown-lite rendering + fenced code as native highlighted editor
  fragments; markdown-aware composer (structure highlighting while typing)
- ✅ `core/` extraction — pure-Kotlin domain module, Author/ThreadStatus
  ADTs, USER rename (legacy-tolerant codec), AnchorPolicy deduplicated,
  22-test suite, `scripts/test-core.sh` local loop, comment-hygiene sweep,
  handover doc → docs/
- ✅ `navigate` endpoint + first settings surface — consent pattern
  (skill etiquette + hard off-switch, 403 when off), display-name override,
  anchor verification shared with comment_add (`940b096`)
- ✅ Tour walking — stable `(n/total)` denominators, first/prev/next/last in
  tool window title (⌘⌥↑/⌘⌥↓ free via occurrence actions) and thread-panel
  header, TourNavigator shared walk, panel title de-redundified (`db11943`)

## Shipped (2026-07-23)

- ✅ QoL trio (operator feedback) — Clear All now closes open editor
  panels (deleted thread → inlay disposed, not just refreshed); per-agent
  author-name colors (identity-hashed six-color palette, anonymous
  "Agent" keeps purple, user blue excluded); skill 0.9.1: `unread`/`list`
  scoped to the cwd project so sweeps stop burning read receipts in
  other projects, plus ranked `project_scope` containment (exact >
  cwd-inside-project > project-inside-cwd — nested projects had matched
  in ping order).
- ✅ Segment comments, human-side (v0.1.8, designed and built same day) —
  select + ⌃⌥M anchors to the span; agents only READ segments (asymmetry
  is the design). Built to the agreed sketch: quote selector (exact +
  within-line prefix/suffix, W3C TextQuoteSelector prior art) captured
  from the live selection; AnchorPolicy degradation ladder segment →
  line → ±window → orphan, 11 new core tests (36/36); tinted EXACT_RANGE
  highlighter; combined per-line gutter icon + chooser popup, with
  marker attachment consolidated into MarginalisMarkers (was duplicated
  across three sites); additive `segment` on comment_list rows,
  comment_add untouched; skill 0.10.0 "Heed the span". Within-line
  selections only — multi-line falls back to a line thread.

## Shipped (2026-07-24)

- ✅ UX round (v0.1.9, from the operator-perspective review) — turn signal:
  tool-window stripe badge + "N awaiting you" title when open threads have
  the agent's last word; unread gutter icons wear a badge dot (the
  BalloonInformation swap was too subtle); bare ⌃⌥M on a line with live
  threads opens the conversation (or the chooser) instead of drafting a
  duplicate — a selection still always drafts; Esc closes the panel and
  refocuses the editor; resolving a walkthrough step auto-advances to the
  next (setting, default on); "Quote code" composer link inserts the
  selection (or the thread's anchor) as a language-tagged fence; panel
  width follows the editor viewport instead of freezing at open; `ping`
  gains the plugin's own `version` (installed truth via PluginManagerCore
  — capability detection stops being absence-based), skill 0.10.1.
  Chooser popup extracted to ThreadChooserPopup (shared by gutter and
  ⌃⌥M).
- ✅ Rendered heading sizes (v0.1.10) — the render pane's HTML kit gains a
  stylesheet: h1 = 1.2× body, h2 = 1.1×, h3+ = body size, tight margins;
  bold carries the hierarchy. Closes the 07-19 "shouty headings" finding.

- ✅ Keyboard fixes (v0.1.20, operator findings) — ⌘⏎ submit actually
  works now: it was a raw KeyListener, and the IDE's key dispatcher
  routes ⌘⏎ to editor actions (Split Line) before the component sees
  it — replaced with a component-registered shortcut, which outranks
  the keymap while the composer has focus. Add-comment default moved
  ⌃⌥M → ⌥⇧M (ctrl-alt is refactoring territory on the default keymap:
  Extract Method collision; ⌘⇧M taken in real use — operator-picked).
  Shortcut swept through README, CLAUDE.md, skill 0.12.1.
- ✅ Resolve folds the panel + docs refresh (v0.1.19) — resolved threads
  now close their editor panels like deleted ones (one rule: only open
  threads hold editor real estate; Resolve All used to leave a wall of
  concluded panels — operator finding). README rewritten (was still
  describing M0 to a public repo); Marketplace description gains spans
  + severity before the first upload; icon deliberately unchanged (it
  predicted the product's color vocabulary).
- ✅ QoL round 2 (v0.1.17, agreed list) — draft preservation (composer
  text survives close/Esc per thread, in-memory, cleared on send/delete
  — Esc-close had made discard a single keystroke); tree right-click
  triage with SCOPED bulk ops (thread: navigate/resolve/delete; file
  and directory nodes: resolve-all/delete-all beneath, blocker warnings
  at every scope — operator extension); the funnel generalized to a
  filter menu (All / Blockers Only / Awaiting You — each lens is also a
  walk, per-lens empty states); agent-reply balloon when the file isn't
  on screen (click opens thread; settings off-switch, default on);
  comment_reanchor — orphan rescue is AGENT-side per operator call
  (orphans only, 409 for live anchors, same-file v1, verified anchor,
  fresh anchorText so the rescue survives restart — anchorText became
  var for this). Script `reanchor` cmd; skill 0.12.0 orphan etiquette.
- ✅ Right-click clipboard menus (v0.1.16, operator finding) — rendered
  prose panes (Copy enabled on selection / Select All), code fences
  (editor Copy / Select All via popup handler), and the composer
  (Cut / Copy / Paste / Select All). Selection worked but Swing installs
  no context menu on its own — an affordance you can't discover barely
  exists. Note: uses impl-package ContextMenuPopupHandler (advisory in
  verifier; public interface is EditorPopupHandler).
- ✅ Icons + delete round (v0.1.15, walkthrough feedback) — the header
  became one toolbar: step arrows · Resolve / Delete / Close as icons
  (links beside an icon toolbar were a mixed idiom); Resolve previews
  its outcome (gutter checkmark ↔ balloon); Quote code is the paste
  icon; Submit stays the one button. NEW: single-thread delete (trash,
  always confirms — deletion keeps no record, unlike resolve); "Clear
  All" renamed "Delete All" (UI only, wire stays comment_clear_all).
  ✓ seen turned green (operator request, "for the dopamine"); grouped
  messages reveal author · time on hover. DECLINED for now: message/
  composer background tints (operator withdrew — busy-ness risk).
- ✅ Panel polish round (v0.1.14, designed in conversation) — the accent
  rail: panel's left edge names its kind (blocker red / nit gray / brand
  purple); per-message author rails in the existing author colors +
  consecutive agent messages group under one meta line (user messages
  always keep theirs — Edit and the seen-check live there); header
  de-redundified again ("Marginalis" title dropped, Resolve/Close are
  links now, step arrows kept per operator call); composer full-width
  with a right-aligned action row beneath and a two-line minimum; "✓
  seen" receipt on read user messages (tooltip names the agents) — the
  edit-window promise made visible. Declined for now: prose measure cap
  (operator call: no width cap).
- ✅ Grouped Resolved/Orphaned sections (v0.1.13, operator ask) — every
  status section now shares the directory tree; the flat lists and their
  per-row path repetition are gone, and file-node turn dots count only
  live conversations. Chronology traded away knowingly: real usage
  consults Resolved by file and clears it per session (operator finding —
  the section is a session record, not an archive; timestamps stay in
  the threads). Blocker counts never count resolved threads, so Resolved
  never alarms about gates already passed.
- ✅ Severity: blocker / nit (v0.1.11, designed and built same day) —
  the agent's ad-hoc "HIGH:/MEDIUM:/LOW:" body prefixes lifted into the
  channel, the walkthrough-order pattern again. Built to the agreed
  sketch: two ends + silent middle (`severity: blocker|nit` on
  comment_add, unmarked = ordinary; blocker = gate NOT importance —
  importance stays in prose, see decision log); legacy high/low/medium
  accepted on the wire, garbage gets a teaching 400; additive codec +
  comment_list field, 2 new core tests (38/38). Visuals per "one loud
  mark, one quiet mark, silence": red/grayed tree chips (word + color),
  nit rows grayed whole, section blocker counts, gutter error-badge
  precedence checkmark → orphan → blocker → unread, panel status word,
  stripe badge red when blockers open. Resolve All / Clear All warn on
  open blockers. "Blockers Only" funnel toggle — filter + step-walking
  = walk the blockers; empty state "No blockers". Skill 0.11.0:
  severity vocabulary (never in the body), call-path walk ordering.
  DECIDED: no severity-colored span tints.
- ✅ Panel severity badges (v0.1.12, walkthrough feedback same day, 2026-07-24) —
  the thread panel header wears a pill badge matching the gutter: red
  "blocker", quiet-gray "nit"; replaces the too-easy-to-miss status-line
  word. The panel was the one surface that only got the quiet treatment.

## Shipped (2026-07-25)

- ✅ JetBrains Marketplace publishing, end to end — repo public
  (Apache-2.0), listing created (hidden, EAP channel), four CI secrets,
  and v0.1.23 as the first fully automated release: tag → build → sign →
  publish-to-EAP → GitHub Release. CHANGELOG.md drives the What's New
  tab. Remaining operator steps tracked in issue #7.

- ✅ Collapsing composer (operator UX) — idle panels fold the reply box to
  one prompt row ("Reply… ⌘⏎ submits" inline); it expands on click, on a
  restored draft, and for new-comment drafts; folds again after submit.
  Panels now open in reading mode with focus on the panel (Esc + walk
  shortcuts work without a click), composers only steal focus when there
  is something to write.
- ✅ Multi-line selection anchoring (operator UX) — a selection spanning
  lines clamps its segment to the first line and keeps the quote instead
  of silently degrading to an unquoted whole-line thread.
- ✅ Stale-inlay fix — document reloads (external file edits) disposed
  open panels behind the bookkeeping map's back; navigation then moved
  the caret but refused to reopen the "already open" panel, and gutter
  clicks ate the first click. Stale entries are now detected
  (`inlay.isValid`) and cleared in both `openPanel` and `toggle`.
- ✅ Resolve auto-advances every walk (operator finding) — previously only
  guided walkthrough steps advanced; now ordinary threads advance through
  the open-threads-in-tree-order walk too, same setting, same walk as the
  header arrows.
- ✅ Panel walk shortcuts — ⌘⌥↑/⌘⌥↓ (the platform occurrence shortcuts,
  keymap-remap-aware) drive Previous/Next Step while focus is anywhere in
  the thread panel, as the skill always claimed.

## Shipped (2026-07-20)

- ✅ Agent self-identification (v0.1.5) — optional `author_name`/`author_id`
  on comment_add/comment_reply, anonymous default display "Agent"
  (no more hardcoded "Claude"); resolver stamped by caller (`faff649`)
- ✅ Marketplace publishing wiring + walkthrough vocabulary (v0.1.3) —
  signing/publishPlugin off CI secrets (workflow skips gracefully until
  they exist), refreshed listing description; tour → walkthrough rename
  across model (legacy-tolerant codec), wire, UI (Step actions), skill
  0.5.0 incl. new Walkthroughs practice section (`a9c8cfc`)
- ✅ Worktree-safe anchoring (v0.1.4) — first externally-reported bug:
  same-layout worktrees made first-match resolution ambiguous. Optional
  `project` on anchored endpoints, git branch in ping + teaching 404s
  (`.git/HEAD` read, platform-only), per-thread `project` in listings,
  script auto-scopes by cwd; skill 0.6.0. CONFIRMED fixed in the
  reporting PyCharm worktree setup (`ae98560`)
- ✅ Hot reload — clean dynamic unload (`MarginalisUnloadListener` strips
  markup highlighters + editor inlays/user-data; all EPs verified
  `dynamic="true"`). Verified live: uninstall → install-from-disk cycles
  without IDE restart, threads survive via persistence + rehydration.
  Platform finding: one-step install-over-existing ALWAYS restarts
  (`PluginInstaller.installFromDisk` hard-codes it); the two-step flow is
  the reload loop — documented in CLAUDE.md.

## Shipped (2026-07-27 → 2026-09-28, v0.1.25–v0.1.29)

- ✅ v0.1.25 (first stable-channel release, listing public, #7) — the
  anchor ladder: file-level (#12) and project-level (#16) threads with
  "Comment on File" (#15) and the composer's split-button widenings;
  thread intents `finding|guidance|question` (#14); `comment_add_batch`
  (#10), `comment_reanchor_all` (#11), the `updated_after` cursor and
  teaching 400s (#13); response shapes in the served guide (#9); the
  skill slimmed to a pure bootstrap.
- ✅ v0.1.26 — metadata only: the listing leads with agent onboarding.
- ✅ v0.1.27 — hand back (#24): Hand Back / "Submit & hand back" and the
  `comment_wait` long-poll; `awaiting=agent|user` (#18); turn glyphs in
  the Project view (#4); code fences colored while typing (#3); a Claude
  Code hook recipe in the README.
- ✅ v0.1.28 — first contact and addressing: `comment_identities` (#19),
  `comment_list?summary=true` (#20), `to` + @-picker + targeted hand back
  (#17), `mg:` references (#23); thread panels keep themselves current
  and the project-thread window scrolls.
- ✅ v0.1.29 — the icon family (#25, spec in
  `docs/iconography-handover.md`): brand-bubble gutter marks with intent
  hues, ✉ / ✈ turn badges on file icons via `FileIconPatcher`, the mini
  plugin icon on the tool window.

## Shipped (2026-10-01, v0.1.30)

- ✅ v0.1.30 — code links (#27): `[text](path#L42)` / `#L42-L50` open
  the editor, parsed in core (`CodeLink`) with every escape check on the
  decoded path; fyi + one-click Agree (#29): the `fyi` intent with an
  agent-chosen `label`, per-message user read tracking, Agree as a
  compact `agrees: true` reply; intent glyphs replace intent words in
  headers and tool window rows; live threads (#30): a per-thread Live
  toggle, `reason: live | hand_back` on wakes, pending live submits
  delivered on unseen messages. Delivered with playgrounds in
  `.features/` and live dev-zip demos instead of review walkthroughs.

## Shipped (2026-10-02, v0.2.0)

- ✅ v0.2.0 — pull request reviews in the margin (#33): `relayed`
  messages attributed to their GitHub author, idempotent re-relay keyed
  on comment kind + id, reopen on a new reply, deleted relays
  remembered; GitHub-login nicknames in Settings; every message body
  sanitised; damaged codec entries skipped instead of emptying the
  margin; coalesced saves; `reason` on every 409; the code-link
  read-action fix for 2026.2; an end-to-end audit of the served guide
  and a CI check that matches exact API table rows.

## Shipped (2026-10-02, v0.2.1)

- ✅ v0.2.1 — avatars (#32): an inline avatar on each byline where the
  speaker changes (picture → GitHub avatar → initials in the author
  colour, ✦ badge for agents), participant stacks on tool window rows,
  the nickname table grown into a People table (Person/Agent rows,
  click-to-choose pictures copied into the IDE config dir), GitHub
  avatars fetched only from GitHub's avatar host and cached on disk for
  a week, and a switch to turn GitHub fetching off. Refined through two
  playgrounds, built in TDD slices with review rounds, reviewed by live
  demo.

## Shipped (2026-10-04, v0.2.2)

- ✅ v0.2.2 — the Margin tab (#37): project-level threads leave their
  popups for a pinnable editor tab titled by its turn signals (or
  "Margin"), backed by a small virtual file system so it restores after
  restart; counts, Submit round, new-message markers, one folded group
  per relayed PR, the resolved fold, an "Also your move" link to file
  threads, an empty state, a ⌘F filter, and four Settings switches
  (live vs hold still, auto-expand, what counts as read, relay
  grouping). Submit round (#38): Hand Back renamed; agents opt in with
  `stay=true` to keep listening across rounds; `stopped` and `closing`
  wake reasons; a listening/working avatar indicator in the tool window
  and the tab; Stop per agent or all. Both refined through playgrounds
  and reviewed by live demo.

## Decision log

- **Importance dimension declined** (2026-07-24): severity stays
  one-dimensional (blocker/nit + silent middle). Importance fails the
  vocabulary test — no distinct reader behavior attaches to it — the
  agent can't measure it honestly, a second chip breaks "one loud mark,
  one quiet mark, silence", and the underlying need is served by
  persistence (important non-blocking threads simply stay open; prose
  argues the weight). Revisit only if real usage produces the want —
  the comment_edit standard.

- **Resolve is an agent verb** (2026-07-19, real-usage finding) — OVERTURNED
  later the same day: the operator resolves tour stops directly while
  walking a review. Human-side resolve affordances stay as they are;
  the de-emphasis item is dropped.
- **Session presence dropped** (2026-07-19): operator call — the indicator
  doesn't make sense for a turn-based channel.
- **Cross-project walkthrough fragmentation accepted** (2026-07-20):
  rare in practice and harmless while walkthroughs don't interleave;
  the agent-side convention (one walkthrough per project) suffices —
  no UI work.
- **comment_edit dropped** (2026-07-20): symmetry without a use case — a
  full session of real use never produced the want. Agents compose in one
  pass and the human reads almost immediately, so the edit window would be
  seconds; corrective replies serve better and keep the record honest.
  Revisit only if real usage produces the need.
- **Review outcomes 2026-07-19** (via resolved margin threads, all landed in
  `65db25b`): comment-hygiene sweep; AuthorKind.HUMAN → USER with tolerant
  loader; Author + ThreadStatus as sealed ADTs; vendor email → m@far.ag;
  description → "between you and your coding agent".
- **Hand back is a long-poll, not a polling loop** (2026-09-27): measured
  ~6 ms per local request; both options are negligible, but the held
  request is idle-cheap and instant. On timeout the agent does not re-arm
  — the user stepped away and will type when back. Claude Code "channels"
  (true push) considered and dropped.
- **No body-returning "peek"** (2026-09-28, #20): receipts are the
  promise that "listed" means "read", so a survey returns metadata only
  (`summary=true`) and marks nothing. "Start from now" identity
  registration and pagination declined — reusing an identity (#19) and
  the awaiting set (#18) cover the incident.
- **Pinned / never-resolve threads deferred** (2026-09-28, #5): lasting
  decisions belong in code, commits and docs; an eternally open thread
  would block every future edit of its file. Resolution authority stays
  unenforced (etiquette + `resolved_by` + reopen).
- **Intent, not kind** (2026-07-27, #14): the field is `intent` because
  `author.kind` already exists on the wire.
- **Icons: dark-first, theme-neutral** (2026-09-28, #25): one SVG per
  brand icon, no `_dark` twin; the user works in the dark theme. ● / ○
  replaced by ✉ / ✈ ("the glyphs never stuck"); the ✈ is the Hand Back
  button's plane, so gesture and result share one symbol.
- **Tool window title count removed** (2026-09-28, #26): it counted
  every open thread owed (10 in a demo) while the user saw two badged
  files. What to count is open in #26.
- **Housekeeping closed** (2026-09-28): #2 (plugin marketplace — the
  skill is a stable bootstrap now), #8 (skill-trigger evals — no
  evidence of under-triggering), #6 folded into #22. #1 (markdown API)
  waits for the floor to reach 262: 2025.2–2026.1 bundle markdown 0.7.2,
  where the modern signatures are missing or experimental.
- **Praise became fyi** (2026-10-01, #29): every decision made for
  "praise" was about *nothing owed*, none about praise itself, so the
  intent is named for the behaviour and praise is one label of it
  (agent-chosen, lowercase, ≤20 chars, never a vocabulary word). A
  free-text label was accepted for fyi only; labels on other intents
  stay with #21.
- **fyi never blocks and is never the agent's to close** (2026-10-01,
  #29): once read it owes nothing, so no wake would bring an agent back
  to it — under the open-threads rule it would block its file forever.
  It is exempt from that rule and stays open as the record until the
  user resolves it. It takes no severity, and offers no Agree (Agree
  answers a request; an fyi makes none).
- **Live is a wake trigger, not a transport** (2026-10-01, #30): the
  long-poll stays; a live thread changes what wakes the agent (Submit in
  that thread, scoped to it and its agent). Session-only, user's call
  only, offered while the agent waits (#35 tracks the busy-agent gap).
  Claude Code channels (#31) would add push but need
  `--dangerously-load-development-channels` during the research
  preview, so the long-poll remains the default path.
- **The skill stays a bootstrap** (2026-10-01): contract changes go in
  the served guide only. #29 briefly added "(fyi excepted)" to the
  skill's trigger text; reverted the same day — the guide already
  carries the exemption, and touching the skill forces every user to
  re-run the skills CLI for nothing.
- **Relays are context, one-way** (2026-10-01, #33): a relayed GitHub
  comment never takes the turn from whoever spoke last (a relay-only
  thread awaits the user), never notifies, and nothing is written back
  to GitHub. New replies reopen a resolved thread; a relayed thread the
  user deleted from the margin is never recreated (Clear All resets).
- **Untrusted text is sanitised everywhere** (2026-10-01, #33): relaying
  lets any PR commenter reach the margin, so every message body is
  reduced to the markup the markdown generator emits — a review found
  an unclosed-tag bypass that made Swing fetch a remote image.
- **Nicknames are display-only** (2026-10-02): a Settings table maps
  GitHub logins to nicknames; agents and stored data keep the original
  names. Avatars (#32) remain open.
- **Plugin module stays test-free for now** (2026-10-02): logic lives in
  `core/` under TDD; the shell is exercised by live demos. This release's
  escaped bugs (a read-action assertion, a settings edit lost on OK)
  were all in the shell — CI-only platform tests for REST and threading
  are the candidate if that keeps happening.
- **Avatars live in IDE Settings, not a config directory** (2026-10-02,
  #32): the People table (identity, nickname, Person/Agent, picture)
  covers what a `~/.config/marginalis/avatars/` folder would, without a
  second place to look. Person rows match a GitHub login, Agent rows an
  author id.
- **A loaded avatar is kept for the session** (2026-10-02, #32): people
  rarely change their GitHub picture, so a good load is never refetched
  until restart; only a failed fetch retries (5 minutes), and the disk
  cache expires after a week.
- **Project threads live in a pinned tab, not popups** (2026-10-04,
  #37): the tab is a home base that exists even when empty; the list is
  live by default, except a thread holding an unsent reply keeps its
  place. Plugins can't pin a tab through public API, so the user pins it
  once and the platform remembers it.
- **Submit round, with opt-in listening** (2026-10-04, #38): "Hand
  Back" read as pausing the conversation; a round is a batch. Agents
  opt in to keep listening (`stay=true`) so harnesses that can't loop
  keep today's behaviour, and the wire names stay unchanged. Connection
  state is deterministic — an open wait is listening, a hang-up is
  gone — so there is no "away" timer; a working agent shows how long.
  One round in flight: Submit round is off while nobody listens.
  Stopped and disconnected agents vanish from the indicator, since
  every session is different.
- **Correction to "Project threads live in a pinned tab"** (2026-10-21,
  #37): the plugin pins the Margin tab itself on a project's first run,
  and pins it again after a plugin reload if it was pinned. The entry
  above said the user pins it once.
- **Live can cover a whole project** (2026-10-21, #43): a project toggle
  makes every open thread live, and a thread's own switch opts out. The
  choice persists per project in the workspace file; a project never
  switched follows the Settings default. Turning live on needs a
  listening agent. Extends "Live is a wake trigger, not a transport".
- **The plugin module has platform tests** (2026-10, reverses "Plugin
  module stays test-free for now"): escaped shell bugs kept coming, so
  the plugin module gained platform tests against a real in-memory IDE
  (`./gradlew test`, in CI against the 2025.2 floor). Tests that pin a
  known defect carry a FixMe marker naming its issue.
