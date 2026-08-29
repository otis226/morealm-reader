# Story Reader v1.0 — Engineering Plan

Status: **active rebuild** on branch `story-reader-v1`.

The v0.x branch is a prototype/reference only. v1 is not considered done because an APK builds; every gate below must pass.

## Product goal

Paste text or import EPUB, press Play, and listen continuously with Edge Vietnamese TTS while the reader tracks the spoken text. Playback must survive screen-off and Activity recreation and behave like an audiobook player.

## v1 scope (locked)

- Paste / append text from clipboard.
- EPUB import using package spine and real chapters.
- Chapter table of contents and previous/next chapter.
- Edge voices: `vi-VN-HoaiMyNeural`, `vi-VN-NamMinhNeural`.
- Speed and pitch controls.
- Word/sentence tracking and tap sentence to read from there.
- Seek within current chapter; previous/next sentence.
- Clean reading view with tap-to-show controls.
- Persist book/chapter/offset and resume after app restart.
- Media3 background playback with MediaSessionService, notification and lock-screen controls.
- Audio focus/headset/call-safe behavior.
- Sleep timer.
- Optional ambience/custom local audio, isolated from narration.

### Explicitly out of scope for v1

Cloud accounts/sync, PDF, store/catalog, AI scene selection, DRM EPUB, social features.

## Architecture

```text
Text / EPUB
    |
Book -> Chapter[]
    |
PlaybackCoordinator ---------------- PositionStore
    |         |              |
    |         |              +-- ReaderState / word boundaries
    |         +-- TtsEngine
    |               +-- EdgeTtsEngine
    |               +-- FakeTtsEngine (tests)
    |
Media3 ExoPlayer -> MediaSession -> MediaSessionService
    |
Android audio / notification / lock screen
```

Rules:

1. Activity/UI never owns narration playback.
2. EPUB is chapter-native; never create one giant Spannable for the whole book.
3. TTS work is generation/cancellation aware; stale synth jobs must never play after a seek.
4. Edge protocol is behind `TtsEngine`; replacing it must not change reader/player state logic.
5. Ambience is a separate concern and may never block or restart narration.

## Gate 1 — deterministic core

Required:

- [x] Branch isolated from prototype.
- [ ] `Book` / `Chapter` domain model.
- [ ] `ReadingPosition` normalization.
- [ ] Pure sentence navigation.
- [ ] Pure playback state machine.
- [ ] `TtsEngine` interface independent of Edge/network.
- [ ] Fake TTS available to tests.
- [ ] Unit tests run in CI before APK build.

Exit criteria: all pure core tests pass consistently.

## Gate 2 — EPUB corpus

Build chapter-native EPUB parser/repository.

Tests must cover:

- [ ] one chapter;
- [ ] 100 chapters;
- [ ] cover + nav document;
- [ ] duplicate/garbage TOC entries;
- [ ] Vietnamese titles/diacritics;
- [ ] long chapter (100k+ chars);
- [ ] correct spine order;
- [ ] chapter title fallback;
- [ ] malformed EPUB produces a useful error, not crash.

Exit criteria: fixed test corpus passes and no full-book UI allocation is required.

## Gate 3 — TTS pipeline

Implement `EdgeTtsEngine` and bounded audio cache.

Required behavior:

- [ ] Hoài My live synthesis.
- [ ] Nam Minh live synthesis.
- [ ] word boundary metadata mapping.
- [ ] synth N+1/N+2 while N plays.
- [ ] cancellation on seek/chapter change.
- [ ] retries with bounded backoff.
- [ ] stale generation audio cannot play.
- [ ] network error surfaces clearly and can recover.
- [ ] pitch and speed included in cache key.

Stress regression: seek 10% -> 80% -> 20%; only the final generation may play.

## Gate 4 — audiobook player UI

Clean view is the default. Tap center to reveal controls.

```text
Chapter 4/18 · 37%
---------●--------------

  prev sentence   -   play/pause   +   next sentence

< chapter       TOC        chapter >
```

Required:

- [ ] seek within chapter;
- [ ] tap a sentence to jump;
- [ ] previous/next sentence;
- [ ] previous/next chapter;
- [ ] TOC bottom sheet;
- [ ] speed +/- 0.05x;
- [ ] controls auto-hide;
- [ ] reader only renders a bounded window around current position.

## Gate 5 — Android reliability

Use AndroidX Media3 (`ExoPlayer`, `MediaSession`, `MediaSessionService`). Player/session live in service scope, not Activity scope.

Required:

- [ ] screen off keeps playback running;
- [ ] Activity recreation does not interrupt narration;
- [ ] notification controls;
- [ ] lock-screen controls;
- [ ] audio focus behavior;
- [ ] headset disconnect pauses safely;
- [ ] resume persisted position after process/app restart;
- [ ] sleep timer;
- [ ] chapter auto-advance while backgrounded.

## Performance gate

Synthetic stress corpus:

- 100 chapters;
- 100k chars in a chapter;
- repeated chapter switching;
- repeated seeks;
- fake playback for 30 minutes.

Must demonstrate:

- [ ] bounded memory;
- [ ] bounded TTS/cache queues;
- [ ] no stale playback;
- [ ] no giant Spannable/full-book render;
- [ ] no UI-thread EPUB parsing;
- [ ] no progressive slowdown during tracking.

## CI / release policy

A release candidate requires:

```text
compile
  -> unit tests
  -> EPUB regression
  -> playback/state tests
  -> Android/emulator smoke tests
  -> performance sanity
  -> assemble APK
```

Terminology:

- **CI PASS**: automated gates above are green.
- **DEVICE PASS**: real Android smoke test confirms Edge networking, actual audio quality, screen-off/background behavior and UX.
- **RELEASE**: CI PASS + DEVICE PASS. Build success alone is never called tested/released.

When a device bug is found, add a regression test first when technically possible, then fix it.

## Definition of Done — v1.0

- [ ] Gate 1 complete.
- [ ] Gate 2 complete.
- [ ] Gate 3 complete.
- [ ] Gate 4 complete.
- [ ] Gate 5 complete.
- [ ] 30-minute stress test passes.
- [ ] Hoài My and Nam Minh live checks pass.
- [ ] Large EPUB device smoke test passes.
- [ ] Position survives restart.
- [ ] Screen-off/background playback device test passes.
- [ ] Signed/reproducible release APK produced.
