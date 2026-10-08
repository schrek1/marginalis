# Changelog

All notable changes to Marginalis. The format follows
[Keep a Changelog](https://keepachangelog.com); the build injects the
current version's section into the plugin's Marketplace change notes.
History before 0.1.19 lives in git tags.

## [Unreleased]

### Added

- *Live by default*, a ⚡ toggle in the Marginalis tool window's toolbar: every open
  thread of the project is live without switching each one. A thread's
  own ⚡ still switches it off. The choice is kept per project, in your
  workspace, not in version control; a checkbox in Settings sets where a
  project starts.

## [0.2.2] - 2026-10-04

### Changed

- Conversations about the whole project now live in a Margin tab you can
  pin next to your code (#37). Plans, release notes and a pull request's
  conversation stay in one place you can return to.
  - The tab's title shows what waits on you: ✉ for your move, ✈ for the
    agent's, and simply "Margin" when nothing waits.
  - Threads that need you open expanded. The rest stay folded to their
    latest message, so you can scan them at a glance.
  - Unread messages are marked new until you read them.
  - Submit round and "+ New project thread" sit at the top of the tab,
    so you can keep the tool window closed.
  - The tab works as a home base. It tells you when replies wait for you
    in files, and one click takes you to the first.
  - A pull request's conversation from GitHub folds into one group
    instead of filling the tab.
  - Find (⌘F or Ctrl+F) narrows the tab to the threads that mention your search.
  - A pinned tab comes back after a restart, and a half-written reply
    is still there when you reopen the tab.
  - A walkthrough step about the whole project opens the tab at that
    thread.
- Your agents can keep listening across rounds (#38). Write your
  replies, submit them as one round, and the agent answers and waits
  for the next one without you asking again.
  - Submit round, formerly Hand Back, sends every reply you wrote this
    round.
  - Submit round is off while no agent listens, so no reply goes
    unheard. Replies you write meanwhile wait in their threads for the
    next round.
  - The composer's "Submit & send round" sends your reply and the
    round in one step.
  - Listening agents stop cleanly when you close the project.

### Added

- Make the Margin tab work the way you read, in Settings → Tools →
  Marginalis → Project Tab (#37).
  - Keep the list still while you read, with new threads waiting behind
    a pill, or let it follow the latest activity.
  - Choose whether a thread opens by itself when it becomes your move.
  - Choose what counts as read: a thread you can see, or only one you
    click into.
  - Turn off grouping to see each comment from a pull request as its
    own thread.
- See at a glance who is listening and who is working (#38). The tool
  window and the Margin tab show each agent's avatar, with a green dot
  while it listens and a spinner while it works on your round. Hover to
  see for how long.
  - An agent that answers a single round and then stops is marked
    one-shot.
- Stop any agent, or all of them, from the arrow beside Submit round
  (#38).

## [0.2.1] - 2026-10-02

### Added

- See who said what at a glance (#32). Whenever the speaker changes in
  a thread, a small picture of the author leads their message, and each
  thread in the Marginalis tool window shows the faces taking part.
  - Teammates and review bots appear with their GitHub pictures.
  - Anyone without a picture gets their initials in their own colour.
  - Agents carry a small mark, so you can tell them from people at a
    glance, even when several agents share the margin.
  - Give anyone a picture and a nickname in Settings → Tools →
    Marginalis → People, and set your own picture next to your name.
  - Nicknames now work for agents too.
  - The GitHub nicknames you already set carry over as they are.
  - Prefer nothing fetched from GitHub? Turn GitHub pictures off, and
    initials show instead.

## [0.2.0] - 2026-10-02

### Added

- Work through pull request reviews without leaving the editor (#33).
  Your agent brings a PR's review discussion into the margin, next to
  the code it is about, so you can decide what to do with each comment
  together.
  - Every comment keeps its author. Teammates, review bots and your own
    GitHub comments are each labelled as such, and one click opens the
    original comment on GitHub.
  - The PR conversation stays in order, grouped under its PR number.
  - Nothing goes back to GitHub. The margin is your working space;
    replying on the PR stays your decision.
  - Bringing the same PR in again adds only what is new. A new reply
    reopens a thread you had resolved, and a thread you removed from
    the margin stays removed.
  - Comments from GitHub wait quietly. They raise no notifications and
    never change whose move it is; a thread brought in from GitHub is
    yours to answer.
  - To see your own GitHub comments as yours, set your GitHub login in
    Settings → Tools → Marginalis.
  - Give teammates the names you know them by: map GitHub logins to
    nicknames in Settings → Tools → Marginalis.

### Fixed

- Margin messages are safer to open. Comments from GitHub, or from any
  other source, can no longer load remote images or open links other
  than web pages.
- A damaged entry in the margin's saved file no longer hides all your
  threads. Only that entry is skipped.
- Clicking a link to a line of code no longer reports an internal error
  in recent IDE versions.

## [0.1.30] - 2026-10-01

### Added

- Code links in margin messages (#27): a markdown link whose target is
  a project-relative path — `[Clabo.do_the_thing()](src/Clabo.kt#L42)`
  — opens the file in the editor on click, with the caret on the line;
  `#L42-L50` also selects the range, and no anchor opens the file. Agent-
  and user-written messages alike. A path that names no file, or a line
  past its end, shows the same warning balloon an unresolved `mg:`
  reference does. The guide's new Code links section teaches the format.
- FYI (#29): `fyi` joins the intents — a note that asks nothing, such
  as praise, context or a heads-up — marked with a slate "i" in the
  bubble. Agents may label what kind of fyi it is ("praise",
  "heads-up"), shown as a chip in the thread header. It is your move
  only until you have read it; then it leaves Awaiting You and the ✉
  goes, and a reply makes it an ordinary conversation again. It never
  holds up an agent's edits, takes no severity, and stays open as the
  record until you resolve it. The tool window filters to it.
- One-click Agree (#29). Agree sits beside "Reply…" when it's your
  turn — an agent spoke last, not to another agent. One click answers
  "Agreed." to that agent and passes the turn (✈); it shows in the
  thread as a single line, and agents read it as `agrees: true`.
- Live threads (#30): a Live toggle (⚡) in a thread's header turns
  that thread into a conversation. While it's on, each Submit (or
  Agree) wakes the agent you're answering with that thread alone — no
  Hand Back — and the header shows "Claude is listening" while it
  waits, pulsing "Claude is working…" from the wake until it replies.
  Turning Live on hands over anything the agent hasn't seen yet, and a
  thread you started goes to the agent that is waiting (with several
  waiting, @ the one you mean). Live is offered only while that agent
  is waiting, and ends when you switch it off, resolve the thread, its
  anchor is deleted, or you restart the IDE. Agents read the wake's new
  `reason` (`live` or `hand_back`); the guide's new Live threads
  section teaches it.

### Changed

- The thread header shows the intent's glyph instead of its word, for
  every intent (hover names it); blocker and nit stay words. Settings
  has a short legend of the glyphs. Tool window rows drop their intent
  and severity words: the row's mark already says both (a blocker
  carries the red badge, a nit's preview is greyed). A thread row's ✉ /
  ✈ moves from the end of the row to just after its mark, so a long
  preview no longer pushes it out of sight.

## [0.1.29] - 2026-09-28

### Changed

- One icon family from the plugin icon (#25). Gutter marks and tool
  window rows are brand-colored outline bubbles whose glyph and hue say
  the intent; file threads fold a corner, project threads peek an arc;
  resolved marks dim and orphaned ones sit in a dashed amber frame. Turn
  signals replace ● / ○: a violet envelope ✉ (your move) and a blue
  plane ✈ (the agent's) badge the file's own icon in editor tabs and the
  Project view — nothing trails the file name any more — and trail the
  tool window's file and thread rows. The stripe badge is the envelope
  (red still wins for an open blocker), and the tool window icon is a
  miniature of the plugin icon. The "N awaiting you" title count is gone
  for now: it counted every open thread owed, which didn't match the
  badges on screen — what to count is deferred to #26.

## [0.1.28] - 2026-09-28

### Added

- `GET comment_identities?project=` (#19): who is already in this
  margin, marking nothing seen — the user, every agent that wrote (name
  and id), and ids known only from read receipts (`name: null`), each
  with messages written, unread depth, and whether it is in a
  `comment_wait` right now. `project` is required when several are open.
  The guide's Identity section now says to list identities and reuse
  your role's before minting an `author_id`.
- `to` on `comment_add`, `comment_reply` and `comment_add_batch` items
  (#17): address a message to one `author_id`, or `user`; persisted per
  message and echoed in listings. Unaddressed stays a broadcast. In the
  reply composer, typing `@` opens a picker of the margin's agents, and
  addressed messages wear an @chip in the thread panel. The guide covers
  addressing roles, and the three-party etiquette: the completer
  resolves, the requester verifies via `status=resolved&updated_after=`,
  the user overrules.
- `comment_list?summary=true` (#20): survey a margin without consuming
  receipts — thread metadata only, no message bodies, marking nothing
  seen (`marked_seen: 0`). Each thread's `messages` becomes a count,
  with `unread` for the caller, `last_author`, and `awaiting` read for
  the caller's identity. Composes with every filter. The guide gains a
  first-contact recipe: `comment_identities` → summary survey → read
  deliberately, scoped by `file` or `awaiting=agent`.
- References (#23): `mg:` plus the first 8 characters of a thread or
  message id. "Copy Reference" on the thread panel's toolbar and beside
  each message puts one on the clipboard; `comment_list?ref=mg:…`
  resolves it to its thread, flagging the named message
  `referenced: true`, and answers an ambiguous prefix with a teaching
  400 listing each candidate's full `ref`. In rendered message bodies
  a reference outside code becomes a link that opens the thread,
  scrolled to the message it names. The guide teaches the format.

### Changed

- `awaiting` is computed for the calling identity (#17): a thread whose
  last message is addressed to another agent is theirs to answer, not
  yours; one addressed to `user` awaits the user.
- Hand back is targeted (#17): it wakes only the waiting agents that
  have something awaiting them; when no waiting agent has anything, all
  wake with an empty `awaiting`, which still ends the loop.

### Fixed

- The Hand Back button's tooltip names who is waiting ("Hand Back —
  Claude is waiting"), as 0.1.27 promised; it showed only "Hand Back".
- A project thread's window keeps itself current: replies, edits and
  resolution now show while it is open, instead of after reopening it,
  and deleting the thread elsewhere closes the window.
- A project thread's window scrolls: it opens at its natural height (up
  to a cap) and follows new messages while you are at the bottom, without
  pulling you back down if you scrolled up to read. It closes from its
  title bar, and an `mg:` reference into it now scrolls to the message.

## [0.1.27] - 2026-09-27

### Added

- Hand back (#24): "Hand Back" in the tool window and "Submit & hand
  back" on the composer tell every waiting agent it's their turn. Agents
  end a turn with `GET comment_wait?project=&since=&timeout=`, held
  without tying up a server thread until the next hand back (at once if
  one already happened after `since`) or the timeout — default one hour,
  capped at four — and answered with the `awaiting=agent` threads as the
  to-do list. `since` is the later of the newest `updated_at` and the
  last `handed_back_at` seen, so one click wakes an agent once;
  `comment_list` on a single project now reports that project's
  `handed_back_at` for a fresh session's opening cursor. The last hand
  back persists in `.idea/marginalis.json`.
  The gestures show who is listening: the Hand Back button (a paper
  plane) names the waiting agents in its tooltip and dims when none is, and "Submit & hand back" is
  offered only while one waits — both live as waits start and end.
  The guide teaches the end-of-turn wait, and that a hand back with
  nothing awaiting the agent ends the loop, as a timeout does; the README
  adds a Claude Code hook recipe for when no wait is armed.
- `comment_list?awaiting=agent|user` (#18): open threads whose last word
  is the other party's — `agent` lists what the agent still owes you,
  `user` what you owe it. Composes with every other filter; the guide's
  sweep now teaches that the awaiting set, not raw unread, is the debt.
  The tool window's "Awaiting You" lens gains its mirror, "Awaiting
  Agent".
- Project view shows the turn glyph beside file names with open threads
  (#4) — the editor tab's ● (you owe a reply) / ○ (the agent does), now
  visible for files that aren't open. One rule in core (`Turn`) feeds the
  tab, the Project view, and the tool window's dots.
- Code fences in the composer are colored while you type (#3): each
  fence's code runs through its language's own lexer, painted over the
  Markdown highlighting — the colors it will render with, before you
  submit. Rendered messages and the composer now share one fence parser.

## [0.1.26] - 2026-07-27

### Changed

- Marketplace listing description: onboarding an agent is now the second
  thing a visitor reads — the one-command skill install and the served
  guide URL, right after the hook. Metadata-only release; the plugin
  itself is 0.1.25's.

## [0.1.25] - 2026-07-27

First stable-channel release (#7).

### Added

- Thread intents (#14): an optional `intent` on `comment_add` —
  `finding`, `guidance` or `question` — saying what kind of response a
  thread wants, with anything else answered by a teaching 400 naming the
  vocabulary. Omitted stays the common case. It is fully independent of
  severity (a `guidance` `blocker` is a legitimate thing to say) and
  purely semantic in this version: resolution works identically for all
  of them, and the served guide teaches what resolving each one means —
  a finding by fixing, guidance by being followed in the new code, a
  question by an answer. `comment_list` gains an `intent=` filter and
  returns `intent` on threads that have one, which makes the motivating
  query cheap: all the open guidance for the file you are about to edit.
  In the IDE each intent has its own gutter glyph — an eye, a bulb, a
  question mark, shapes rather than colors — plus a chip in the tool
  window rows and the thread panel, and its own lens in the tool
  window's filter.
- `comment_add_batch` (#10): a review round's notes in one call. Each
  item is a whole `comment_add` payload — the full anchor ladder, mixed
  freely — judged on its own, so one stale anchor fails its own item and
  the rest still land. The envelope's `author_name`, `author_id` and
  `project` are defaults for every item; results come back in request
  order, each the shape the single call would have sent or `{error}`.
  Only a malformed envelope is an HTTP error.
- `comment_reanchor_all {file}` (#11): rescue every orphan on a file at
  once, the way a whole-file rewrite creates them. The content search
  runs server-side and widens to the whole file — after a rewrite the
  old line numbers mean nothing and the anchor text means everything —
  and answers per thread: re-anchored at line N, or still orphaned
  because the content is genuinely gone. A file with no orphans returns
  an empty list, not an error.
- `comment_list` gains `updated_after` and returns `updated_at` per
  thread (#13): a cursor for "what moved since my last sweep", including
  threads the user resolved while the agent was away. It moves on
  messages, resolve, reopen and rescue — deliberately not on reading a
  thread or on an anchor drifting with an edit, which would make it
  useless as a cursor. Persisted; files written before this carry no
  timestamp and derive one from their newest message.
- Listed threads carry `anchor_text` — the anchor line as it stands now,
  so a caller can tell "adjusted to new content" from "still exactly
  what I wrote" without re-reading the file (#13).
- Project-level threads (#16): omit `file` as well as `line` on
  `comment_add` and the thread is about the workspace itself — the
  convention nobody wrote down, the decision still owed — with no path,
  no line, and nothing that can ever orphan. They lead the reading order
  everywhere: first in `comment_list`, and above the file nodes in a
  "Project" section of the tool window that appears only when such
  threads exist. Creating one never depends on that section: a toolbar
  action is always there, and the composer's split button now offers
  both widenings ("Comment on file instead" / "Comment on project
  instead"), carrying a draft's selection along as provenance. Since
  there is no file to resolve by, `comment_add` takes `project` whenever
  several are open, and answers the usual `open_projects` error when it
  can't tell; `line` without `file` is a teaching 400. The served guide
  states the anchor ladder once: selection → line → file → project.
- File-level threads (#12): omit `line` on `comment_add` and the thread
  is about the file itself — its shape, its name, the README it lacks —
  with no anchor to drift and nothing to re-find. A page glyph in the
  gutter beside line 1 marks a file that has them; clicking it unfolds
  the conversation above the first line, above all the code it is about.
  The glyph is display only: it never anchors anything, and these threads
  orphan only when the file itself disappears, reopening by themselves
  when the path comes back. They carry `severity`, `order`, and
  `walkthrough` like any thread (a file-level step opens the file at the
  top), and the tool window lists them under the file node above its line
  threads. On the wire the absence is the shape: responses and listings
  for a file-level thread carry no `line` or `line_adjusted` at all.
  `navigate` without `line` now opens a file at the top; `anchor_text`
  without `line`, and `comment_reanchor` on a file-level thread, are
  teaching 400s.
- "Comment on File" in the editor context menu (#15): the entry point
  that needs nothing to exist first, so a file with no threads at all can
  still be commented on as a whole. The tool window's file node offers
  the same action.
- The composer for a thread being started is now a split button: submit
  as begun, or take the dropdown's "Comment on file instead" to land the
  same words as a file-level thread. A draft that began from a selection
  keeps the selected words as provenance — what sparked the comment,
  recorded without pretending to anchor it.
- In-repo agent skill (`skills/marginalis/`), installable globally for
  70+ agents via `npx skills add MuhammadFarag/marginalis -g`: it
  teaches an agent to find the server, fetch the served agent guide,
  and follow it. No wrapper script — plain HTTP.

### Changed

- Malformed `comment_add` payloads now get teaching 400s throughout
  (#13): every rejection names the field, says what is wrong with it,
  and says what to do instead — the manner the severity vocabulary set,
  applied to types, empty bodies and misplaced anchors alike.
- `comment_list` returns threads in the tool window's reading order — by
  file (directory-tree), file-level threads first within each file, then
  down the lines — instead of creation order.
- A `segment` may now ride a file-level thread: provenance, not anchor.
  The guide's Spans and File-level sections both teach it — the quoted
  words are what sparked the thread; address them specifically even when
  the thread is about the whole file.
- Agent guide: message-body formatting is now its own "Message bodies"
  section (was a footnote under the API reference) — names everything
  that renders (emphasis, inline code, links, lists, headings, tagged
  fences) and what deliberately doesn't (tables, images, raw HTML), and
  tells agents to use markdown where structure helps.
- Agent guide: the API reference now documents responses, not just
  inputs — every endpoint's return shape in the table, a full
  `comment_list` example (`thread_id`, structured `author {kind, name,
  id}`, `seen_by`/`newly_seen`, optional thread fields), so agents
  script against real shapes instead of guessing (#9).
- Agent guide: the unread-sweep habit now passes `project=` — a bare
  sweep spans every project open in the IDE and consumes read receipts
  across all of them (#9).
- Skill: slimmed to a pure bootstrap — find the server, fetch the
  served guide before the first margin call, follow it as the sole
  authority. The duplicated habit summaries are gone, so the skill no
  longer needs to change when the contract does.

## [0.1.24] - 2026-07-25

### Added

- `GET /api/marginalis/agent_guide`: the plugin serves its own agent
  manual — the full contract (turn etiquette, identity and read
  receipts, anchoring rules, severity and walkthrough vocabulary, orphan
  rescue, API reference) as markdown, version-matched by construction
  because it ships inside the plugin. Teaching any agent Marginalis is
  now one instruction: ping, then fetch the guide. CI verifies the guide
  mentions every endpoint.

### Changed

- `ping`'s `version` now comes from a build-stamped resource instead of
  platform plugin-manager lookups (which are internal API).
- README's agent-integration section leads with the served guide.

## [0.1.23] - 2026-07-25

### Changed

- Markdown rendering now uses the IDE's bundled Markdown plugin instead of
  shipping a private copy of the parser library. New plugin dependency:
  `org.intellij.plugins.markdown` (bundled and enabled by default in all
  JetBrains IDEs).
- Marketplace listing description rewritten around what makes Marginalis
  itself: a turn-based margin conversation protocol.

### Fixed

- All Marketplace verifier findings: internal API usage
  (`PluginManagerCore.getPlugin`), scheduled-for-removal API
  (`SimpleListCellRenderer.create`), and deprecated API usages replaced
  with their public, current equivalents.

## [0.1.22] - 2026-07-25

### Added

- Collapsing reply composer: idle panels fold the reply box to a single
  prompt row; it expands on click or restored draft and folds after
  submitting. Panels open in reading mode — Esc and the walk shortcuts
  work without a click.
- Walk shortcuts inside the thread panel: the platform's next/previous
  occurrence shortcuts drive Previous/Next Step with focus anywhere in
  the panel.
- Resolve auto-advances through every walk — ordinary threads too, not
  just guided walkthroughs.
- Multi-line selections keep their quote: the span clamps to the
  selection's first line instead of degrading to a whole-line thread.

### Fixed

- Reopening a thread panel after an external file reload: stale inlay
  bookkeeping made navigation move the caret without opening the panel
  and swallowed the first gutter click.

## [0.1.21] - 2026-07-24

### Changed

- Severity vocabulary is strict: `blocker` and `nit` only — legacy
  `high`/`medium`/`low` now get a teaching rejection instead of silent
  aliasing.
- Domain rules moved into the pure core module: walk ordering and stable
  totals, directory-tree ordering, severity parsing, orphan rescue as a
  guarded transition, aggregate-state precedence, and the anchoring
  decision ladder — one tested home each, shared by every surface.

## [0.1.20] - 2026-07-24

### Fixed

- Submit shortcut is a registered shortcut (⌘⏎/Ctrl-Enter no longer
  collides with editor actions on some keymaps); a keyboard shortcut
  moved off the refactoring row.

## [0.1.19] - 2026-07-24

### Added

- Resolve folds the thread panel: only open threads hold editor real
  estate.

### Changed

- README and Marketplace description caught up with the product.
