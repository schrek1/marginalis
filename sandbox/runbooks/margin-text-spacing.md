# Margin text renders as typed

- **Key:** MARG-23 (schrek1/marginalis#23)
- **Tags:** regression
- **Scenario:** `MarginTextSpacingTest`

## Protects

- A single Enter inside a paragraph is a line break. A second blank line stays an empty line.
- Paragraphs and lists get a small gap. The first block sits flush with the header.

## Automated

`./gradlew uiSandboxTest --tests '*MarginTextSpacingTest'`

- a single Enter starts a new line
- two trailing spaces still give exactly one break (guard)
- a second blank line adds an empty line
- paragraphs are set apart
- the first paragraph sits flush with the header
- a list is set off from the text above
- list items get a small gap
- the gap below a list matches a paragraph gap
- a message starting with a list has no gap on top (guard)

## Manual

1. Type a reply with single Enters, two blank lines, a paragraph and a list, then send it → it reads as typed,
   both in a thread in the editor and in the Margin tab.

## On main

Every check fails except the two guards.
