# Marginalis — Handover: In-Editor Agent Comment Threads for JetBrains IDEs

**Project:** Marginalis
**Plugin id:** `marginalis` (suggested FQN `dev.marginalis.plugin`)
**Status:** original design brief (2026-07), implemented; kept as history. The current agent
contract is `src/main/resources/marginalis/agent-guide.md`, and user-facing behaviour is
`CHANGELOG.md`.
**Target:** PyCharm / IntelliJ Platform plugin (2025.2+)
**Audience:** the implementing agent

> **On the name.** *Marginalis* is the Latin adjective "of the margin, bordered" — the root behind
> *marginalia*, a reader's notes in the margins. It's also a common biological species epithet (e.g.
> *Dytiscus marginalis*, the great diving beetle): a small bordered thing that clings to the edges,
> which is exactly what these comments do. Use "Marginalis" for the product/tool window and
> `marginalis` for ids and the verb we'll actually use ("I glossed / margin-noted that line").

---

## 1. What we're building

A shared annotation layer over live code, inside the editor. Claude and the human both leave
comments anchored to specific lines. Each comment shows who wrote it. Either party can reply,
forming a thread. Threads can be resolved.

**The correct mental model is Google Docs comments, not Slack.** This distinction is load-bearing
and should drive your design decisions:

- Docs comments are anchored to a *range*, threaded, attributed, resolvable, and they survive
  edits to the document underneath them.
- Slack messages are ordered by *time* and have no anchor.

Everything hard about this project comes from the anchor. The messaging part is a list of structs.
If you find yourself building chat infrastructure, you have taken a wrong turn.

### Non-goals for v1

- Not a diff viewer. We are deliberately not porting [hunk](https://github.com/modem-dev/hunk).
  The IDE already has a diff viewer, and the diff framework is the least stable part of the
  platform. Stay in the regular editor.
- Not multi-human collaboration. Two participants: one local human, one agent. No server, no
  accounts, no sync.
- Not a review tool. Review implies work is finished and now gets judged. This is for margin
  notes *during* authoring.

### 1.4 Design philosophy: convention over enforcement

This is a personal tool for two cooperating participants — one human, one agent — not a product for
many users. That single fact should settle a hundred small design questions in favor of the simpler
option:

- **Prefer an agreed convention over an enforced mechanism.** We do not need the plugin to *prevent*
  editing during discussion. We need it to make the current state obvious and make following the
  convention cheap. No locks, no guardrails against a careless user — there is no careless user.
- **Prefer correct-under-cooperation over robust-against-abuse.** Skip machinery that only earns its
  keep with untrusted or numerous users.
- **When a convention can carry a guarantee, let it.** The plugin's job is to make the convention
  cheap to keep and its violations visible, not to police them.

The plugin therefore models *session presence* (is an agent attached right now?) as real state. The
discussion-vs-editing *phase* is a human intention layered on top — a convention, not a state
machine the plugin enforces. Do not build a mode engine.

---

## 2. Motivation

The trigger was hunk, a review-first terminal diff viewer for agent-authored changesets. Its
genuinely clever bit is not the TUI — it's that the agent can drive a live session. Hunk runs a
loopback daemon on `127.0.0.1:47657` serving a JSON `/session-api`, and `hunk session comment add`
is a CLI wrapper that POSTs to it.

The limitation is that a TUI is not an IDE: no click-to-definition, no navigation, no PSI.

The insight: in a JetBrains IDE, everything hard about hunk is free (diff, highlighting,
navigation, go-to-definition), and hunk's clever bit maps onto extension points that already
exist. So don't port hunk. Build the thing hunk's protocol was reaching for, natively.

---

## 3. Design constraints that will bite you

Read this section twice. Everything below is a trap that looks like a detail.

### 3.1 Asymmetric presence — the central problem, and the mode convention that answers it

The human is continuously present. **Claude is only present during a turn.** Between turns, Claude
does not exist and cannot be notified of anything.

This breaks the "live chat" intuition, and if you ignore it you will build a UI where the human
types a reply into a void and feels ignored. That is *the* failure mode for this product.

**The resolution is a mode convention: at any moment we are either editing code or discussing it,
never both.**

Be precise about what this does and does not buy, because it is easy to oversell:

- It does **not** make Claude continuously present. Discussion mode is still turn-based.
- It **does** convert "will Claude ever see this?" (unknown, anxious) into "Claude will see this on
  its next turn" (bounded, fine). It is a promise of *eventual attention*, not real-time presence.
  Reliability is what actually prevents the feeling of being ignored — liveness was never the
  requirement.

Note also that this is not a mode in the harmful vi sense. The dangerous kind of mode is one where
the *same input means different things* depending on state. Here the inputs are already distinct —
typing into a code editor versus typing into a reply field. So this is better understood as a
**session phase** than an input mode, and the usual modal-error risk is low.

Consequences you must still design for (none of these go away):

- **Show the current mode.** The mode indicator and the "is an agent attached" indicator are the
  same concept — collapse them into one. A reply typed outside discussion mode is a note for
  later, not a message, and the user must be able to tell which one they just wrote.
- **Track read state.** Each message needs a "seen by agent" flag. This is a read receipt, and it
  exists because presence is asymmetric.
- **Make "what's new" cheap.** The agent must be able to ask for unread messages only, without
  re-reading every thread. See `comment_list(unread_only=true)`.

Honest framing for the UI: *in discussion, your reply is guaranteed a response; outside it, you're
leaving a note.*

Prior art for the convention itself: Kent Beck's "two hats" rule — you are either adding
functionality or refactoring, never both — which works precisely because it is a convention that
reduces the state a human has to hold. IntelliJ's own Review Mode is the same idea already present
in the platform.

**The agreed protocol (v1), and it defines what RESOLVED means:**

- We are always, per file, in one of two phases: *discussing* it or *editing* it.
- While a file is under discussion, neither party edits it. Conversation happens in threads on it.
- Moving to edit **is** the act of consolidating the discussion: every OPEN thread on that file is
  driven to RESOLVED — either its conclusion becomes a code change (consolidated into the edit), or
  we explicitly decide it needs none (resolved, no action).
- Invariant: **you never edit a file that still has an open thread.**

This gives RESOLVED a precise meaning it otherwise lacks: a resolved thread's outcome is now
reflected in the code (or decided moot). OPEN means "unfinished conversation." That is a checkable
state, not a vibe — and it is the whole reason the convention buys us anything.

Two refinements that keep it from feeling heavy:

- **The scope is per-file, not global.** Editing file X requires X's threads resolved; threads on
  file Y are undisturbed. The invariant is exactly `comment_list(file, status=OPEN)` returning
  empty. No global "mode" to toggle.
- **Phases can be tiny.** The rule is not "batch all discussion, then batch all edits." It is fine
  to resolve one thread, make its edit, and go back to discussing. Rapid alternation is expected;
  the invariant is checked per edit, not per session. The "live chat" feel you wanted lives *inside*
  the discussion phase and survives intact.

**The one guard worth building** — and note it constrains the agent, the eager party, more than the
human: before editing a file, the agent calls `comment_list(file, status=OPEN)`; if it returns
anything, consolidate first. This is a checklist the agent follows, not a lock the plugin enforces
(§1.4). The human keeps the convention by habit; the agent keeps it by this check.

### 3.2 Line numbers are not identity

The agent will send you stale line numbers. This is not an edge case, it is the default: the agent
read the file N turns ago, the human has typed since, and the agent's mental line numbers are
wrong. Comments landing on the wrong line is the most annoying possible failure, and it will
happen constantly if you trust line numbers.

Mitigation, and this is why `comment_add` takes an `anchor_text` parameter: the agent passes the
text it *believes* occupies that line. The plugin verifies. If line 42 no longer matches, search a
small window around it for the text and anchor there; if nothing matches, reject the call with a
useful error telling the agent to re-read the file. Never silently anchor to the wrong line.

**The mode convention (§3.1) is the bigger lever here.** If code does not change during a
discussion phase, the agent's line numbers cannot drift *within* a phase — the file it read is the
file it comments on. That is arguably the strongest practical argument for the convention, more so
than the presence story: it collapses the stale-anchor problem from "happens constantly" to "can
only happen across an edit phase." Keep `anchor_text` regardless — it is the safety net for the
across-phase case and for anchors persisted across restarts (§7) — but expect it to fire rarely if
the convention holds.

### 3.3 Use RangeMarker. Do not roll your own anchoring.

`com.intellij.openapi.editor.RangeMarker` "points to the specified range of text in the document
and is automatically adjusted when the document text is changed," with optional invalidation on
external reload. The platform solves live anchoring for free. This was the single hardest problem
in a diff-based design and it evaporates here.

Caveats:

- RangeMarker is in-memory and bound to a `Document`. It is not persistence. See §7.
- `RangeMarker.isValid` goes false when the range is deleted. That's an orphan — see §3.4.

### 3.4 Orphans need a policy, not a delete

If the anchored code is deleted, do **not** silently drop the thread. That loses a conversation
because someone deleted a line, which is unacceptable.

Policy: mark the thread orphaned, keep it in the tool window's orphan list, and let the human
re-anchor it or resolve it. Orphaned threads are still readable.

**Under the §3.1 protocol, orphans should be rare** — a live thread does not sit over code that is
being deleted, because you resolve a file's threads *before* you edit it. Build the orphan list as a
safety net for convention leaks and cross-restart drift, but do not over-invest in it. It is not a
hot path.

### 3.5 Threading (the JVM kind)

Tool handlers do not run on the EDT — they arrive off a background HTTP thread. Editor markup and
inlay mutation must happen on the EDT, and document access needs the appropriate read action.
Marshal accordingly. This is where your first crashes will come from.

Verify the exact locking contract against current SDK docs rather than trusting this paragraph.

### 3.6 Scope: the missing shared referent

A diff bounds the conversation — both parties know what "this" refers to. A plain editor has no
such boundary. In practice this is softer here than in a review tool, because the human is driving
and saying "look at this file." But give the agent a `navigate` tool so it can point at something,
and default any "show me the threads" flow to the current VCS changelist rather than the whole
project.

---

## 4. Data model

```
Thread
  id            String        stable UUID
  file          String        project-relative path
  anchor        Anchor
  status        OPEN | RESOLVED | ORPHANED
  messages      List<Message>
  createdAt     Instant
  resolvedBy    Author?

Anchor
  marker        RangeMarker?  in-memory; authoritative while valid
  line          Int           0-based, last known good
  fingerprint   String        hash of anchor line text + small context window
                              -> used to re-anchor on reopen (§7)

Message
  id            String
  author        Author
  body          String        markdown
  createdAt     Instant
  seenByAgent   Boolean       read receipt; see §3.1

Author
  kind          HUMAN | AGENT
  displayName   String        "Muhammad" / "Claude"
```

`Author.kind` drives the visual treatment; `displayName` satisfies the "know who wrote them"
requirement. Keep it a struct, not a boolean — a second agent is plausible later.

---

## 5. Agent-facing tool surface

```
comment_add(file, line, body, anchor_text?)      -> thread_id
comment_reply(thread_id, body)                   -> message_id
comment_list(file?, status?, unread_only?)       -> [Thread]
comment_resolve(thread_id)                       -> ok
comment_reopen(thread_id)                        -> ok
navigate(file, line)                             -> ok    # moves the human's caret
```

Notes:

- `comment_list` is how the agent discovers human replies. It is the entire inbound channel.
  Convention to document in the skill/prompt: **call `comment_list(unread_only=true)` at the start
  of a turn.**
- Reading messages via `comment_list` should mark them seen. Make that explicit in the response so
  the agent knows what it just consumed.
- `anchor_text` is optional but strongly encouraged — see §3.2.

---

## 6. Transport: read this before writing any tool code

There are two options and the choice is currently uncertain. **Resolve this in the first hour;
do not let it block M0.**

### Option A — MCP tool extension (nicer, verify first)

Since 2025.2 JetBrains IDEs ship an integrated MCP server, and Claude Code speaks MCP natively —
no CLI shim, no skill file. The known reference pattern, from JetBrains' demo plugin:

```kotlin
class MyCustomTool : AbstractMcpTool<MyArgs>(MyArgs.serializer()) {
    override val name: String = "my_custom_tool"
    override val description: String = "Custom tool for the demonstration"

    override fun handle(project: Project, args: MyArgs): Response {
        return Response("Everything is fine, passed args: ${args.param1}")
    }
}

@Serializable
data class MyArgs(val param1: String, val param2: Int)
```

```xml
<depends>com.intellij.mcpServer</depends>

<extensions defaultExtensionNs="com.intellij.mcpServer">
    <mcpTool implementation="org.jetbrains.mcpextensiondemo.MyCustomTool"/>
</extensions>
```

Imports: `org.jetbrains.ide.mcp.Response`, `org.jetbrains.mcpserverplugin.AbstractMcpTool`.

**⚠️ Trust this pattern only after verifying it.** The demo it comes from
([MaXal/mcpExtensionPlugin](https://github.com/MaXal/mcpExtensionPlugin)) was last committed
**2025-05-13**, which *predates* the 2025.2 built-in MCP integration. There is a known
incompatibility report against exactly that migration
([LLM-18970](https://youtrack.jetbrains.com/projects/LLM/issues/LLM-18970/mcp-server-in-2025.2-incompatible-with-supported-extensions-for-2025.1-ide-with-mcp-server-1.0.30)),
and the official docs do not document the extension point at all. As of this writing that demo is
over a year stale.

**Step 0:** confirm `AbstractMcpTool` resolves against your target SDK. If it doesn't, use Option B
and revisit.

### Option B — Built-in HTTP server (boring, stable, works today)

`com.intellij.httpRequestHandler` with the `RestService` base class serves JSON on the IDE's
built-in server port. Long-standing platform API. This is a near-literal port of what hunk does,
and Claude Code can drive it with `curl` from Bash.

Recommendation: **Option B for M0** — it's an hour of work and unblocks everything. Move to
Option A once verified, or keep both.

---

## 7. Persistence and re-anchoring

RangeMarker dies with the Document. On reopen you must re-anchor from disk.

Do **not** persist raw line numbers and trust them. The file may have changed while the IDE was
closed — a `git pull`, a branch switch, a reformat. Persist the fingerprint (§4) and re-anchor by
searching for the anchor text near the last known line. Fall back to orphaned (§3.4) rather than
guessing.

Storage location is an open question (§10). Default suggestion: a JSON file under `.idea/`,
gitignored, per-project.

**The §3.1 protocol shrinks this too.** Because a file's threads are resolved at its edit boundary,
few OPEN threads survive across an edit phase — so cross-restart re-anchoring applies to a small,
mostly-idle set. Fingerprint re-anchoring is still worth having for that set, but it is not carrying
the design. Resolved threads, if you keep them at all (§10, they double as a decision log), don't
need live anchors — a last-known line is fine.

---

## 8. UI specification

- **Collapsed state (default):** a gutter icon on the anchored line. Distinct treatment for
  open / resolved / has-unread. Do not render inlays for every thread by default — a file with
  fifteen threads becomes unreadable.
- **Expanded state:** click the gutter icon to open a block inlay *below* the anchor line
  (`InlayModel.addBlockElement` with `showAbove = false`, plus an `EditorCustomElementRenderer`).
  The inlay renders the thread: each message with author name, visual author distinction,
  body, timestamp.
- **Reply affordance:** a text field inside the expanded inlay. This is the human's entire
  outbound channel — it should be one click and one keystroke away, not a dialog.
- **Resolve:** a button on the thread header.
- **Tool window:** all threads in the project grouped by file, plus an orphans section. This is
  where you find the conversation whose code got deleted.
- **Session indicator:** whether an agent session is currently attached (§3.1). Small, persistent,
  honest.

Note that the IDE gives you real text widgets, so the human→agent channel here can be *better*
than hunk's — hunk's agents can delete but not author human comments, and its inbound channel is
thin. Ours doesn't have to be.

---

## 9. Milestones

**M0 — Spike (target: one evening).**
One tool, `comment_add(file, line, body)`, over Option B transport. Renders a gutter icon on the
line. Hover shows the body. No threads, no replies, no persistence. This proves the whole loop:
agent writes, IDE shows. Everything else is incremental.

**M1 — Threads.**
Replies, attribution, resolve/reopen. Block inlay UI with inline reply field. RangeMarker
anchoring. `anchor_text` verification. In-memory only.

**M2 — Durability.**
Persistence, fingerprint re-anchoring on reopen, orphan handling and the orphan list.

**M3 — The async story.**
Unread tracking, `unread_only` filtering, session indicator, `navigate`, tool window.

M3 is not polish. It's what makes the asymmetric-presence problem (§3.1) survivable, and the
product is unpleasant without it.

---

## 10. Open questions for the human

1. **Where do comments live?** `.idea/` gitignored (private notes) vs. a committed file
   (teammates see them). Committed changes the product substantially. Assume private for v1.
2. **Per-branch?** Review-ish comments on a feature branch make sense; general margin notes
   probably shouldn't follow you across branches. Unclear. Assume not per-branch for v1.
3. **Should a human reply actively interrupt a running agent session,** or wait to be polled?
   Polling is the honest default and much simpler. Interrupt is the more "live chat" feel. This is
   the key product decision and it can be deferred past M1.
4. Multi-root projects — ignore for v1?

---

## 11. Prior art

- **[hunk](https://github.com/modem-dev/hunk)** — the inspiration. Read
  [docs/agent-workflows.md](https://github.com/modem-dev/hunk/blob/main/docs/agent-workflows.md)
  for its session protocol; the tool surface in §5 is deliberately shaped like it.
- **[AI Review plugin](https://plugins.jetbrains.com/plugin/30378-ai-review)** — existing
  Marketplace plugin: git diffs to Claude, findings as inline gutter annotations, powered by the
  Claude Code CLI. Worth an hour before writing code. Note the directionality differs: it drives
  Claude *from* the IDE; we want the agent to drive the *editor*, bidirectionally.
- **IntelliJ Review Mode** — the platform already renders gutter markers and inline review comments
  for GitHub PRs. Same affordance, wired to GitHub's API. Useful reference for the visual language.

### API references

- [RangeMarker](https://github.com/JetBrains/intellij-community/blob/master/platform/core-api/src/com/intellij/openapi/editor/RangeMarker.java)
- [Inlay Hints](https://plugins.jetbrains.com/docs/intellij/inlay-hints.html) ·
  [block inlay + gutter icon](https://platform.jetbrains.com/t/how-to-add-more-than-one-gutter-icon-for-a-block-inlay/1545)
- [Line Marker Provider](https://plugins.jetbrains.com/docs/intellij/line-marker-provider.html)
- [MCP Server docs](https://www.jetbrains.com/help/idea/mcp-server.html) ·
  [mcp-server-plugin](https://github.com/JetBrains/mcp-server-plugin)

---

## 12. A note on risk

JetBrains already ships Review Mode, an integrated MCP server, and an official Claude Code plugin.
The odds that "agent annotates code in the editor" becomes a first-party feature within a year are
non-trivial. That is an argument for M0 being genuinely small and for not gold-plating before the
interaction is proven — not an argument against building it.

Build M0 first. Find out whether the interaction feels good before investing in M2.
