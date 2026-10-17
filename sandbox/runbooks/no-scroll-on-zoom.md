# Zooming keeps the code where the mouse points

- **Key:** MARG-21 (schrek1/marginalis#21)
- **Scenario:** `StablePrsTest.zoom`

## Protects

- Ctrl+mouse wheel with a thread open zooms around the line under the mouse. It does not scroll the code to
  the end of the thread.

## Automated

`./gradlew uiSandboxTest --tests '*StablePrsTest.zoom'`

- the zoom happened
- the line under the mouse stays put

## Manual

1. Open a long thread in the middle of a file. Point at a line well above it and zoom in and out with a real
   Ctrl+wheel → that line stays under the mouse, and the view never jumps to the thread.

## On main

Not recorded yet.
