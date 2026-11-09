# Esputifai — personal YouTube Music app for Android

A personal, serverless Android music player backed by YouTube Music. The phone talks to YouTube directly: search and audio resolution run on-device through [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor), playback through Media3/ExoPlayer. Personal use only; not distributed.

Working agreement and language rules: [`CLAUDE.md`](CLAUDE.md) and [`soul.md`](soul.md).

## Status (2026-11-09)

- **v0.1.0** (search → play → background playback with notification/lock-screen controls) confirmed working on the phone by the owner.
- **v0.2.0** (local playlists, bigger UI) confirmed working on the phone by the owner. 
- **v0.5.1 not yet confirmed on a device:** plays count only when a song ends, tapping the playing song doesn't restart it, long press on a song → "Ir al álbum", share playlists by link (see below).
- **v0.5.0 not yet confirmed on a device:** search autocomplete, play counter on every song, "Top" tab (see below).
- **v0.4.0 not yet confirmed on a device:** related songs, active list, album/artist search, Albumteca (see below).
- **v0.3.3** (name "Esputifai"). v0.3.0 offline playlists confirmed working in airplane mode by the owner. **v0.3.2:** standard sizes again (zoom reverted at the owner's request). v0.3.1 added the Esputifai launcher icon. v0.3.0: playlists saved on the phone (offline) or streamed; app renamed Esputifai. Downloads and offline playback can only be verified on the device (Media3's cache needs Android); the pure rules are unit-tested.
- The data path (search, URL resolution, ranged downloads) and the playlist store are verified against real YouTube data by JVM tests.

## Layout

```
CLAUDE.md                     project instructions for Claude (language rules first)
soul.md                       working agreement with the owner
docker/android-build.Dockerfile   build image: JDK 21 + Android SDK (platform 37.2, build-tools 37.0.0)
scripts/build-apk.sh          builds inside Docker; creates the signing key on first run
dist/                         last APK copied for installing (not in git)
android/                      Gradle project
  gradle/libs.versions.toml   all dependency versions
  keystore/                   release signing key + passwords (gitignored, BACK IT UP)
  app/src/main/java/dev/personal/music/
    MusicApp.kt               Application: shared OkHttpClient, NewPipe init
    data/
      Track.kt                Track (UI/queue model), ResolvedAudio (direct URL + size + expiry)
      Album.kt                Album, Artist, LibraryAlbum (Albumteca entry), album sort orders
      JsonStore.kt            generic "value persisted as one JSON file" used by all stores
      LibraryStores.kt        AlbumStore (albums.json), PlayCountStore (plays.json)
      RelatedSongs.kt         "Relacionadas": paged, de-duplicated YouTube Music radio
      Playlist.kt             local playlist model
      PlaylistStore.kt        playlists persisted as files/playlists.json (atomic writes, StateFlow)
      OkHttpDownloader.kt     NewPipeExtractor HTTP layer on OkHttp
      YouTubeMusic.kt         search (music_songs filter), audio stream pick + URL cache
      StreamRequests.kt       googlevideo request shape (POST, range=/rn= params, headers)
    playback/
      YouTubeDataSource.kt    ExoPlayer DataSource for ytm://track/<videoId>: lazy resolve + chunked reads
      PlaybackService.kt      MediaSessionService owning ExoPlayer (notification, lock screen, focus)
    offline/
      OfflineRules.kt         pure rules: which songs must be on the phone, sync plan, progress
      OfflineAudio.kt         SimpleCache + DownloadManager + network state; playback data source
      AudioDownloadService.kt Media3 DownloadService (foreground, progress notification)
    ui/
      MainActivity.kt         edge-to-edge, notification permission, sets Compose content
      MusicViewModel.kt       search, playlists actions, snackbar messages, MediaController → NowPlaying
      MusicScreen.kt          shell: bottom tabs (Buscar / Mis listas), player bar, snackbars
      SearchScreen.kt         search field, Canciones/Álbumes/Artistas selector, results, Relacionadas
      AlbumScreens.kt         Albumteca tab and the album page
      ArtistScreen.kt         artist page (songs on top, albums below)
      PlaylistScreens.kt      "Mis listas" and one playlist (play, shuffle, move, remove, rename, delete)
      AddToPlaylistSheet.kt   bottom panel to toggle a song in/out of playlists
      Components.kt           shared pieces: TrackRow, artwork, big button, dialogs
      Theme.kt                Material You colors
  app/src/test/.../YouTubeMusicSmokeTest.kt   real-network smoke test of the data path
  app/src/test/.../PlaylistStoreTest.kt       playlist store round-trip with real search results
  app/src/test/.../OfflineRulesTest.kt        saved/streaming rules (lists and albums)
  app/src/test/.../CatalogSmokeTest.kt        related songs, albums, artists against real YouTube Music
  app/src/test/.../AlbumtecaTest.kt           album order, albums/plays persistence
```

## Build, test, install

Nothing is installed on the host; everything runs in Docker. Gradle's cache lives in `~/.cache/music-android-gradle`.

```bash
scripts/build-apk.sh                     # assembleRelease → android/app/build/outputs/apk/release/app-release.apk
scripts/build-apk.sh testDebugUnitTest   # smoke test against real YouTube (needs network)
```

Install by copying the APK to the phone and opening it (sideloading; allow "install unknown apps" for the app you open it from). Bump `versionCode`/`versionName` in `android/app/build.gradle.kts` for each release.

**Signing key:** `android/keystore/` holds `release.jks` and `keystore.properties`. Updates only install over the existing app if signed with this same key. Losing it means uninstalling the app (and its local data) to install a new build.

## How it works

**Search.** `YouTubeMusic.search()` uses NewPipe's YouTube search extractor with the `music_songs` content filter (YouTube Music results, not plain videos). Results map to `Track` (videoId, title, artist = uploader, duration, thumbnail). YouTube Music thumbnails are tiny; googleusercontent URLs encode the size (`=w60-h60…`), so they are rewritten to `=w544-h544-l90-rj`.

**Playback pipeline.**

1. The UI builds the queue as `MediaItem`s whose `mediaId` is the videoId, and sends it to `PlaybackService` through a `MediaController`.
2. Media3 strips URIs from controller-sent items, so `PlaybackService.Callback.onAddMediaItems` rebuilds each URI as `ytm://track/<videoId>`.
3. ExoPlayer (`ProgressiveMediaSource`) opens that URI with `YouTubeDataSource`, which:
   - resolves videoId → googlevideo URL **lazily**, on the loader thread, only when the track actually loads (queued tracks cost nothing). `StreamInfo.getInfo` is used, and the highest-bitrate original-language progressive audio stream is picked, Opus winning ties. In practice: Opus/WebM ~160 kbps.
   - caches the URL until 10 min before its `expire=` timestamp;
   - downloads in 2 MB chunks, opening the next chunk transparently;
   - on a 403 when opening, invalidates the cache and re-resolves once.
4. Requests copy NewPipe's `YoutubeHttpDataSource`: **POST** with body `x\0`, byte range as `&range=a-b` URL parameter, request counter `&rn=N`, `Accept-Encoding: identity`. Web-client URLs get `Origin`/`Referer`/`Sec-Fetch-*` youtube.com headers. visionOS-client URLs get the visionOS UA, everything else the desktop Firefox UA. NewPipeExtractor v0.27.0 currently hands out `c=VISIONOS` URLs.

**Why this design**

- *Serverless.* The owner explicitly wants the app not to depend on a server (a FastAPI + yt-dlp backend existed briefly and was deleted, see History).
- *NewPipeExtractor* rather than a hand-written InnerTube client: YouTube changes signatures, clients and PO-token rules every few weeks, and the NewPipe team tracks that. Our job on breakage is to bump the version.
- *Progressive + custom DataSource* rather than NewPipe's progressive-DASH-manifest approach: lazy per-track resolution fits a queue naturally, and the chunking gives the same anti-throttling benefit. Revisit if seeking or throttling misbehave.
- *No R8/minify* yet: NewPipeExtractor (Rhino, jsoup…) needs keep rules. The APK is ~17 MB. Fine for now.

**Versions** (`gradle/libs.versions.toml`) mirror NewPipe's own `dev` build of Nov 2026 so they are known to compile together: AGP 9.5.0 (built-in Kotlin, so no `kotlin-android` plugin), Kotlin 2.4.21 (compose plugin), Gradle 9.8.2, JDK 21 toolchain. compileSdk **37.2** (late-2026 AndroidX/OkHttp require it), targetSdk 36, minSdk 26. Media3 1.11.1, Compose BOM 2026.09.00, Coil 3.6.3, OkHttp 5.5.1, desugar_jdk_libs_nio 2.1.5.

AGP 9 notes: unit tests exist only for the debug variant (`testDebugUnitTest`). JVM tests set `unitTests.isReturnDefaultValues = true` because OkHttp's Android artifact calls `android.util.Log`.

**Playlists (local).** Stored only on the phone in `files/playlists.json` (`{"version":1,"playlists":[{id,name,createdAtMs,tracks:[Track…]}]}`), not synced with any YouTube account (owner's choice). A JSON file instead of Room: few lists, always read whole, no build plugins. Every change is written (temp file + rename) before the UI state updates. An unreadable file is renamed to `playlists.json.unreadable-<ms>`, never overwritten. A song appears at most once per list. Uninstalling the app deletes the playlists; the signing key matters for this too.

**Saved playlists (offline).** Decided with the owner (2026-11-07): the choice is per playlist only, never per song. Each list is "En streaming" or "Guardada en el móvil", chosen in the "Nueva lista" dialog and changeable later from the list's "⋮" menu (switching to streaming asks for confirmation). Rules (`OfflineRules.kt`): a song is on the phone iff it is in at least one saved list; adding a song to a saved list downloads it; removing it, switching the list to streaming or deleting the list frees only the songs no other saved list needs. Downloads use any network (Wi-Fi or mobile data, owner's choice; ~4 MB per song).

How: `OfflineAudio` owns a `SimpleCache` (`files/offline-audio`, no eviction) and a Media3 `DownloadManager` whose upstream is `YouTubeDataSource`; cache key = videoId. `MusicApp` re-syncs the manager with the playlists on every change. The player reads through a read-only `CacheDataSource`: a fully cached song is read from disk and the upstream (URL resolution, network) is never opened (checked in Media3 1.11.1 source, `CacheDataSource.openNextSource`); plain streaming never writes to the cache. `AudioDownloadService` has no scheduler: on Android 12+ Media3 doesn't use one, so `OfflineAudio` restarts the service when the app comes to the foreground or the network returns while it is open. Failed downloads are retried only from the "Reintentar" button. `PlaybackService` skips songs that fail to play (e.g. offline and not saved), at most once around the queue.

**Search, related songs, albums, artists (v0.4.0).** The search has a Canciones / Álbumes / Artistas selector (remembered). Song results are followed by "Relacionadas": the first result's YouTube Music radio (`RDAMVM<videoId>`), 10 at a time with "Más relacionadas", loaded after the results so they never wait for it. The radio's pages overlap and later bring video versions of songs already given, so `RelatedSongs` de-duplicates by videoId and by a normalized title (`titleKey`) and skips the search results. Artist pages: NewPipe gives no usable tabs for YouTube Music artist channels ("<name> - Topic", zero tabs; checked 2026-11-07), so `artistSongs`/`artistAlbums` search by the artist's name and keep only results whose uploader URL is the artist's channel id (drops namesakes). One page, owner's choice: 5 popular songs ("Ver todas") then albums.

**Active list.** "+" next to any song (results, related, album, artist, player) adds it with one tap to the active list, with "Deshacer". Long press opens the panel to pick lists. The active list shows in an "Añadir a: [lista ▾]" bar above song lists, where it is changed or a new one created. The first list created or chosen becomes active automatically. Stored in SharedPreferences (`ui`).

**Albumteca.** Albums are added from album search results, the album page or an artist page; their tracks are stored at that moment (`albums.json`) so they can be saved offline and counted without YouTube. Streaming by default; "⋮ → Guardar en el móvil" downloads them under the same rules as saved playlists (`wantedOnPhone` takes lists and albums). Order: "Más escuchados" (plays of the album's songs from anywhere, counted by `PlaybackService` on each song start, so also with the app closed; owner's decision), A-Z, Z-A (Spanish collation, accents ignored).

**Play history and Top (v0.5.0).** `PlaybackService` counts a play when a song plays to its end (since v0.5.1, owner's rule: starting and skipping a song is not listening to it; AUTO/REPEAT transitions and STATE_ENDED count, SEEK/PLAYLIST_CHANGED don't) into `plays.json` (v2: `{"songs":{"<videoId>":{count,lastPlayedMs,track}},"albums":{"<id>":{album,trackIds}}}`); the song's details are stored with its count so the Top can show songs that are in no list. v1 files (counts only) are read as songs without details; the Top fills those from the playlists and Albumteca, or skips them. Every song row shows "▶ n" when played at least once (`LocalPlays`). The "Top" tab ranks (`Top.kt`, max 100 rows):
- songs by plays, ties to the most recently played;
- artists by the sum of their songs' plays, matched by name ignoring case and accents (songs carry no channel id; opening one searches the artist by name);
- albums by the plays of their songs (as "Más escuchados"), among Albumteca albums and albums played from their page (remembered on play).

**Autocomplete.** NewPipe's YouTube suggestion extractor (Google's suggest endpoint, ~100 ms), 250 ms debounce, terms that are never music ("reaction", "karaoke", "tutorial"…) dropped. Suggestions are plain queries, so they work for songs, albums and artists alike. While the box has focus they replace the results; the arrow fills the box without searching.

**Debug build.** `assembleDebug` installs next to the real app (`dev.personal.music.probe`, "Esputifai prueba") and carries throwaway probes in `src/debug` (currently: sync through a Google Drive file opened via the system picker).

**Tapping the playing song** does nothing to it (owner's rule): `play()` keeps it going and only rebuilds the queue around it (remove + add, so the session's `onAddMediaItems` restores URIs).

**Song menu, "Ir al álbum" (v0.5.1).** A long press on any song row (`TrackRow`, via `LocalSongMenu`) or on the player opens a dialog with "Ir al álbum". Songs carry no album, so `albumOf` checks the Albumteca and albums already played, then `YouTubeMusic.findAlbum`: album search for "artist title", the first of 4 candidates that contains the videoId, else the first with a song of the same loose title (remasters have other ids). Checked on real data: 6/6 found, 0.7–1.7 s.

**Sharing playlists (v0.5.1).** "⋮ → Compartir" sends a text with a link `https://david-navarro.github.io/esputifai/l/#<data>` where `<data>` is the whole list (base64url of zlib-deflated JSON, no thumbnails; ~35 chars per song). Nothing is stored anywhere: the part after `#` never reaches the server. GitHub Pages (branch `main`, folder `docs/`) serves a static page that decodes the list in the browser, shows it, and opens the app with `esputifai://import?d=<data>`; the app asks "¿Importar?" and creates a streaming list (name suffixed " (2)" if taken). An intermediate page is needed because WhatsApp only links `https://`, and the `https` link can't be an auto-verified App Link (Pages can't serve `/.well-known/assetlinks.json` at the domain root for a project site); the manifest still declares it, so the user can enable it in Settings → Apps → Esputifai → Open by default and skip the page. Forks must change `PlaylistShare.PAGE` and the manifest host.

**Navigation.** Four tabs; each keeps its own stack of pushed screens (playlist, album, artist). Back pops; tapping the current tab returns to its root.

**Sizes.** Standard Material 3 sizes (as in v0.1.0). v0.2.0–v0.3.1 had an app-wide zoom (`UI_SCALE`/`TEXT_SCALE` in `Theme.kt`) and oversized components for readability; the owner found it too big and it was reverted in v0.3.2. Whoever needs bigger text raises the phone's font size, which the app follows. Reordering inside a list uses "Subir/Bajar" menu items; removing a song can be undone from the snackbar; deleting a list asks for confirmation.

## Maintenance: when YouTube breaks playback or search

1. Run the smoke test: `scripts/build-apk.sh testDebugUnitTest`. It prints search results, the chosen stream (mime, bitrate, client) and the HTTP status of three byte ranges.
2. Check NewPipeExtractor releases (`https://api.github.com/repos/TeamNewPipe/NewPipeExtractor/releases`) and bump `newpipe-extractor` in `libs.versions.toml`. If JitPack lost the artifact, use the commit hash instead of the tag (NewPipe's own workaround).
3. If NewPipe changed how it requests streams, compare `StreamRequests.kt` with NewPipe's `app/src/main/java/org/schabi/newpipe/player/datasource/YoutubeHttpDataSource.java` (`makeConnection`), and the UA in `OkHttpDownloader.USER_AGENT` with NewPipe's `DownloaderImpl.USER_AGENT`.
4. If dependencies demand a newer compileSdk, check available platforms in `https://dl.google.com/android/repository/repository2-3.xml` and update both the Dockerfile and `compileSdk`.

## Pinned versions and supply chain

Every input of the build is pinned, so the same commit always builds from the same bits:

- **Direct dependencies and plugins:** exact versions in `android/gradle/libs.versions.toml`. NewPipeExtractor is pinned to the **commit** of its release tag (JitPack tags can be moved); its nanojson fork already comes by commit.
- **Every artifact, transitive ones and plugins included:** SHA-256 in `android/gradle/verification-metadata.xml` (Gradle dependency verification). A changed version or a tampered file fails the build with "Dependency verification failed".
- **JitPack** may only serve `com.github.TeamNewPipe` artifacts, and those only come from there (`exclusiveContent` in `settings.gradle.kts`).
- **Gradle** distribution checked by `distributionSha256Sum` in `gradle-wrapper.properties`; `gradle-wrapper.jar` matches the official one for 9.8.2.
- **Build image:** base image by digest, SDK command line tools by SHA-256, SDK packages by version (`docker/android-build.Dockerfile`). `android.builder.sdkDownload=false` stops AGP from downloading SDK components on its own (it used to fetch build-tools 36.0.0 silently; now `buildToolsVersion` names the installed 37.0.0).

**Updating a dependency:** change its version in `libs.versions.toml`, then regenerate the checksums and review the diff of `verification-metadata.xml` before committing:

```bash
scripts/build-apk.sh --write-verification-metadata sha256 assembleRelease testDebugUnitTest
```

**Known vulnerabilities:** only critical CVEs are patched (owner's policy), checked against [OSV](https://osv.dev) for the APK's and the build's dependencies. As of 2026-11-09 the only critical one was CVE-2026-8763 in Bouncy Castle < 1.85, which AGP uses for signing (build only, not in the APK); `android/build.gradle.kts` forces 1.85. Drop that override once AGP ships a fixed version.

## Roadmap

Proposed order, agreed in principle with the owner (exact scope of each step to confirm when starting it):

1. **Full-screen player:** large artwork, seek bar, queue view (reorder/remove). Today there is only the mini player.
2. **Autoplay radio:** when the queue ends, continue with related tracks (NewPipe `StreamInfo.relatedItems`, or YouTube Music's "up next" / radio playlist `RDAMVM<videoId>`).
3. **Albums and artists:** search tabs for albums and artists; album and artist pages (NewPipe `music_albums` / `music_artists` filters, playlist and channel extractors).
4. ~~Local playlists~~ done in v0.2.0. Still open: listening history, and backup/export of `playlists.json` (e.g. share as a file), since uninstalling loses it.
5. ~~Offline downloads~~ done in v0.3.0 (per playlist).
6. **Google account sign-in:** the user's YouTube Music library, playlists and likes. The most fragile piece: cookie or OAuth handling, and it needs maintenance when Google changes things.

Smaller items to fold in along the way:

- Error handling UX: auto-skip unplayable tracks, retry button, offline message.
- `R8` with NewPipe's keep rules to shrink the APK.

## Contributing

Improvements are welcome: bug fixes, YouTube breakages, UI polish, new features from the roadmap.

1. **Open an issue first** for anything bigger than a small fix, so we can agree on the approach before you spend time on it.
2. **Fork** the repo and create a branch from `main` (`fix/related-duplicates`, `feature/sleep-timer`…).
3. **Build and test in Docker**, no local Android SDK needed:
   ```bash
   scripts/build-apk.sh testDebugUnitTest   # unit tests + smoke tests against real YouTube (needs network)
   scripts/build-apk.sh                     # release APK (creates your own signing key on first run)
   ```
4. **Open a pull request** describing what changes and why, and how you checked it: which tests pass and what you tried on a real phone.

There is no CI: pull requests are not built or tested automatically, so run the tests locally before opening one. The smoke tests talk to the real YouTube, which is also why they don't fit an automated pipeline: they break when YouTube changes, not when the code does.

Guidelines:

- Keep the app **serverless**: everything runs on the phone.
- Code, comments and docs in **English**; the app's UI is in **Spanish**.
- Prefer tests with **real data** (like the existing smoke tests) over mocks.
- Explain non-obvious decisions in a short comment, and update this README when behavior or design changes.
- Small, focused PRs are easier to review than big ones.

This is a personal project maintained in spare time, so reviews may take a while and not every proposal will be merged. Thanks for helping!

## License

Copyright (C) 2026 david-navarro

Esputifai is free software: you can redistribute it and/or modify it under the terms of the [GNU General Public License v3.0](LICENSE) (GPL-3.0). It comes with no warranty.

GPL-3.0 because the app is built on [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor), which is GPL-3.0 too: anyone who distributes the APK must offer the source under the same license. By opening a pull request you agree that your contribution is licensed under GPL-3.0.

This is an independent personal project, not affiliated with YouTube, Google or Spotify.

## History

- **2026-11-07:** Started as a FastAPI backend (ytmusicapi search + yt-dlp ranged audio proxy, Subsonic API layer for Symfonium/Tempo). The owner found the server + third-party client setup confusing and wanted a self-contained app, so the backend was stopped and deleted the same day. Lessons kept: YouTube stream URLs from yt-dlp's TV client return 403 until their `available_at` time, and googlevideo URLs are IP-locked, so on-device resolution avoids both issues.
- **2026-11-07:** Android app v0.1.0 (this repo).
- **2026-11-09:** v0.5.1: plays counted on song end, playing song not restarted on tap, "Ir al álbum" on long press, playlist sharing via GitHub Pages link.
- **2026-11-09:** v0.5.0: search autocomplete, play counter on songs, "Top" tab (songs, artists, albums); play history stores song details (`plays.json` v2).
- **2026-11-09:** Supply chain hardening: Gradle dependency verification (SHA-256 for all artifacts), NewPipeExtractor pinned by commit, JitPack restricted to NewPipe, Gradle and build image pinned by checksum/digest, no SDK auto-download, Bouncy Castle 1.85 in the build classpath (critical CVE-2026-8763).
- **2026-11-07:** v0.4.0: related songs under song results, active list for one-tap "+", album and artist search, artist page, Albumteca (save offline, sort by plays/A-Z/Z-A). Persistence refactored into `JsonStore`.
- **2026-11-07:** v0.3.3: app name written "Esputifai" (not all caps).
- **2026-11-07:** v0.3.2: back to standard (v0.1.0) sizes: app-wide zoom removed, one-row player, default buttons, icons and text styles. Features unchanged.
- **2026-11-07:** v0.3.1: Esputifai adaptive icon from `design/esputifai-logo.svg` (designed by the owner): gradient background, flame + waves foreground scaled so the logo circle equals the visible 72dp icon circle, flame-only monochrome layer.
- **2026-11-07:** v0.3.0: playlists saved on the phone or streamed; auto-skip of unplayable songs; renamed Esputifai.
- **2026-11-07:** v0.2.0: local playlists (create, rename, delete, add from search or player, move, remove with undo, play/shuffle) and a bigger UI.

