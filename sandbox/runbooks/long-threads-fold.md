# Long threads fold to what matters, and keep folding as the conversation goes on

- **Key:** MARG-170 (schrek1/marginalis#170)
- **Scenario:** `LongThreadsFoldTest`

## Protects

- A read long thread opens with its last two messages open and the others folded to one line (who wrote it and
  how it starts); a run of more than three folded messages stacks behind one "Show N more messages" link. Unread
  messages never fold, and reading a thread folds nothing under the reader.
- The arrow beside a message folds it to one line, and opens it again.
- As the conversation goes on in an open thread, every new message (the agent's answer or your own reply) folds the
  read messages before the last two again, without a click. Kept open: unread messages, the message you are editing,
  a message you opened with its arrow, and the first message when Settings keep it open.
- With the whole thread expanded (Expand All), new messages fold nothing; back in the default view the next message
  folds it again.
- While you are scrolled up in the middle of a thread, a new message folds nothing, so the text you read stays where
  it is; once you are back at the end, the next message folds the thread.
- Settings → how long threads open → "fold nothing" opens a read long thread with every message open.
- The same folding in the Margin tab (platform tests).

## Automated

`./gradlew uiSandboxTest --tests '*LongThreadsFoldTest'`. The agent posts a seven-message thread on a line of
`src/review_target.py`; the checks:

- opens_folded: unread, every message is open; reading folds nothing under the reader; read and reopened, messages 6
  and 7 are open, message 1 folds to its opening line and the four between stack behind one link
- fold_one_message: the arrow folds message 7 to its opening line and opens it again
- keeps_folding: the agent's answer leaves 7 and 8 open; your own reply leaves 8 and 9 open; the stack grows to six
- expanded_folds_nothing: with Expand All a new message folds nothing; back in the default view the next one folds
- reading_in_the_middle: a thread taller than the view, scrolled to message 3: a new message folds nothing and
  message 3 does not move; scrolled to the end, the next message leaves only the last two open
- setting_fold_nothing: with "fold nothing" the read thread opens all eleven messages

Screenshots of each stage land in the runtime's `shots/long_threads_fold/`.

## Manual

1. In your own IDE, keep a long read thread open and talk with an agent for a few rounds → after each answer the
   thread shows only the latest question and answer open; the panel does not grow round after round.
2. Scroll up into the middle of a long open thread and let the agent answer → nothing folds or moves under you.
3. Open an older message with its arrow, let the agent answer → that message stays open.

## On main

`main` keeps every message an open thread has shown open, so five checks fail (messages 6 and 7 stay open as 8, 9
and on arrive): the three of keeps_folding, "back in the default view the next message folds the thread again" and
"back at the end the next message folds the thread". The other checks cover upstream's folding and pass on main.
