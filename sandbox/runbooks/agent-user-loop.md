# The agent and you take turns in the margin

- **Key:** MARG-104 (schrek1/marginalis#104)
- **Scenario:** `AgentUserLoopTest` (tagged `core`)

## Protects

- A thread the agent posts through the API shows in the editor as a gutter icon on its line, and a click on the
  icon opens it with the agent's message.
- While the agent waits (`comment_wait`), Submit round is on. Your reply plus Submit round wakes the agent with
  that thread and your words.
- The agent's answer shows in the thread you have open, without reopening it.
- Resolve in the thread header reaches the agent: it lists the thread as resolved.
- A Live thread wakes the waiting agent on your Submit alone, with that thread only.

## Automated

`./gradlew uiSandboxTest --tests '*AgentUserLoopTest'` (or `-Pmarginalis.sandbox.tags=core` with the other critical
flows). The cases run in order on one thread of `src/greeting.py`; every check must PASS:

- the gutter icon on the thread's line, and the click that opens it
- Submit round on while the agent listens; the wake's reason `hand_back`, its one awaiting thread, the reply in it
- the agent's answer in the open thread
- the thread listed as resolved for the agent
- the agent listening again after the round; the thread going live; the wake's reason `live` with only that thread

## Manual

1. With a real agent waiting in its `comment_wait`, let it post a thread in a file you have open → the icon shows
   on the line; a click opens the thread.
2. Reply and press Submit round in the Marginalis tool window → the agent wakes with your reply and answers; the
   answer appears in the open thread.
3. Resolve the thread → the agent's next listing shows it resolved.
4. On another thread press the ⚡ toggle while the agent waits, then reply → only your Submit wakes the agent.

## On main

The flow is upstream's own, so there is no fix to compare against: the scenario guards the core loop against
regressions.
