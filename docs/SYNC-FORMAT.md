# Sync format

The format itself is documented once, in the Android app's repository, because both
implementations have to follow the same words:

<https://github.com/sharap/auraai/blob/main/docs/SYNC-FORMAT.md>

Two things in this repository are bound by it:

- `composeApp/src/commonMain/kotlin/music/ai/recommend/ai/Excerpt.kt` is a copy of the Android
  app's file of the same name. The two must stay identical, line for line, apart from the sentence
  that says which side the copy is on. Diff them when either changes — an embedding is only worth
  carrying between devices if both devices embedded the same samples, and this file is what decides
  which samples those are.
- `ANALYSIS_VERSION` in `composeApp/src/desktopMain/kotlin/music/ai/recommend/db/AppDatabase.kt`
  must match the Android app's. A vector may only be accepted from another device when the analysis
  version, the model and the excerpt all agree.

Note that this repository's `desktopTest` source set cannot be compiled without network access to
Maven Central, so the tests covering the excerpt rule live on the Android side, where they run.
