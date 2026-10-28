# An Agree shows 👍, then what the agent did with it

- **Key:** MARG-75 (schrek1/marginalis#75)
- **Scenario:** `AgreeShowsThumbsUpTest`

## Protects

- After the user presses Agree, the thread shows 👍 instead of ✈ in the tool window tree and on its Margin tab
  card. It adds no ✈ to the file row's count, the editor tab's badge or the Margin tab title, and the tool
  window's "Awaiting Agent" filter leaves it out.
- The agent still owes an answer: the thread stays in the agent's `awaiting=agent` until it calls
  `comment_agent_state`.
- The agent answers `working` → the thread shows ⏳; `acknowledged` → no sign, and the thread stays open for
  the user to resolve. Either way it awaits nobody on the wire.
- The next message from either side clears the answer, and the usual ✉ / ✈ come back.

## Automated

`./gradlew uiSandboxTest --tests '*AgreeShowsThumbsUpTest'`

Four line threads in `src/review_target.py`: the user agrees with A and leaves it, agrees with B (the agent
answers `working`), agrees with C (the agent answers `acknowledged`), and answers D in words. A project thread
repeats the Agree in the Margin tab.

- setup: A, B and C end on the user's Agree
- tree: A shows 👍, B shows ⏳, C shows no sign, D still shows ✈
- tree: the file row counts only D as the agent's move (✈ 1)
- API: A and D are in `awaiting=agent`, B and C await nobody
- API: B carries `agent_state` working, C acknowledged and stays open
- editor tab: with only agreed threads left (D resolved), the file shows no ✈ badge; the file row shows no counts
- tree: the Awaiting Agent filter leaves out the agreed thread A
- Margin tab: the agreed project thread's card shows 👍, and the title counts no agent's move for it
- tree: the agent's reply on B replaces ⏳ with the usual signal (✉ while unread); API: B is the user's move
  again and no longer carries an `agent_state`

## Manual

1. Let an agent ask something on a line, then press Agree in the thread → the tree row shows 👍, not ✈.
2. Let the agent answer with `comment_agent_state` `working` → the row shows ⏳.
3. Let the agent reply → ⏳ gives way to ✉ (your move).
4. Agree with another thread and let the agent answer `acknowledged` → the row shows no sign; the thread stays
   open until you resolve it.

## On main

On `schreko_stable` without the change, the agreed threads show ✈ and count in the file row, the editor tab
badge and the Awaiting Agent filter, and `comment_agent_state` does not exist, so the scenario fails from the
first answer on.
