# Bug Assessment: Android floating widget unusable + animation not desktop-like

- **Slug**: android-widget-unusable
- **Created**: 2026-09-28
- **Source**: pasted text (user report in Persian, no URL)
- **Verdict**: valid (4 of 5 defects reproduced by direct code reading; the user's exact on-device symptom needs confirmation)
- **Severity**: high — the primary interaction path is blocked or laggy, and one defect can leave the user unable to type at all

## Report (verbatim or summarized)

> ویجت فلوتینگ درست کار نمی‌کند. انیمیشن ضبط و تبدیل مثل حالت مک نیست. اپتیمایز نشده برای
> موبایل. اصلاً کارآمد نیست. با هر ابزار و اسکیلی که از صدا بزن و این مشکل رو حل کن.

Translated: the floating widget does not work properly; the recording and transcribing
animation is not like the macOS version; it is not optimized for mobile; it is not
effective/usable at all. Fix it using whatever tooling is appropriate.

Reported against the installed release **v1.2.0** (`android-v1.2.0`).

## Symptom

Tapping the floating lips button either does nothing or visibly lags before refusing, and
when dictation is started through the new keyboard the user's normal keyboard disappears.
The listening UI is a scaled/rotated copy of the app icon, not the desktop's dot + 9-bar
waveform + live caption.

Expected: tap the button anywhere → speak → text appears in the focused field immediately,
with the desktop's listening animation.

## Reproduction

1. Install v1.2.0; enable the Sokhan accessibility service.
2. Open any app with a text field (chat list, browser omnibox, a WebView-based form).
3. Tap the floating lips button.
   - Observed: nothing happens, or a "no text field here" toast appears even though a field
     is on screen. [NEEDS CLARIFICATION: which apps the user tried, and whether Sokhan was
     also selected as the active keyboard.]
4. Alternative path: select Sokhan as the system keyboard and open a field.
   - Observed: only the "🎤 گوش بده" strip is shown; the normal keyboard is gone and there is
     no key to return to it.

## Suspected Code Paths

- `SessionManager.kt:107-131` (`start()`) — refuses to record unless `hasPasteTarget()` is
  true (or the IME is attached). This gate was added in v1.0.1 and is the most likely cause
  of "the widget stopped working properly".
- `SokhanAccessibilityService.kt:263-297` — `hasPasteTarget()` → `pasteTarget()` →
  `findPasteTarget()` walks up to **300 nodes with one binder IPC per `getChild()`**,
  synchronously, on the tap callback thread. On a real chat/webview screen that is hundreds
  of cross-process calls before the button reacts → perceived dead/laggy tap. It also
  requires `node.isEnabled && actionList contains ACTION_PASTE|ACTION_SET_TEXT`, which
  many focused editors do **not** advertise until a long-press, so legitimate targets are
  rejected.
- `SokhanAccessibilityService.kt:103-104` — `root` is a VERTICAL `LinearLayout` with the
  **caption added above the row**. The caption starts `GONE` and becomes `VISIBLE` as soon
  as the first interim result lands, which changes the window height and moves the button
  down mid-recording.
- `SokhanAccessibilityService.kt:146-147` — drag clamp uses `size` (the 64 dp button) but the
  window is now wider (waveform + caption), so the button can be parked partly off-screen.
- `SokhanInputMethod.kt:96-127` — no `switchToNextInputMethod` / input-method-picker affordance
  (grep: zero matches) and `onEvaluateInputViewShown` is not overridden, so while Sokhan is
  the selected IME it occupies the keyboard area showing one button and no way back.
- `SokhanAccessibilityService.kt:169-176` + `WaveView.kt:23` — "animation" is a scale pulse and
  a rotation applied to the lips drawable. There is no 7 px pulsing dot, no dedicated 13 px
  spinner, and the 9 bars are driven by a single scalar mic level multiplied by static weights
  (`weights` array), not by band energies. The desktop reference is
  `Sokhan/src/overlay/RecordingOverlay.css:315-343` (dot, `sdot-pulse` 1.9 s),
  `:445-458` (spinner, `sspin` 0.7 s), `:378-409` (arming), and
  `RecordingOverlay.tsx:106-108,179` (FFT bins, `3 + pow(v,0.7)*15`, smoothing
  `prev*0.7 + target*0.3`).
- `RecordingService.kt:143-153` — mic level is emitted once per 100 ms read, i.e. ~10 Hz,
  versus the desktop's ~30 fps stream.

## Root Cause Hypothesis

Two independent causes, both introduced by my own earlier fixes. First, the v1.0.1
"don't record where there is no text field" requirement was implemented as an
expensive synchronous accessibility-tree scan with a strict capability predicate, and
that predicate is wrong often enough (and slow enough) that the button reads as broken —
the gate is both the latency source and the false-refusal source. Second, making Sokhan a
real IME for live typing shipped without any escape hatch back to the previous keyboard,
which on mobile is a blocker, not a polish item. The animation gap is a scope gap rather
than a regression: a scalar-level-driven fake meter and icon rotation were substituted for
the desktop's dot/waveform/spinner, and driven at 10 Hz. Confidence: high for the gate and
IME-trap findings (read directly from code); medium that they fully explain the user's
symptom, since there is no device to reproduce on.

## Proposed Remediation

**Preferred — make the tap path cheap, never blocking, and give the keyboard an exit:**

1. Remove the pre-flight `hasPasteTarget()` requirement from `SessionManager.start()`.
   Always start recording on tap. Decide *after* transcription which insertion route works,
   in this order: active IME `InputConnection` → accessibility paste → clipboard with an
   explicit message. This is also what the desktop effectively does (it pastes into whatever
   has focus and only reports a paste error afterwards).
2. If a "nowhere to type" hint is still wanted, derive it from data we already have at zero
   extra cost — `LiveTyper.imeAvailable()` or the last known focus state — and never run a
   tree walk on the tap thread.
3. Give the IME strip an escape hatch: a switch key calling
   `switchToNextInputMethod(false)` plus long-press → `showInputMethodPicker()`, and keep the
   mic key. This removes the "I can't type at all" failure mode.
4. Stop the button from moving: add the caption *below* the button row (or give the caption
   its own window), and clamp dragging with the measured window width/height instead of `size`.
5. Bring the animation to parity with the desktop: emit band energies (a cheap 9-bin
   magnitude sum over the PCM window we already have, no FFT library) at ~30 fps, add the
   real 7 px pulsing dot and the dedicated 13 px spinner as their own views instead of
   rotating the lips icon, and reuse the desktop numbers already listed above.

**Alternatives:**
- Keep the gate but cache node lookups and check only `rootInActiveWindow.findFocus()` —
  cheaper, but still an IPC on the tap thread and still misses editors that never expose
  focus via accessibility. Rejected as the primary because it keeps a false-refusal class.
- Drop the IME entirely and rely only on paste — restores the old, unreliable insertion and
  forfeits live typing. Rejected.

**Files likely to change**:
- `app/src/main/java/app/sokhan/android/SessionManager.kt`
- `app/src/main/java/app/sokhan/android/SokhanAccessibilityService.kt`
- `app/src/main/java/app/sokhan/android/SokhanInputMethod.kt`
- `app/src/main/java/app/sokhan/android/WaveView.kt`
- `app/src/main/java/app/sokhan/android/RecordingService.kt`
- `app/src/main/res/values/strings.xml`, `app/src/main/res/values-en/strings.xml`

**Tests to add or update**:
- Unit-test the 9-bin band computation as a pure function (fixed PCM input → expected band
  array) so the meter is pinned without a device.
- Unit-test the insertion-route selection order (IME → paste → clipboard) with fakes, so a
  future change cannot silently re-block the tap path.
- Extend `LiveTyperTest`-style coverage for `isFullyTyped` after a mid-session disconnect.
- Keep all existing FLAC/protocol/chunk/half-space tests green (they pin the wire format).

## Risks & Considerations

- **No device, no local SDK.** CI only compiles and runs JVM tests; every UI/IME claim here
  remains unverified on hardware. The user must retest.
- Removing the gate reopens "recorded a lot, then could not insert" — mitigated by the
  explicit clipboard fallback plus a visible message, which is strictly better than refusing
  to start.
- `switchToNextInputMethod(false)` returns false when there is no other enabled IME; the key
  must degrade to opening the picker.
- Driving the meter at 30 fps adds UI-thread work; must stay off the audio thread and reuse
  buffers (no per-frame allocation).
- The overlay is a `TYPE_ACCESSIBILITY_OVERLAY` with `FLAG_NOT_FOCUSABLE`, which is correct
  and must not change — a focusable overlay would steal focus from the user's editor.

## Open Questions

- [NEEDS CLARIFICATION: which apps/screens the user tried when the button "did not work",
  and whether Sokhan was the selected keyboard at that time?]
- [NEEDS CLARIFICATION: does the button respond at all, or is it completely inert? That
  distinguishes the slow-gate theory from the state-machine wedge.]
- [NEEDS CLARIFICATION: is the 64 dp floating button acceptable, or should the widget become
  a small persistent mic bar / notification action — the user said "not optimized for
  mobile", which may mean the whole interaction model, not just sizing?]
