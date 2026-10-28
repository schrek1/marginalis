# Updating the plugin keeps the IDE running and the margin working

- **Key:** MARG-108 (schrek1/marginalis#108)
- **Scenario:** `UpdatingThePluginTest`

## Protects

- Update replaces the running plugin without a restart, while you have a thread open in the editor, the Margin tab
  open and the tool window up.
- The update logs no error from the plugin (no "IDE error occurred").
- Afterwards every thread is back with its messages, the marker sits on its line, the tool window and the Margin tab
  list the threads, and a round between the agent and you still works: the agent's answer shows in the thread and
  your reply reaches the agent.

## Automated

`./gradlew uiSandboxTest --tests '*UpdatingThePluginTest'`. Needs the prebuilt plugin (Docker mode, as the suite
runs). The harness makes the next build itself: the installed plugin's folder as a distribution zip whose
`plugin.xml` carries the version plus `.1` (`PluginUpdate`), copied into the container. The update then runs the two
steps of the IDE's own Update: unload the loaded version as an update, waiting until its class loader is gone, then
install and load the new one. Three cases in the default run, every check must PASS, and a fourth one gated:
- the user works in the margin: a line thread with the user's reply, open in the editor; a project thread in the
  Margin tab; the tool window lists both;
- the next build loads into the running IDE: no restart asked for, the IDE reports the new version, and the IDE log
  written during the update holds no error from the plugin;
- the margin works after the update: threads, messages, marker, tool window, Margin tab, and a new agent ↔ user
  round;
- nothing keeps the old version in memory, a known gap (MARG-240 (schrek1/marginalis#240)) that runs only with
  `MARGINALIS_SANDBOX_KNOWN_GAPS=true`. It holds a weak reference to the old version's class loader and expects it
  collected within 60 s. When it is not, it takes a heap dump in the IDE and puts the platform's own analysis (the
  one behind "Save Memory Snapshot") into the failure: the paths that keep the class loader.

The update's errors are read from the IDE's own `idea.log` in the container, from the moment the update starts.

## Manual

1. In your own IDE, with threads in the margin, a thread panel open, the Margin tab and the tool window up, install a
   newer build through Settings → Plugins → Update (or a dev build from disk).
2. → No restart asked for; Help → Show Log holds no `ERROR` naming `dev.marginalis`; the threads, the tab and the tool
   window are back, and replying works.
3. Wait half a minute → no "Restart advised: 'Marginalis' plugin didn't unload fully" balloon (today it comes: #240).

## On main

The update path is upstream's own; the scenario guards it against regressions. Its error check was proven by a
mutation: a plugin that logs an error while it unloads fails "the update logged no error from the plugin" with that
entry and the plugin's frame.

MARG-72's two errors (schrek1/marginalis#72) stay out of the sandbox's reach on IntelliJ IDEA 2026.2.3, and the
scenario passes with that fix reverted. The platform unloads the plugin in one EDT block (about 0.4 s), so the tool
window cannot repaint in the middle and meet the withheld avatar store. The `file is not open` error needs a split
editor and code from before upstream's "Each project keeps its own Margin tab". Both remain guarded by the platform
tests MARG-72 added.
