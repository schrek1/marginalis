# Guided steps show just their number

- **Key:** MARG-26 (schrek1/marginalis#26)
- **Tags:** regression
- **Scenario:** `guided_step_labels.py`

## Protects

- With several walkthroughs open, every step shows just `(n/total)`. The section title names the walkthrough.

## Automated

`py sandbox/scenarios/guided_step_labels.py`

- both walkthroughs have their own section
- round 2 steps show just their number
- round 1 steps show just their number

## Manual

1. With two real review rounds open, read the Guided sections → no step row repeats the walkthrough's label.

## On main

The label is glued to the number ("(SHOP-42 · round 2 – …1/3)"), so both step checks fail.
