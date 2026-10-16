# An open thread shows the agent's reply

- **Key:** MARG-14 (schrek1/marginalis#14)
- **Tags:** smoke, regression
- **Scenario:** `ApiReplyRerendersTest`

## Protects

- A reply the agent posts through the HTTP API shows in a thread that is already open, and the panel grows to
  fit it. This holds in the editor, after the user's own reply, in a walkthrough step and in the Margin tab.

## Automated

`./gradlew uiSandboxTest --tests '*ApiReplyRerendersTest'`. These two checks run for each of the four cases
(plain, after-user-reply, walkthrough-step, margin-tab-step):

- the open thread renders the agent's reply
- the panel grows to fit it

## Manual

1. Keep a thread open in your own IDE while a real agent replies → the reply appears without reopening the
   thread, and the panel is tall enough to show all of it.
2. If it ever does not: save `idea.log` and the plugin version (`ping`), and attach them to MARG-14.

## On main

The reported bug did not reproduce in the sandbox (8/8 on `schreko_stable`), so this is a regression guard,
not a fix check. Expected to pass on `main` too.
