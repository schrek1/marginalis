# Steps in the same file stay in the editor you are reading

- **Key:** MARG-18 (schrek1/marginalis#18)
- **Scenario:** `StablePrsTest.stayInDiff`

## Protects

- Next and Previous Step in a local diff keep the diff in front and scroll to the step there.
- Resolve in a pull request's review diff does the same, instead of opening the file's regular editor tab.

## Automated

`./gradlew uiSandboxTest --tests '*StablePrsTest.stayInDiff'`

- next step in a local diff keeps the diff in front
- the next step opens in that diff

Known gap, not in the default run: `StablePrsTest.resolveInReviewDiff` (Resolve in the diff of a pull request
that is not checked out), run with
`MARGINALIS_SANDBOX_KNOWN_GAPS=true ./gradlew uiSandboxTest --tests '*StablePrsTest.resolveInReviewDiff'`.

## Manual

1. Open a GitHub pull request that is not checked out. Open its review diff of a file with two walkthrough steps
   → both thread icons show in the diff.
2. Open step 1 and press Resolve → the diff stays in front and scrolls to step 2. No regular editor tab opens.
3. Press Previous Step → the diff stays in front.

## On main

Not recorded yet.
