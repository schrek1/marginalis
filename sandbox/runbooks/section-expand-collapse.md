# Expand and Collapse in the tool window

- **Key:** MARG-25 (schrek1/marginalis#25)
- **Tags:** regression
- **Scenario:** `SectionExpandCollapseTest`

## Protects

- Collapse folds one level further out with each press, from the selection's folder up to its section and then
  every section. It folds everything inside, as IntelliJ's Collapse All does.
- Expand opens the selected section all the way down, and then every section.
- A folder you fold stays folded when the tree refreshes.

## Automated

`./gradlew uiSandboxTest --tests '*SectionExpandCollapseTest'` (the deep walkthrough fixture)

- Collapse folds one level further out with each press, up to the section
- Collapse up to the section leaves the other sections open
- Collapse folds everything inside, as IntelliJ does
- past the section, Collapse folds every section
- Expand opens the selected section all the way down
- Expand leaves the other sections folded
- Expand again opens every section all the way down
- Expand on a folded folder opens that folder
- a folded folder stays folded when the tree refreshes

## Manual

1. Hover over Expand and Collapse in the toolbar → each shows its tooltip.
2. Fold a folder, then let the agent reply in one of its threads → the folder stays folded.

## On main

Not recorded yet.
