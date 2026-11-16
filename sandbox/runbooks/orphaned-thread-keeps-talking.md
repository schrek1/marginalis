# A conversation goes on after its code is gone

- **Key:** MARG-275 (schrek1/marginalis#275)
- **Scenario:** `OrphanedThreadKeepsTalkingTest`

## Protects

- An orphaned thread the user answers shows ✈, the agent's move.
- The next Submit round hands that orphan to the agent, marked `orphaned`.
- A live thread stays live when it orphans, and the user's reply in it wakes the agent at once, with that thread
  alone.

## Automated

`./gradlew uiSandboxTest --tests '*OrphanedThreadKeepsTalkingTest'`. Each case orphans its thread by erasing its
anchored line in `src/long_module.py`, then replies through the thread's panel; every check must PASS.

## Manual

1. Let the agent post a thread, erase its line → the thread is orphaned; reply in it → its row shows ✈.
2. With the agent listening, press Submit round → the agent gets the orphan and rescues it before answering.
3. Switch a thread Live, erase its line, reply → the agent answers at once.

## On main

An orphan is nobody's turn: no ✈, the Submit round leaves it out, and orphaning switches Live off, so the reply
never reaches the agent.
