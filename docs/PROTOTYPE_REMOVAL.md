# Prototype removal

The v0.x implementation is retained in Git history and the `simple-text-tts` branch only.

Story Reader v1 replaces the old runtime components as follows:

- `MainActivity` -> `V1ReaderActivity` (thin MediaController UI)
- `PlaybackController` -> `StoryPlaybackService` + core state/coordinator
- `EdgeTtsClient` -> `core.tts.edge.EdgeTtsEngine`
- `EpubParser` -> `core.epub.EpubBookParser` + file-backed archive
- `BackgroundSoundEngine` -> `AmbientPlayer` using user-selected real audio loops

No v0.x runtime component should be reintroduced into the v1 launcher path.
