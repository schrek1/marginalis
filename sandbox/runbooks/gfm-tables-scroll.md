# Tables and strikethrough in message bodies

- **Key:** MARG-19 (schrek1/marginalis#19)
- **Tags:** smoke, regression
- **Scenario:** `StablePrsTest.tables`

## Protects

- A Markdown table in a message renders as a table, and `~~text~~` renders struck through.
- A table wider than the panel keeps its natural width and scrolls sideways, while the prose around it wraps.

## Automated

`./gradlew uiSandboxTest --tests '*StablePrsTest.tables'`

- the table renders as a table
- strikethrough renders as `<s>`
- the wide table keeps its natural width
- the wide table scrolls sideways

## Manual

1. Have the agent post a narrow table and a 20-column table → in both the light and the dark theme, the header
   row and the borders are readable.
2. Scroll the wide table with Shift+wheel and with its scrollbar → only the table moves. The text above and
   below stays wrapped to the panel.

## On main

Not recorded yet.
