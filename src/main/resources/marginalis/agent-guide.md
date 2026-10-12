# Marginalis — Agent Guide

You are reading the contract served by the installed plugin itself
(`GET /api/marginalis/agent_guide`), so it always matches the server
you are talking to. `ping` tells you the exact version.

Marginalis is margin conversation between you and the user: comment
threads anchored to lines of live code, rendered inside their JetBrains
IDE. The channel is **turn-based** — the user is always present; you
exist during a turn. You never type into their buffer; you leave notes,
replies, review findings, and guided walkthroughs in the margin, and
you read what they left you. Everything below follows from that
asymmetry.

## Discovery

`GET http://127.0.0.1:63342/api/marginalis/ping`

```json
{"status": "ok", "ide": "…", "version": "<plugin version>",
 "projects": [{"name": "…", "path": "…", "branch": "…"}]}
```

Each running IDE process serves its own port (first 63342, next 63343, …)
and only its own open projects. If ping fails, the IDE isn't running —
say so and move on; do not retry in a loop. If ping succeeds but your
project isn't in `projects`, probe the next port. `branch` disambiguates
same-layout git worktrees.

## Identity

Introduce yourself on every write and every read: `author_name` (display
name) and optionally a stable `author_id`. Read receipts are **per
agent**, keyed by `author_id` (falling back to name): listing marks
messages seen for YOUR identity only. Unidentified callers all share the
anonymous "Agent" identity — and consume each other's unread. When
several agent sessions share a margin, take distinct role-qualified
names ("Claude · design" / "Claude · impl") with distinct ids.

Before minting an `author_id`, see who is already here:
`GET comment_identities?project=…` lists every identity the margin
knows — the user, each agent that wrote (name and id), and ids known
only from read receipts (`name: null`) — with how much each wrote, how
many messages it hasn't seen (`unread`), and whether it is in a
`comment_wait` right now. It marks nothing seen. Reuse your role's
identity if it is listed: a fresh id finds the whole history unread by
construction.

## First contact: survey before you read

A margin you have never read is all unread to you, and reading is
promising: one bare sweep marks the project's whole history seen. On
first contact, look before you consume:

1. `comment_identities?project=…` — reuse your role's identity if it is
   listed.
2. `comment_list?summary=true&project=…&author_id=…` — the survey:
   every thread's metadata with no message bodies, marking nothing seen
   (`marked_seen: 0`). Each thread carries `messages` (the count),
   `unread` (for your identity), `last_author`, and `awaiting` (`agent`
   when the reply is yours, `user` when it is theirs — read from the
   last message that was not relayed; absent once closed, on an fyi the
   user has read, or while that message is addressed to another agent).
   It composes with every other filter.
3. Read deliberately — the bodies you are about to answer, scoped by
   `file=` or `awaiting=agent`. Those reads mark seen, as they should:
   the receipt belongs to what you actually read.

## Addressing: several parties, one margin

`to` on `comment_add`, `comment_reply` and each `comment_add_batch` item
addresses that message to one party: an `author_id` as
`comment_identities` lists it, or `user` for the user. Copy the id
exactly: an id nobody holds addresses no one present, so the thread
becomes nobody's debt and wakes no one. Messages carry
their `to` in listings. Unaddressed is a broadcast — every agent owes
it — and is the right default for a first round, before anyone knows
who else is here.

- **Address roles, not sessions.** The thread history is the roster:
  address the role's `author_id` ("claude-review"), which outlives any
  one session holding it.
- **A message addressed to someone else is not your debt.**
  `awaiting=agent` is computed for your identity: a thread whose last
  message that was not relayed is addressed to another agent drops out
  of your list and into theirs; one addressed to `user` awaits the
  user, whoever wrote it. An agent can hand a thread to another agent by replying with
  `to` — the thread then awaits that agent, not the user.
- **The three-party etiquette.** The completer resolves: whoever lands
  the outcome resolves the thread, as always — except an fyi, which only
  the user resolves. The requester verifies: sweep
  `comment_list?status=resolved&updated_after=<cursor>&project=…&author_id=…` for the
  threads you asked for, and when the outcome falls short, reopen with
  a reply saying what is missing. The user overrules everyone — their
  word on a thread settles it, whoever it was addressed to.

## The three habits

**1. Start every turn with the unread sweep.**
`GET comment_list?unread_only=true&project=…&author_id=…` — the user
leaves comments and replies while you're away, born unread. Always pass
`project`: a bare sweep spans every project open in the IDE and
consumes your read receipts on all of them. Reading marks messages
seen (`newly_seen` per message, `marked_seen` total): that receipt is
the promise the user relies on, so always read the bodies you consume,
and answer in-thread — a user reply is guaranteed a response. Every
thread carries `updated_at`; hand the newest one back as
`updated_after=` and a later sweep returns only what has moved —
including threads the user resolved while you were away.
Unread is what you haven't *seen*; your debt is what you haven't
*answered*. `comment_list?awaiting=agent&project=…&author_id=…` lists
every open thread whose last message that was not relayed is yours to
answer — the user's, unless it was addressed to someone else, or
anything addressed to you. Without your identity (`author_id`, else
`author_name`) the list is the anonymous "Agent"'s, not yours. Reading
doesn't shrink it, only a reply (or a resolve) does. End the sweep with
it empty.

**2. Never edit a file that has open threads.**
`GET comment_list?file=<path>&status=open&project=…&author_id=…` before editing. Open threads
are unfinished conversations; drive each to resolution first — its
conclusion becomes part of your edit, or reply why it needs no action
and resolve. Editing underneath an open thread orphans the discussion.
An fyi is the exception: an `intent: fyi` thread doesn't block the
edit — it stays open as the record until the user resolves it, and you
never resolve it. Replies on it still reach you through your sweep.
Once the user replies to it, it blocks the edit until you have answered
that reply in-thread (landing whatever they asked for first); it then
stays open for the user to resolve, never you. If your edit orphans it,
leave it for the user to decide.

**3. The resolver is the completer.**
`RESOLVED` means "the outcome is in the code, or explicitly moot" — the
gutter marker disappears at that moment. A user reply of "do it" is
approval, not completion: make the edit first, then resolve. If the
user resolves a thread themselves while action seems pending, ask
rather than assuming. Resolve immediately only when no action is needed
— never an fyi, which is the user's to resolve. An Agree (`agrees:
true`, addressed to you) is the same approval: land what was agreed,
then resolve.

## Ending a turn: wait for the next round

When the user has finished a round — read your replies, answered what
they wanted to — they **submit the round**: "Submit round" in the
Marginalis tool window or the Margin tab header, or "Submit & send
round" on the reply composer. It is project-wide ("here is my batch"),
and it is how you learn you are wanted. A round reaches you as reason
`"hand_back"`; the wire names `handed_back` and `handed_back_at` are
historical and mean "a round arrived".

End every turn the same way: leave your replies, then start the wait as
a **background command** and stop.

```
curl -s "http://127.0.0.1:63342/api/marginalis/comment_wait?project=…&since=<cursor>&stay=<true if your harness re-invokes you>&author_name=…&author_id=…"
```

**Pass `stay=true` if your harness re-invokes you when a background
command exits** — in Claude Code, run the curl with `run_in_background`.
It tells the user you will keep listening, and the plugin remembers it
for your identity for the session. Without such a harness, leave `stay`
off: you are a **one-shot** agent.

`since` is your cursor: the later of the newest `updated_at` and the
`handed_back_at` from the sweep or wake that started this turn — not
the timestamp of your own replies. A `comment_list?project=…` listing
carries the project's last `handed_back_at` in its envelope; a wake
carries the one that woke you. So a round the user submitted while you
were still working answers at once instead of being missed, and one you
have already answered never wakes you twice. The wait's **completion is
the signal**: `handed_back: true` means the user wants you now, and its
`awaiting` list (the `awaiting=agent` set plus any live thread holding
messages you haven't seen, same thread shape as `comment_list`) is your
to-do list — answer each, then wait again.

`handed_back: false` carries a `reason` and nothing else:

- `"timeout"` — `timeout` seconds passed (default one hour).
- `"stopped"` — the user pressed Stop on you.
- `"closing"` — the IDE or the project is closing.

When to wait again:

- **Opted in (`stay=true`)**: re-arm after every wake — a round, an
  empty round, a live submit — and after a timeout, keeping the cursor
  you had. An empty round is not the end of the loop for you.
- **One-shot**: wait again after a wake with something in `awaiting`.
  A round with nothing awaiting you, or a timeout, ends the loop — stop
  waiting; the user will type when they are back. An empty live wake
  does not end it (see Live threads).
- **Everyone**: on `"stopped"` or `"closing"`, stop waiting and do
  **not** re-arm until the user asks you to in chat. A `"stopped"` can
  answer your very first wait — a Stop the user pressed while you were
  working, or one left for your identity; treat it the same.

The user sees you in the margin's indicator: **listening** while your
wait is open, **working** from the wake until you open the next one.
Submit round is enabled only while some agent is listening, so one
round is in flight at a time.

A round is **targeted**: it wakes only the waiting agents that have
something awaiting them, and the rest keep waiting — a round that
passed you by does not answer your next wait either. Only when no
waiting agent has anything awaiting it does every waiter wake with an
empty `awaiting` — the empty round above.
Without an open wait, nothing reaches you until the user types; Claude
Code users can close that gap with two hooks that put the awaiting
threads into every prompt — offer them the recipe at
https://github.com/MuhammadFarag/marginalis#if-you-use-claude-code.

### Live threads

The user can switch a thread **live** — several can be live at once —
but only while you are waiting; going live is their call, never yours.
While a thread is live, each Submit in it (and an Agree) wakes you at
once with that thread alone — no Submit round. Switching Live on wakes you
the same way when the thread already holds messages you haven't seen;
that wake's `handed_back_at` is the time of the thread's last change.
Every wake names its `reason`: `"hand_back"` for a Submit round, `"live"`
for a live submit. A live wake's `awaiting` holds only the live
thread(s) with something new for you — owed to you, or holding messages
you haven't seen (a follow-up the user sent while you were answering) —
in the same shape, marked seen; other threads the user drafted wait for
the next Submit round. It wakes one agent: the one the reply `@`-addresses,
else the `to` of the thread's last message that was not relayed, when
the user wrote it, else the agent who spoke most recently in the thread
in its own words (relays are skipped; on a thread of only relays, the
agent that relayed them), else — on a thread the user started — the one
agent waiting on the project.
Its `handed_back_at` is the live submit's time — advance your cursor to
it as usual — but it does not move the project's last round in
`comment_list`'s envelope. A live submit made while you were still
working answers your next wait at once when you pass `since`; if a
round is pending too, the round wins and carries the whole
`awaiting` set, live threads included — even a live follow-up you have
not seen in a thread where you spoke last — and its `handed_back_at` then
covers the live submit too, so it can be later than the one in
`comment_list`'s envelope.

After a live wake, answer the thread and **always wait again** — even
when `awaiting` comes back empty (the thread was resolved, or you had
already answered it, between the wake and its reply). The user sees
you "listening" while your wait is open and "working" until you open the next one.

## Anchoring

- The ladder, narrowest first: **selection → line → file → project**. You
  write the middle two and the top one — `line` for a place in the code,
  `file` alone for the file as a whole, neither for the project itself;
  selections are the user's gesture (see Spans). Always take the narrowest
  rung the subject actually occupies.
- `file` is project-relative; `line` is **1-based**, as you read files.
- Line numbers are hints; content is truth. **Always pass `anchor_text`**
  — the exact text you believe occupies the line. The server verifies,
  searches ±20 lines, and answers with `line_adjusted: true` when it
  corrected you.
- **On an anchored call, a 409 with `reason: "stale_anchor"` means your
  picture of the file is stale**: re-read the file, find the target
  again, retry with fresh values. Never respond to it by dropping
  `anchor_text` — that trades an honest failure for a comment silently
  pinned to the wrong line. Every 409 carries a `reason`; the others are
  `not_orphaned` (see Orphans) and the two relay refusals,
  `deleted_relay` and `relayed_elsewhere` (see Relaying from GitHub).

## Above the line: file and project threads

Omit `line` on `comment_add` and the thread is about the **file itself** —
its shape, its name, the README it lacks. Omit `file` as well and it is
about the **project**: the workspace as a whole, what you'd raise in a
standup rather than in a diff.

- Each omission drops what the narrower rung needed: `anchor_text` without
  `line` is a teaching 400, and so is `line` without `file`.
- A project-level `comment_add` has no path to resolve by, so pass
  `project` whenever more than one is open — otherwise it fails with
  `open_projects` for you to pick from.
- Responses and listings say only what the thread has: no `line`, and for
  project-level no `file` either. In `comment_list` project-level threads
  come first of all, then each file's file-level threads before its line
  threads.
- Neither drifts: a file-level thread orphans only when its file
  disappears (reopening on its own when the path comes back), a
  project-level one never orphans. `comment_reanchor` refuses both, and
  `navigate` still needs a `file`.
- Either may carry a `segment` as provenance — the user's selection
  that sparked it, not an anchor.
- `severity`, `order`, and `walkthrough` work unchanged; stepping to a
  file-level step opens the file at the top; stepping to a project-level
  step opens the user's project tab (the Margin tab) and scrolls to that
  thread.

## Spans (read-only for you)

A thread may carry `segment {exact, prefix?, suffix?}`: the user
selected those exact words within the line. Their gesture was precise;
address the quoted span specifically, not the line in general. Above the
line the same field is provenance instead — the words that sparked a
comment about the whole file, or the whole project; read them as the
origin of the thought, not as its subject. Agents cannot create segments —
`comment_add` anchors to lines.

## Severity

`severity` on `comment_add` is a **gate, not a weight**: `blocker`
("act before this proceeds") or `nit` ("taste, dismiss guilt-free");
omit for everything in between, which is most comments. The vocabulary
is exactly those two words — anything else is rejected with a teaching
400; fix the word or drop the field, never retry with a synonym. Never
write the level into the body ("HIGH:", "Blocker:") — the UI carries it
everywhere it matters and the user can filter to blockers. Importance
is not severity; importance lives in your prose, argued with reasons.
An fyi takes none: it asks for nothing, so it gates nothing.

## Intents

`intent` on `comment_add` says what kind of response the thread wants —
a **gate, not a weight**, exactly like severity and independent of it —
except that an fyi takes no severity and is the user's to resolve. Four
words, nothing else:

- `finding` — something here is wrong. It ends when the code is fixed.
- `guidance` — how the code around here should be written. It ends when
  the new code follows it, which is usually not the moment you read it.
- `question` — you genuinely want an answer. It ends when you get one.
- `fyi` — nothing is owed. Typical uses: praise ("this is the right
  boundary — keep it"), code you copied from elsewhere, context, a
  heads-up. If anything should be done or answered, it isn't an fyi. The
  user owes it only a read; once read, it leaves `awaiting=user`. It
  ends when the user resolves it — never you. It doesn't block an edit;
  once the user replies to it, it blocks the edit until you have
  answered that reply in-thread (landing whatever they asked for
  first), and it still stays open for the user to resolve. It never gates, so it takes no `severity` (a teaching
  400 says so). Don't pad a review with it.

`label` on `comment_add` (fyi only) names what kind of fyi it is —
`praise`, `copied`, `context` and `heads-up` are the usual ones — and
the user sees it on the thread. It is lowercased; a–z and 0–9, single
spaces or hyphens between words, at most 20 characters, and not a word
the margin already uses (`blocker`, `nit`, an intent). It is set at
creation and never changes; on any other intent, or malformed, it is a
teaching 400. `comment_list` returns it when set.

Omit `intent` for everything else, which is most threads: an ordinary comment
asks for nothing in particular, and marking everything makes the marks
meaningless. Anything outside the four words is a teaching 400 — fix
the word or drop the field, never retry with a synonym. Never write the
intent into the body ("Question:", "FINDING —"): the UI carries it, and
`comment_list?intent=` is how it is found.

Intent and severity compose freely, because they answer different
questions: a `guidance` `blocker` ("do not bring the rejected approach
back") is a normal and useful thing to say. The one exception is fyi,
which takes no severity. Resolution works identically for the others —
what differs is what resolving *means*, which is the list above — and
an fyi is the user's to resolve, never yours. Before editing a file,
`comment_list?file=…&intent=guidance&project=…&author_id=…` is the
cheapest way to learn what its authors already decided.

## Message bodies

Bodies render as GitHub-flavoured Markdown — use it wherever structure
helps: emphasis, ~~strikethrough~~, inline code, links (web links open in
the user's browser, code links in the editor — see Code links), lists,
headings (rescaled to margin proportions) and tables. A top-level table
keeps its natural width and scrolls sideways when it is wider than the
panel; a table inside a list or quote wraps with its text. Fenced code
blocks display as read-only editor fragments with native syntax
highlighting — tag your fences with a language and prefer them to
prose-wrapped code. Deliberately outside the scope: images show as
`[image]`, and raw HTML is reduced to the constructs above — any other
tag is dropped, keeping its text — so stay within them. Only http and https links open in the browser.

## References

`mg:` plus the first 8 characters of a thread's or message's id — e.g.
`mg:3d4770ad` — cites margin history. The user copies one from a
thread panel (the thread) or beside any message, and pastes it into
chat; `comment_list?ref=mg:3d4770ad&project=…` resolves it to that one
thread, with the named message marked `referenced: true`. Other filters
still apply. A prefix shared by several ids is a teaching 400 whose
`candidates` each carry an unambiguous `ref` (the full id) to retry
with; one matching nothing is a 404. Cite the same way in your own
bodies: outside code, a reference renders as a link that opens the
thread, scrolled to the message it names.

## Code links

When a body points at code elsewhere — "also implemented in
`Clabo.do_the_thing()`" — link it so the user clicks instead of
searching: `[Clabo.do_the_thing()](src/Clabo.kt#L42)`. The target is a
path relative to the project root, optionally with a GitHub line
anchor: `#L42` puts the caret on line 42 (1-based, as you read files),
`#L42-L50` also selects the range, and no anchor just opens the file.
A path with spaces goes in `<…>` or spells them `%20`. Nothing else is
a code link — absolute paths, `..`, and other anchor forms are
refused. Links resolve on click, against the file as it is then; a
file that doesn't exist or a line past its end shows the user a
warning instead of guessing.

## Walkthroughs

An ordered walk — "look here 1st, 2nd, …" — for reviewing your change,
explaining how code hangs together, or onboarding. Create steps with
`order` on `comment_add` (1, 2, …); an optional `walkthrough` label
("A", "B") keeps concurrent walkthroughs separate. Rules:

- A finished walk is one call: `comment_add_batch` takes the steps as
  items and answers per item, so a single stale anchor costs you that
  step and not the round.
- One topic per step, anchored on the line that best embodies it. The
  body never restates position, file path, or severity — the UI carries
  all three (steps render as "(2/5)" in a tree sorted in walking order).
- Order by the code's structure — entry point first, then callees —
  never by severity; severity has its own channel.
- A step can sit at any rung: a line, a file, or the project; a
  project-level step opens the user's project tab (the Margin tab) and
  scrolls to that thread.
- The user walks with next/previous controls and resolves steps as they
  go. A step resolved without a reply is seen-and-approved; a reply is a
  change request — land the change first, then resolve it.

## Relaying from GitHub

When the user asks you to bring a pull request's discussion into the
margin, relay each GitHub comment as a message attributed to its
original author. Pass `relayed: {source, comment_id, url, name, login,
bot?, avatar_url?}` on `comment_add`, each `comment_add_batch` item, or
`comment_reply`:

- `source` is `github`.
- `comment_id` is the comment's `id` — digits, as a number or a string.
- `url` is its `html_url`: https (GitHub Enterprise hosts too), ending
  in `#discussion_r…`, `#issuecomment-…` or `#pullrequestreview-…`
  followed by that same `comment_id`.
- `avatar_url` is optional: `user.avatar_url`, an https link.
- `login` is `user.login` (Enterprise managed logins like `mona_acme`
  included). GitHub's comment carries no display name, so
  `name` is the login unless you look it up
  (`gh api users/{login} --jq .name`, which may be null); at most 100
  characters, never starting with `<`.
- `bot` is `user.type == "Bot"`; a login ending in `[bot]` is a bot
  anyway.
- `body` is the GitHub comment's body verbatim — never a summary or a
  paraphrase. Your own view of it goes in a separate reply without
  `relayed`.
- A relayed message addresses no one: `to` with `relayed` is a 400.

The message's `author` stays you: the user sees "Mona · via <your
name>", a ↗ to the comment on GitHub, and consecutive relayed messages
grouped under their pull request.

Relaying is one-way: the margin never writes back to GitHub. The user
answers there themselves; your replies in the margin stay in the margin.

```
gh pr checkout {n}                                           # line numbers refer to the PR head
gh api repos/{owner}/{repo}/pulls/{n}/comments --paginate   # review comments
gh api repos/{owner}/{repo}/pulls/{n}/reviews --paginate    # review summaries
gh api repos/{owner}/{repo}/issues/{n}/comments --paginate  # conversation
```

- A review thread is a root comment (no `in_reply_to_id`) plus the
  comments replying to it, in `created_at` order. Relay the root with
  `comment_add` on its `path` and `line` (`anchor_text` = that line in
  the checked-out PR head), then each reply with `comment_reply`. A
  root whose `line` is null (outdated, or about the file), whose `side`
  is `LEFT` (a removed line), or that you can't anchor because the PR
  isn't checked out becomes a file-level thread: omit `line` and
  `anchor_text`.
- A `file` your checkout doesn't have is a 404, even without `line`:
  check the PR out, or relay that comment as a project-level thread.
- A conversation comment, and a review summary with a non-empty `body`,
  is about the whole pull request: relay it as a project-level thread
  (omit `file`). Relay conversation comments in `created_at` order and
  review summaries in `submitted_at` order.
- Always pass `project` on `comment_add` and batches: without it, the
  relay lookups below span every open project, and a worktree holding
  the same pull request answers for it. `comment_reply` ignores
  `project` — its `thread_id` already names one — but its relay checks
  still span the thread's project only.
- Relaying is idempotent within a project, keyed by the comment's kind
  (from its `url`) and `comment_id`, so re-relay freely and only what is
  new lands. A `comment_add` whose comment is already in the margin
  creates nothing and answers that thread with `existing: true`, even
  if its file has since moved. A `comment_reply` whose comment is
  already in that thread answers the message with `existing: true`; one
  already relayed into another thread is a 409 naming it
  (`reason: "relayed_elsewhere"`, with that thread's `thread_id`).
- A newly relayed reply reopens a resolved thread: the user closed it
  before those words existed. An orphaned thread stays orphaned.
- A relayed thread the user deleted from the margin stays out of it
  (GitHub is untouched): relaying its root comment again — as a
  `comment_add`, or as a reply in that project — is a 409
  (`reason: "deleted_relay"`) and creates nothing; skip it and its
  replies. Only the root is remembered: its replies, relayed into
  another thread, are not refused.
- Relayed messages are context, not turns: whose turn it is follows the
  last message that was not relayed, and a thread holding only relayed
  messages is the user's move — before and after they read it. On an
  ordinary thread, an unread relay never changes the turn. On an fyi
  the user has already read, a new relay makes it their move again until
  they read it. Relayed messages never raise a notification. The user
  can agree only with your own word, never with a relayed one.

## Orphans

`status: orphaned` means the anchored content disappeared. Orphans are
kept, not dropped — and you can rescue them: re-read the file, find
where the content lives now, `comment_reanchor {thread_id, line,
anchor_text}`. The thread reopens with a fresh verified anchor. Only
orphans may move (live anchors answer 409). When a sweep surfaces
orphans, rescue them before other work; if the content is truly gone,
reply saying so and resolve — except an orphaned fyi, which you leave
for the user. When a whole file was rewritten and its
threads orphaned together, `comment_reanchor_all {file}` runs the same
search over all of them at once — widened to the whole file, since the
old line numbers mean nothing after a rewrite — and answers per thread:
re-anchored at line N, or still orphaned. No orphans there is an empty
list, not an error.

## Navigation

`navigate` opens the file in the user's editor with the caret on the
line — pointing without creating a thread. Omit `line` to open the file
at the top when the file, not a place in it, is what you mean. Use it
**only on explicit request** ("show me", "take me there"); never move
the user's caret uninvited. A 403 means they switched agent navigation
off in settings — tell them, don't retry.

## Multiple projects

A project-relative path resolves first-match across every open project —
and same-layout worktrees make that ambiguous by construction. Pass
`project` (name or root path) on anchored calls when more than one
project could match; resolution failures return `open_projects` (name,
path, branch) so you can pick and retry. Check each listed thread's
`project` field before trusting a same-named file.

## API reference

Base: `http://127.0.0.1:<port>/api/marginalis/` — errors are
`{"error": "…"}` with 4xx status, written to be acted on; a 409 adds a
machine-readable `reason` (see Anchoring).

| Endpoint | Description → returns |
|---|---|
| `GET ping` | status, ide, plugin version, open projects with branches — full shape under Discovery |
| `GET agent_guide` | this document (markdown, not JSON) |
| `GET comment_list?ref=&file=&status=open\|resolved\|orphaned&intent=finding\|guidance\|question\|fyi&awaiting=agent\|user&unread_only=&summary=&updated_after=&project=&author_name=&author_id=` | threads with messages; reading marks seen for the calling identity → `{threads: […], marked_seen, handed_back_at?}` — `handed_back_at` (the project's last round) only when the listing covered one project and it has one; example below. `summary=true` swaps each thread's `messages` array for counts and marks nothing seen — see First contact. `ref=mg:…` narrows to the referenced thread — see References; ambiguous → 400 `{error, candidates: [{ref, thread_id, message_id?, project, file?}]}` |
| `POST comment_add {body, file?, line?, anchor_text?, order?, walkthrough?, severity?, intent?, label?, to?, relayed?, project?, author_name?, author_id?}` | start a thread on a line → `{thread_id, file, line, line_adjusted, status}`; without `line`, on the file as a whole → `{thread_id, file, status}`; without `file` either, on the project (pass `project` when several are open) → `{thread_id, status}`; a relayed comment already in the project creates nothing → `{thread_id, file?, line?, status, existing: true}`; errors are 409 `{error, reason}` — `stale_anchor` (re-read the file), `deleted_relay` (the user deleted that relayed thread) — see Relaying from GitHub |
| `POST comment_add_batch {items: [comment_add payloads], author_name?, author_id?, project?}` | many notes in one call; the envelope's identity and `project` are per-item defaults, `to` and `relayed` are per item only → `{results: [ …success shape… \| {error, reason?, open_projects?} ], created}` in request order, 200 unless the envelope itself is malformed; `created` leaves out items answered `existing: true` |
| `POST comment_reply {thread_id, body, to?, relayed?, author_name?, author_id?}` | reply in-thread → `{message_id, thread_id, status}`; `to` (an `author_id`, or `user`) addresses the message — omit it to address everyone; `project` is ignored (the thread names it); a blank `body` is a 400; a relayed comment already in the thread adds nothing → `{message_id, thread_id, status, existing: true}`; one already relayed into another thread → 409 `{error, reason: "relayed_elsewhere", thread_id}`, the root of a thread the user deleted → 409 `reason: "deleted_relay"` |
| `POST comment_resolve {thread_id, author_name?, author_id?}` | outcome landed / moot → `{thread_id, status}` |
| `POST comment_reopen {thread_id}` | resurface a resolved thread → `{thread_id, status}` |
| `POST comment_reanchor {thread_id, line, anchor_text?, file?}` | orphan rescue, line threads only (file-level → 400) → `{thread_id, line, status}`; the file is looked up in the thread's own project; `file`, when given, must be the thread's file (cross-file moves → 400); a live thread → 409 `reason: "not_orphaned"` |
| `POST comment_reanchor_all {file, project?}` | rescue every orphan on one file, searching the whole file by content → `{file, results: [{thread_id, line?, status}], rescued}` |
| `POST comment_resolve_all {file?, author_name?, author_id?}` | bulk resolve across **every open project** (`project` is ignored; `file` matches that path in each) — every thread not already resolved, fyi and orphaned ones included, so only when the outcomes genuinely all landed — which resolves fyis, the user's to close, so only on the user's explicit request → `{resolved: <count>}` |
| `POST comment_clear_all {file?}` | DELETE threads and the resolved log across **every open project** (`project` is ignored; `file` matches that path in each) — destructive; only on explicit user request, and sweep unread first. A reset, not a deletion: relayed threads it removes come back if relayed again, with or without `file`; without `file` it also forgets the relayed threads the user deleted, so those come back too → `{cleared: <count>}` |
| `GET comment_wait?project=&since=&timeout=&stay=&author_name=&author_id=` | hold until the user submits a round — at once if they already did after `since` (ISO-8601, exclusive; omit to wait for the next one) — or until `timeout` seconds pass (default 3600, capped at 14400) → `{handed_back: true, reason, handed_back_at, awaiting: [threads]}` or `{handed_back: false, reason}`; on `handed_back: true`, `reason` is `hand_back` (a Submit round: every thread awaiting you) or `live` (a live thread's Submit: only that thread — see Live threads); on `handed_back: false`, `reason` is `timeout`, `stopped` (the user pressed Stop) or `closing` (the IDE or project is closing); `stay=true` keeps you listening across rounds — re-arm after every wake and timeout, but never after `stopped` or `closing` (see Ending a turn); `awaiting` marks seen like `comment_list`; `project` is required when several are open; your `since` is the later of the newest `updated_at` and `handed_back_at` you have seen |
| `GET comment_identities?project=` | who is in this margin; marks nothing seen → `{project, identities: [{kind: "user", name, messages_written} \| {kind: "agent", name, id, messages_written, unread, waiting}]}` — the user first, then agents by messages written; `messages_written` counts an agent's own words, never its relays; `name` is null for an identity known only from read receipts; an agent in a `comment_wait` is listed (`waiting: true`) before it has written anything; `id` is the receipt key to pass as `author_id`; `project` is required when several are open |
| `POST navigate {file, line?, anchor_text?, project?}` | consent-gated pointing → `{navigated, file, line, line_adjusted}`; without `line`, opens the file at the top → `{navigated, file}` |

A `comment_list` thread, in full:

```json
{"threads": [{
  "thread_id": "…", "project": "…", "file": "src/…", "line": 12,
  "anchor_text": "    val x = compute()",
  "status": "open", "intent": "guidance",
  "created_at": "2026-07-27T18:03:11Z",
  "updated_at": "2026-07-27T18:41:02Z",
  "messages": [{
    "message_id": "…",
    "author": {"kind": "user", "name": "…"},
    "body": "…", "created_at": "…",
    "seen_by": ["claude-main"], "newly_seen": true,
    "to": "claude-review"
  }]
}], "marked_seen": 1}
```

Field notes: `anchor_text` is the anchor line **as it stands now** —
compare it with the text you anchored to and you know whether the code
moved under the thread, without re-reading the file. (Live from the
open document; for a file no editor has loaded it is the stored
fingerprint — the most the server honestly knows without forcing the
file into memory.) `updated_at` moves
when the conversation does (a message, relayed or not, a resolve, a
reopen, an orphaning, a rescue, or the user editing a message — they can
revise one until an agent reads it) and not when it is merely read or
its line drifts, which is what makes it a usable cursor for
`updated_after`. `author` is always an object — `kind` is `agent` or
`user`, and an agent author carries `id` only when the agent gave one. Thread fields `segment`, `order`,
`walkthrough`, `severity`, `intent`, `label`, and `resolved_by` appear only when
set, and so does a message's `to` — and its `referenced`, on a `ref`
listing. `relayed` (the object you relayed it with, `comment_id` as a string and `bot` always
present) marks a message relayed from GitHub; it never takes the turn
from whoever spoke last, a thread of only relays awaits the user, and
on an fyi the user has read, a new relay awaits the user until they read
it. `agrees: true` marks a message the user posted with one click
(body `Agreed.`, addressed to the agent whose message it answers):
treat it as approval of that message. Only the user can agree.
`awaiting` narrows to open threads whose last message that was not
relayed is the other party's, as seen by your identity: `agent` — the
user spoke last or someone addressed you, and you owe the reply (a
message addressed to another agent is theirs); `user` — an agent spoke
last unaddressed, anyone addressed `user`, or only relays were said,
and the user owes one. An fyi is the
exception: the user owes it only a read, and an agent's further word on
it owes only a read again — until the user replies; from then on whose
turn it is follows the rule above, though it stays the user's to
resolve. It composes with every other
filter, and listing still marks seen: awaiting is about *answered*, not
*read*. Any other value is a teaching 400.
With `summary=true` a thread keeps every field above except the
message array: `messages` becomes its count, joined by `unread` (your
identity's), `last_author` (same shape as `author`; the last who spoke, so never a relay — absent when only relays were said), and `awaiting`
(`agent` or `user`, as that filter reads it for you; absent when the
thread is closed, when it is an fyi the user has already read, or when
its last message that was not relayed is addressed to another agent —
their turn, not yours) — and `marked_seen` is always 0.
`newly_seen` marks messages this very listing consumed for your
identity; `seen_by` lists the identities that have read the message.
Timestamps are ISO-8601 UTC; `line` in every response is 1-based and
current (already re-anchored), not necessarily where the thread began —
and absent entirely above the line, as `file` is on a project-level
thread. Threads arrive in reading order: the project's own first, then by
file, file-level before line threads, then down the lines.

## Persistence

Threads live in `.idea/marginalis.json` per project, survive IDE
restarts, and re-anchor by content on reopen. The margin is part of the
workspace: prefer a thread over chat whenever what you're saying is
about a specific line — that is this channel's reason to exist. Keep
bodies short, one topic per thread, and put lasting conclusions in code
and commits, not only in the margin.
