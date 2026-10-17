# Tool window arrows keep their place and walk Guided steps by number

- **Key:** MARG-24 (schrek1/marginalis#24)
- **Scenario:** `ToolwindowStepNavigationTest`

## Protects

- Next and Previous in the tool window continue from the selected step, even after the tree refreshes.
- In a Guided section, First, Previous, Next and Last Step follow the step numbers. In Open they follow the
  tree.

## Automated

`./gradlew uiSandboxTest --tests '*ToolwindowStepNavigationTest'` (the review-sized deep walkthrough fixture)

- Next keeps a selection after every press
- Next walks up the step numbers without jumping back
- Previous walks down the step numbers without jumping back
- First Step lands on the lowest open step
- Last Step lands on the highest open step
- in Open, Next follows the tree, not the numbers
- in Open, Next stays in Open
- the selection is still there after the tree refreshes

## Manual

1. During a real review walkthrough, press Next in the tool window while the agent posts replies → the
   selection never jumps back to the first node.

## On main

Not recorded yet.
