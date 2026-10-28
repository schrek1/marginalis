# A thread's header stays readable in a narrow panel

- **Key:** MARG-65 (schrek1/marginalis#65)
- **Scenario:** `ThreadHeaderInANarrowPanelTest`

## Protects

- In a narrow thread panel, such as one side of a side-by-side review diff, the status text ends in "…" where the
  buttons start, instead of running under them. It keeps at least its first word ("open…").
- In a panel narrower than the buttons, the buttons that do not fit move behind › at the header's end.
- In a wide panel the status text shows in full.

## Automated

`./gradlew uiSandboxTest --tests '*ThreadHeaderInANarrowPanelTest'`

- the panel is narrow (setup)
- the status text ends where the step buttons start
- the buttons stay inside the header
- the status text is shortened to fit
- the status text keeps its first word
- the panel is wide (setup)
- the status text shows in full
- the status text ends where the step buttons start (wide)

## Manual

1. Open a step in a side-by-side review diff and narrow the IDE window → the header reads "open · …" up to the first
   button; nothing is painted over.
2. Narrow it further → the last buttons move behind ›; clicking › lists them and each still works.

## On main

The status sits on the header's west and the buttons on its east, so in a narrow panel the buttons are painted over
the status text, and the buttons run off the header's left edge: "the status text ends where the step buttons
start", "the buttons stay inside the header" and "the status text is shortened to fit" fail.
