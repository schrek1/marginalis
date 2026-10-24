# Margin text renders as typed

- **Key:** MARG-23 (schrek1/marginalis#23)
- **Scenario:** `MarginTextSpacingTest`

## Protects

- A single Enter inside a paragraph is a line break. A second blank line stays an empty line.
- Paragraphs get a gap; a list sits close under its lead-in, its items together (upstream's layout since 0.2.6).
  The first block sits flush with the header.

## Automated

`./gradlew uiSandboxTest --tests '*MarginTextSpacingTest'`

- a single Enter starts a new line
- two trailing spaces still give exactly one break (guard)
- a second blank line adds an empty line
- paragraphs are set apart
- the first paragraph sits flush with the header
- a list sits close under the text above, closer than a paragraph
- list items sit together
- the text below a list is set apart as a paragraph
- a message starting with a list has no gap on top (guard)

## Manual

1. Type a reply with single Enters, two blank lines, a paragraph and a list, then send it → it reads as typed,
   both in a thread in the editor and in the Margin tab.

## On main

Every check fails except the two guards.
