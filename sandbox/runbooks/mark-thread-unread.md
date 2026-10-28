# Mark a thread unread from the tool window's context menu

- **Key:** MARG-76 (schrek1/marginalis#76)
- **Scenario:** `MarkThreadUnreadTest`

## Protects

- A thread's context menu in the Marginalis tool window offers **Mark as Unread** once you have read it. Its
  latest message from someone else is new again, so the ✉ comes back in the tree, and in the Margin tab the card
  shows ✉ and its unread dot, as when that message arrived.
- On a thread with something new the same entry reads **Mark as Read**, and clears it.
- With several threads selected, one entry acts on all of them; a right-click outside the selection acts on the
  clicked thread alone.
- A thread of only your own words offers no read mark (platform test).
- A thread marked unread while its panel stays open is not read again by being on screen; opening it again or
  clicking into it reads it (platform test). The sandbox brings the Margin tab to the front without opening P,
  so P stays unread there while the tab shows it.

## Automated

`./gradlew uiSandboxTest --tests '*MarkThreadUnreadTest'`

Three threads you have read: A an agent question on a line of `src/review_target.py`, B an fyi on another line,
P a project thread.

- seed: no thread shows ✉ once read
- menu: a read thread offers Mark as Unread
- tree: A shows ✉ again; store: A's latest agent message is unread; menu: the entry now reads Mark as Read
- tree: A's ✉ is gone once marked read; store: nothing in A is unread
- menu: two selected threads get one entry for both; tree: A and B both show ✉; P was not selected and stays read
- Margin tab: P's card shows ✉ and the unread dot

## Manual

1. Open a thread with an agent message, read it, close it → its row shows no ✉.
2. Right-click its row → Mark as Unread → the ✉ is back on the row, in the gutter and on the file's row.
3. Open the thread → it is read again and the ✉ goes.
4. Ctrl+click two read threads, right-click one of them → Mark as Unread → both show ✉.

## On main

The context menu has no read mark: every menu check fails, and so do the checks that follow from choosing it.
