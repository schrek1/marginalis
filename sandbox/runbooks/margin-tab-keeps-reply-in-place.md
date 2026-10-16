# The reply you are typing in the Margin tab stays put while other threads change

- **Key:** MARG-45 (schrek1/marginalis#45)
- **Tags:** regression
- **Scenario:** `MarginTabKeepsReplyInPlaceTest`

## Protects

- While you write a reply in one thread, a new message in a thread above it does not move the reply box: it keeps
  its place on screen, the focus, the draft and the caret.
- The same holds for the draft of a new project thread.
- Reading without a draft, the card at the top of the view stays where it is when a thread above it grows.
- At the very top of the list nothing is held, so threads arriving above come into view as before.
- Expanding or folding a card keeps that card where you clicked it.

## Automated

`./gradlew uiSandboxTest --tests '*MarginTabKeepsReplyInPlaceTest'`

- the threads above and in view are expanded
- 1-reading: the message made the thread above taller
- reading without a draft, the topmost visible card stays where it was on screen
- 2-draft-in-thread: the message made the thread above taller
- a reply draft stays at the same place on screen when the thread above grows
- the reply box keeps the focus
- the reply box keeps the draft and the caret
- 3-draft-new-thread: the message made the thread above taller
- a new project thread's draft stays at the same place on screen when another thread grows
- the new thread's reply box keeps the focus, the draft and the caret

## Manual

1. In the Margin tab, scroll so an expanded thread is half above the view, start a reply in the thread below it,
   and let an agent post a long message into the upper thread → the reply box does not move and you keep typing.
2. With the reply still open, switch to another application, let an agent post into the upper thread, come back →
   the reply box is where you left it.
3. Scroll to the very top and let an agent start a new project thread → it appears at the top, in view.
4. Click the header of a card in the middle of the view to expand it, then again to fold it → the header stays
   under the mouse both times.

## On main

Run against schreko_stable without the change (7fe12c5), three checks fail, each by exactly the height the thread
above gained (281 px): the topmost visible card while reading, the reply draft, and the new thread's draft (oldest
first puts it below the threads). Focus, draft and caret survive there too, so those checks pass.

On upstream main the view also jumps to the end of the grown thread: its message panes scroll their caret into
view. The change takes MARG-21's caret fix for that. With newest first, a new thread's draft sits on top, so the
third check cannot fail on main.
