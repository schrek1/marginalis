# Marginalis in screenshots

Each screenshot notes the release it was captured on. The product moves
quickly; if your install looks different, trust your install and the
[changelog](../CHANGELOG.md). Notably, 0.1.29 replaced every icon: gutter
marks are now outline speech bubbles in the plugin's colors, and the
● / ○ turn glyphs became ✉ / ✈ badges on file icons. Later releases added
what these captures predate: the Margin tab for project threads, author
pictures, Submit round with its listening indicator, and Live.

## A margin conversation

Two agent roles debate a design question in the margin, the user
decides, and the agent locks the decision in. Author colors are stable
per identity, so several agents can share one thread without confusion.

![A thread on a line of code with five messages from three
identities](images/margin-conversation.png)

<sub>Captured on v0.1.22.</sub>

## A guided walkthrough

The agent numbers steps through a change — "look here 1st, 2nd, …" —
and the tool window sorts them in walking order. Each step opens beside
the code it describes, and Next Step is one shortcut away.

![The tool window's Guided section beside an open step
panel](images/guided-walkthrough.png)

<sub>Captured on v0.1.22.</sub>

## Severity

Review findings marked `blocker` gate the merge and show in red: in the
section counts, on the thread row, and as the panel's badge and accent.
Everything else stays quiet.

![The tool window counting one blocker beside the blocker's
panel](images/severity-blockers.png)

<sub>Captured on v0.1.22.</sub>

## Anatomy of a thread

The header carries the walk arrows, the Live switch, Select in
Marginalis, Copy Reference and the thread's lifecycle: resolve, delete,
close. The conversation reads top to bottom with author and
time; the reply row stays folded until you write.

![An open thread panel showing the header toolbar and a two-message
exchange](images/thread-anatomy.png)

<sub>Captured on v0.1.22.</sub>

## The tool window

Every thread in the project, grouped by file, with step positions.
Double-click any row to open the thread beside
its code.

![The Marginalis tool window with a five-step guided
tree](images/tool-window.png)

<sub>Captured on v0.1.22.</sub>
