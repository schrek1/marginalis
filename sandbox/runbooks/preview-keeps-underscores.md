# Thread previews keep the underscores of names

- **Key:** MARG-217 (schrek1/marginalis#217)
- **Scenario:** `PreviewKeepsUnderscoresTest`

## Protects

- A thread's one-line preview keeps a name as written: `legacy_helper` in a code span and a plain `old_total_price`
  both keep their underscores. Only Markdown's markers go: the `_` and `*` of emphasis, backticks, heading `#`s,
  quote `>`s and list bullets.
- That holds wherever the preview shows: a thread's row in the Marginalis tool window, the balloon announcing an
  agent's reply, the Margin tab card's last-message line (and the gutter tooltip, platform test).
- A `*`, `#` or `>` inside a code span or in ordinary prose stays too (platform test).

## Automated

`./gradlew uiSandboxTest --tests '*PreviewKeepsUnderscoresTest'`

A an agent thread on line 1 of `src/review_target.py` naming `` `legacy_helper` ``, B on line 5 with a plain
`old_total_price` next to `_this_` and `**that**`, P a project thread whose agent reply names `` `cache_ttl` `` and
`max_retry_count` and arrives while the Margin tab is closed.

- tree: A keeps the underscores of its code span
- tree: B keeps a plain snake_case name and drops the emphasis markers
- balloon: the reply keeps its underscores
- Margin tab: the card's last-message line keeps its underscores

## Manual

1. Let an agent leave "`` `legacy_helper` `` has no callers left." on a line → its tool window row reads
   "legacy_helper has no callers left.", without backticks.
2. Let it reply in a project thread with "_then_ tag it" while the Margin tab is closed → the balloon reads
   "then tag it", the name in it keeps its underscores.

## On main

Every check fails: the preview drops every `_`, so the rows read "legacyhelper" and "oldtotalprice".
