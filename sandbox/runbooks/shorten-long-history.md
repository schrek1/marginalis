# A long thread shortens its read history

- **Key:** MARG-12 (schrek1/marginalis#12)
- **Scenario:** `ShortenLongHistoryTest`

## Protects

- A thread of more than four messages shows the first message, the last two and every unread message; each run
  of read messages between them hides behind one "Show N more messages" link.
- Messages never hide under the reader: a message read while the thread is open stays until the thread opens
  again.
- The link shows the full history, and the full history stays on the next visit (until the IDE restarts).
- The thread header offers Shorten History while the full history shows, and Show Full History while the middle
  is hidden.

## Automated

`./gradlew uiSandboxTest --tests '*ShortenLongHistoryTest'`. The agent posts a seven-message thread; the checks:

- an unread long thread shows every message and hides nothing
- reading does not hide messages under the reader; the still-open thread offers to shorten
- read and reopened, the thread shows the first and the last two, and the four between hide behind one link
- the link shows the full history, which offers to shorten and stays on the next visit
- Shorten History hides the middle again and offers the full history again

Screenshots of each stage land in the runtime's `shots/shorten_long_history/`.

## Manual

1. Read a long thread, close it, open it again → the middle is gone, one link says how many messages it hides,
   and the panel is not taller than the visible messages.
2. Jump to a hidden message from elsewhere (a walkthrough step or a link to the message) → the full history opens
   and the message scrolls into view.
3. Start editing a message in the middle, let the thread rerender (the agent replies) → the edited message stays
   visible.

## On main

`main` never shortens a thread: the unread and still-open checks pass, every check from "offers to shorten" on
fails.
