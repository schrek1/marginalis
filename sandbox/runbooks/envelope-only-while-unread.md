# The ✉ envelope shows only while something in the thread is unread

- **Key:** MARG-36 (schrek1/marginalis#36)
- **Tags:** regression
- **Scenario:** `EnvelopeOnlyWhileUnreadTest`

## Protects

- In the Marginalis tool window a thread row shows ✉ only while it is your move and the thread has a message
  you have not read. A file row's `✉ N` counts only those threads.
- The tool window's stripe button keeps its envelope badge only while such a thread exists. A red blocker
  badge still wins.
- In the Margin tab the title (`✉ N`) and the thread cards follow the same rule.
- What follows the real turn is unchanged: the editor tab and Project view file badges, the "Awaiting You"
  filter, the Margin tab header's "N your move" count, "Also your move: N threads in files" and the agent
  API's `awaiting`.

## Automated

`./gradlew uiSandboxTest --tests '*EnvelopeOnlyWhileUnreadTest'`

Four line threads in `src/review_target.py`: A an agent reply you have not read, B one you read but did not
answer, C one you answered, D an FYI you read. Project A and Project B repeat A and B in the Margin tab.

- setup: A and Project A are still unread (and again before the Margin tab checks)
- tree: thread A shows you, B none, C agent, D none, Project A you, Project B none
- tree: the file row counts ✉ 1 and ✈ 1
- stripe: the envelope badge stays while A is unread
- Margin tab: the title counts only the unread project thread (✉ 1)
- Margin tab: Project A's card keeps the envelope, Project B's card shows no envelope
- Margin tab: the header still counts both as your move
- API: B and Project B stay in `awaiting=user`
- the file's editor-tab badge still follows your move
- stripe: once everything is read the button is plain
- tree: the file row shows no ✉ once everything is read
- Margin tab: the title is Margin once everything is read
- the file's editor-tab badge still follows your move (after everything is read)
- tree: a new agent message on B brings its envelope back; stripe: the envelope badge returns with it
- stripe: an open blocker's red badge wins over the envelope

The robot marks B, C, D and Project B read through the store, as an open thread panel does once it is on
screen. Project A is posted while the code editor is in front: a your-move card opens expanded, and on screen in
the active IDE it is read at once. "A and Project A are still unread" is checked after the setup and again right
before the Margin tab checks.

## Manual

1. Let an agent reply on a line thread and leave it unread → the tree row, the file row's count and the
   stripe button show ✉.
2. Open the thread in the editor and read the reply, without answering → the row loses ✉, the file row's
   count drops by one, and the stripe button turns plain once nothing else is unread. The editor tab's badge
   keeps ✉.
3. Filter the tree by Awaiting You → the read thread is still listed.
4. Read an unread project thread in the Margin tab → its card loses ✉ and the tab title's count drops by one
   (or reads "Margin"). The header's "N your move" does not change.
5. Let the agent reply again on the read thread → ✉ comes back on its row, its card and the stripe button.

## On main

On `schreko_stable` without the change, 8 of 24 checks fail: tree B and Project B (✉ shown), the file row's
count (✉ 2), the Margin title (✉ 2), Project B's card (✉ shown), and once everything is read the stripe
button (still the envelope), the file row (still ✉ 2) and the title (still ✉ 2). The rest pass there too.
With the change all 24 pass.
