# Grouping consecutive steps by file as a Settings default

- **Key:** MARG-278 (schrek1/marginalis#278)
- **Scenario:** `GroupingByDefaultTest`

## Protects

- Without the Settings default a new project lists every walkthrough step as its own row.
- With it ticked, a new project groups consecutive steps in one file under that file.
- A project that unticks Group Consecutive Steps by File keeps its own off across a restart, over a ticked Settings
  default, and stores it in `workspace.xml`.

## Automated

`./gradlew uiSandboxTest --tests '*GroupingByDefaultTest'`. It posts the hopping SHOP-77 walkthrough into fresh
projects, switches the Settings default through the settings service, and reads the tool window's tree shape; every
check must PASS.

## Manual

1. Post a walkthrough that hops between files → the Marginalis tool window lists every step as its own row.
2. Settings › Tools › Marginalis › tick "Group consecutive steps by file by default" › Apply → the open tool window
   regroups at once, with no restart.
3. Untick Group Consecutive Steps by File in the Walkthrough Layout menu, then untick and tick the Settings default
   again → the project stays ungrouped.

## On main

Grouping is a per-project choice that starts off: every new project or worktree has to switch it on by hand.
