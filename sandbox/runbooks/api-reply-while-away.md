# A thread you left open shows the reply that came while you were away

- **Key:** MARG-50 (schrek1/marginalis#50)
- **Tags:** regression
- **Scenario:** `api_reply_while_away.py`

## Protects

- You write into an open thread and move to another tab while the agent works. The agent replies through the
  HTTP API while it keeps listening (`comment_wait`). When you come back, the thread shows the reply, and the
  panel is tall enough for it. This holds for a thread open in a file and for a step in the Margin tab.
- A reply into a thread that stays in view still shows at once (control).

## Automated

`PYTHONPATH=<harness>/sandbox py sandbox/scenarios/api_reply_while_away.py --feature-dir <checkout>`. Each case
(control-in-view, editor-tab-away, margin-tab-away) runs these checks:

- the agent's long-poll is open, and still open after the reply
- the open thread renders the agent's reply
- the panel grows to fit it

The two away cases also check their precondition: the thread's editor tab, or the Margin tab, left the screen
before the agent replied.

## Manual

1. Open a thread in a file, write a reply, switch to another file's tab. Let a real agent answer while it keeps
   a `comment_wait` open, then switch back → the answer is there without clicking anything.
2. The same with a project thread in the Margin tab, switching to a file and back.

## On main

`editor-tab-away: the open thread renders the agent's reply` fails: the panel shows only the user's message
until something else changes on the thread (the Live toggle, in the report). `margin-tab-away` is expected to
fail the same way, because the Margin tab keeps the expanded panel across a tab switch. `control-in-view`
passes on main.
