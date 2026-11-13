# AGENTS.md: Esputifai, a reference for AI coding agents

This file is for AI agents (and humans new to the code) who need to change Esputifai without
prior context. It describes what exists, how the pieces connect, the rules the code relies on,
and how to make common changes safely. `README.md` is the human-facing overview: it holds the
version status, the history and the reasons behind decisions. When the two disagree, the code
wins. Fix whichever document is wrong.

Last full review: 2026-11-13, v0.8.0 (versionCode 12). v0.8.1 (13) added the version line in the sync dialog.

---

## 1. What the app is

Esputifai is a personal Android music player backed by YouTube Music, with **no server**. The
phone does everything itself:

- **Catalog** (search, albums, artists, public playlists, related songs, suggestions):
  [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (Java, GPL-3.0) on
  OkHttp.
- **Audio:** NewPipe resolves a YouTube videoId to a direct `googlevideo.com` URL, and Media3
  ExoPlayer plays it through a custom `DataSource`.
- **Library** (local playlists, the "Albumteca" album collection, play history): JSON files in
  the app's private storage.
- **Offline:** whole playlists or albums can be "saved on the phone" with Media3's
  `DownloadManager` and `SimpleCache`.
- **Device sync:** optional. The library is kept the same across one person's devices through the
  hidden `appDataFolder` of their Google Drive (REST API, scope `drive.appdata`).
- **Sharing:** a playlist travels as a link whose fragment contains the whole list. A static
  GitHub Pages page (`docs/l/`) opens the app with it.

Stack: Kotlin, Jetpack Compose (Material 3), Media3 1.11.1, NewPipeExtractor v0.27.0 (pinned by
commit), OkHttp 5, Coil 3, Play services auth. minSdk 26, targetSdk 36, compileSdk 37.2. The
package is `dev.personal.music`.

### Hard rules (do not break them without the owner's explicit agreement)

1. **Serverless.** Nothing may depend on a server the project runs. Third-party endpoints the
   phone calls directly are fine: YouTube, Google Drive, and GitHub Pages for static files.
2. **Language.** Code, comments, docs, commit messages and file names are in **American English**.
   Every user-visible string is in **Spanish (Spain)** and written inline in Kotlin. Only the app
   name and the download channel live in `res/values/strings.xml`.
3. **Tests with real data** beat mocks. Smoke tests hit the real YouTube. There is **no CI**:
   run the tests locally before you call something done.
4. **The repo is public.** Never commit personal data, device names, network or Tailscale
   details, keys or passwords. `android/keystore/`, `.env`, `CLAUDE.md` and `soul.md` are
   gitignored on purpose.
5. **Owner's product decisions** are listed in §10. Treat them as requirements, not suggestions.
6. **Nothing is installed on the host.** Builds run in Docker (`scripts/build-apk.sh`).

---

## 2. Build, test, run

```bash
scripts/build-apk.sh                       # assembleRelease → android/app/build/outputs/apk/release/app-release.apk
scripts/build-apk.sh testDebugUnitTest     # all JVM tests (several need network: real YouTube)
scripts/build-apk.sh testDebugUnitTest --tests 'dev.personal.music.data.CatalogSmokeTest'
scripts/build-apk.sh assembleDebug         # debug app "Esputifai prueba" (dev.personal.music.probe), installs next to the real one
scripts/build-apk.sh --write-verification-metadata sha256 assembleRelease testDebugUnitTest   # after changing a dependency
```

- The script builds the `music-android-build` image from `docker/android-build.Dockerfile` (JDK 21
  plus Android SDK, everything pinned). Gradle's cache lives in
  `~/.cache/music-android-gradle`.
- On the first run the script creates the release signing key in `android/keystore/`
  (gitignored). **Updates install over the existing app only when signed with the same key.**
  Losing the key means uninstalling the app, which deletes the user's local data. The debug
  build reuses this key.
- AGP 9 only creates unit tests for the debug variant, so the task is `testDebugUnitTest`.
- **Release checklist:**
  1. Bump `versionCode` (+1) and `versionName` in `android/app/build.gradle.kts`.
  2. Update `README.md`: Status, Layout, the feature section and History.
  3. Run the tests and `assembleRelease`.
  4. The owner installs the APK on their phone. Delivery is done outside the repo, so don't
     document it here.
- Warnings that `Locale(String, String)` is deprecated (in `Album.kt`, `Top.kt` and
  `SyncDialog.kt`) are known and harmless.

---

## 3. Architecture

```
                         ┌──────────────────────── process ────────────────────────┐
 MainActivity ──────────▶│ MusicApp (Application): process-wide singletons          │
  (Compose: MusicScreen) │   httpClient (OkHttp) · playlists · albums · plays       │
        │                │   offline (OfflineAudio) · sync (DeviceSync) · appScope  │
        ▼                └──────────────────────────────────────────────────────────┘
 MusicViewModel ── search/catalog ──▶ YouTubeMusic (object, blocking, Dispatchers.IO) ─▶ NewPipeExtractor ─▶ YouTube
        │  ── library edits ───────▶ PlaylistStore / AlbumStore / PlayCountStore (JsonStore files)
        │  ── MediaController ─────▶ PlaybackService (MediaSessionService)
        │                                 └─ ExoPlayer ─ ProgressiveMediaSource
        │                                       └─ CacheDataSource (read-only, files/offline-audio)
        │                                             └─ YouTubeDataSource  ytm://track/<videoId>
        │                                                   └─ YouTubeMusic.resolveAudio ─▶ googlevideo (POST, chunked)
        └─ observes ◀── OfflineAudio.audio/online · DeviceSync.status · stores' StateFlows

 MusicApp.onCreate: (playlists + albums) ─combine─▶ wantedOnPhone() ─▶ OfflineAudio.sync() ─▶ DownloadManager
 DeviceSync: store changes (debounced 5 s) / foreground poll (60 s) ─▶ DriveAppData (Drive REST) ─▶ SyncMerge
```

Some things are deliberately missing: dependency injection (`MusicApp` holds the singletons and
the ViewModel casts `application`), a navigation library (navigation is plain Compose state), a
database (JSON files) and a backend.

---

## 4. Source map

All paths below are under `android/app/src/main/java/dev/personal/music/`.

### Root
| File | Responsibility |
|---|---|
| `MusicApp.kt` | `Application`. It owns the shared `OkHttpClient`, the three stores, `OfflineAudio` (created in `onCreate` on the main thread, as Media3 requires), `DeviceSync` and `appScope` (`MainScope`, which lives as long as the process). It initializes NewPipe and, whenever playlists or albums change, re-syncs the songs on the phone (`wantedOnPhone` → `offline.sync`). It also starts sync. |

### `data/`: models, persistence, YouTube access (pure JVM except where noted; unit-testable)
| File | Responsibility |
|---|---|
| `Track.kt` | `Track(videoId, title, artist, durationSec, thumbnailUrl)`: the universal song model. `ResolvedAudio` is a direct stream URL with its size and expiry. |
| `Album.kt` | `Album` (id = `OLAK5uy_…` playlist id), `OnlinePlaylist` (a public YT Music playlist), `Artist` (channelId), `LibraryAlbum` (an Albumteca entry: album, tracks, addedAtMs, offline), `AlbumLibrary` (albums plus deletion markers), `AlbumOrder`, `sortAlbums`, `playsOf`. |
| `Playlist.kt` | `Playlist` (a local list: id UUID, name, tracks, createdAtMs, offline, updatedAtMs) and `PlaylistLibrary` (lists plus deletion markers). |
| `JsonStore.kt` | Generic "one value persisted as one JSON file". It also holds the `Track`↔JSON helpers and `JSONArray.mapObjects`. See §5. |
| `PlaylistStore.kt` | `playlists.json`: create, rename, delete, add/remove/move a track, setOffline, merge. Also has the `Map<String,Long>`↔JSON helpers (`toJson`, `toTimes`). |
| `LibraryStores.kt` | `AlbumStore` (`albums.json`): add, remove, setOffline, merge. |
| `PlayHistory.kt` | `PlayCountStore`: `plays.json` (this device) plus `plays-others.json` (the sum of the other devices). `history` = both added up. It also has the `Album`↔JSON helpers. |
| `SyncMerge.kt` | Pure merge rules for device sync (§7.7). |
| `Top.kt` | The rankings of the Top tab: songs, artists (grouped by name ignoring case and accents), albums. |
| `RelatedSongs.kt` | "Relacionadas": the YT Music radio `RDAMVM<id>`, paged 10 at a time and de-duplicated by videoId and `titleKey`. Stateful and blocking. |
| `YouTubeMusic.kt` | **Every call to YouTube.** `search`, `searchAlbums`, `searchArtists`, `searchPlaylists`, `suggestions`, `albumTracks`, `playlistTracks` (capped at `PLAYLIST_MAX_SONGS` = 200), `artistSongs`/`artistAlbums`, `findAlbum`, `resolveAudio` (with a URL cache), `invalidate`. All of them block. |
| `OkHttpDownloader.kt` | NewPipe's `Downloader` on OkHttp. `USER_AGENT` = desktop Firefox; HTTP 429 → `ReCaptchaException`. |
| `StreamRequests.kt` | The shape of googlevideo requests: POST body `x\0`, `&range=a-b&rn=N`, `Accept-Encoding: identity`, web/visionOS headers. `CHUNK_SIZE` = 2 MB. |
| `PlaylistShare.kt` | Playlist ↔ link: base64url(zlib(JSON)) in the URL fragment. `PAGE` = the GitHub Pages URL. |

### `playback/`
| File | Responsibility |
|---|---|
| `PlaybackService.kt` | A `MediaSessionService` that owns ExoPlayer: notification, lock screen, audio focus, pause when headphones are unplugged. It contains `SkipUnplayable` (skips a failing song, at most once per queue item), `CountPlays` (counts a play when a song **ends**) and `Callback.onAddMediaItems` (rebuilds the `ytm://` URIs that Media3 strips). |
| `YouTubeDataSource.kt` | An ExoPlayer `DataSource` for `ytm://track/<videoId>`. It resolves the URL lazily on the loader thread, reads in 2 MB chunks, reopens on early EOF, and re-resolves once after an HTTP 403. |

### `offline/`
| File | Responsibility |
|---|---|
| `OfflineRules.kt` | Pure rules: `wantedOnPhone`, `planSync`, `progressOf`, `SaveState`, `SavedAudio`, `formatBytes`. |
| `OfflineAudio.kt` | `SimpleCache` (`files/offline-audio`, no eviction), `DownloadManager` (2 parallel downloads, any network), the network-state flow, and `playbackDataSourceFactory()`, a read-only `CacheDataSource`. Cache key = videoId. |
| `AudioDownloadService.kt` | Media3 `DownloadService` (foreground, `dataSync` type, progress notification, no scheduler). |

### `sync/`
| File | Responsibility |
|---|---|
| `DriveAppData.kt` | Google authorization (`Identity.getAuthorizationClient`, scope `drive.appdata`) plus Drive v3 REST calls: list, download, create, update. No client id appears in the code: Play services identifies the app by package name and signing certificate. |
| `DeviceSync.kt` | When to sync, this device's file `device-<uuid>.json`, the receive → merge → send round, and `SyncStatus` for the UI. |

### `ui/`
| File | Responsibility |
|---|---|
| `MainActivity.kt` | Edge-to-edge layout, the notification permission, links (`ACTION_VIEW` → `receiveShared`), and the foreground flags for `OfflineAudio` and `DeviceSync` (`onStart`/`onStop`). `singleTop`. |
| `MusicViewModel.kt` | The only ViewModel. It handles search and its kinds, suggestions, related songs, album/artist/public-playlist loading (with caches), the active list, playlist and Albumteca actions, sharing and import, sync actions, the MediaController (play, queue, seek) and snackbar messages. It also defines the UI state types (`SearchKind`, `SearchState`, `NowPlaying`, `QueueState`, `TopLists`, `UiMessage`…). |
| `MusicScreen.kt` | The app shell: 4 tabs, a per-tab route stack (`Route`), the player bar, snackbars, and the dialogs and sheets (import, song menu, add-to-playlist, sync, storage, now playing). Every screen is wired here. |
| `SearchScreen.kt` | The search field, suggestions, the segmented selector Canciones/Álbumes/Artistas/Listas, and the results. Also `SongActions`, `songItems()` (a reusable list of song rows) and `playlistSubtitle()`. |
| `OnlinePlaylistScreen.kt` | The page of a public YT Music playlist: play, shuffle, "+", and "Guardar en Mis listas". |
| `AlbumScreens.kt` | The Albumteca tab and the album page. |
| `ArtistScreen.kt` | The artist page: 5 songs ("Ver todas"), then albums. |
| `PlaylistScreens.kt` | "Mis listas", one local playlist (menu: save on the phone / streaming, rename, share, delete; per song: up, down, remove), `StorageDialog`, and the `StatusLine`/`SaveIndicator` helpers. |
| `TopScreen.kt` | The Top tab (songs, artists or albums). |
| `NowPlayingSheet.kt` | The "Reproduciendo" sheet: seek bar and queue. |
| `AddToPlaylistSheet.kt` | Puts one song in or out of several lists, and creates a list with it. |
| `SongMenu.kt` | The long-press song menu ("Ir al álbum"). |
| `SyncDialog.kt` | The cloud button and the sync dialog. Its foot shows "Esputifai <versionName>" (read from `PackageManager`). There is no settings or "about" screen, so this is where the version lives. |
| `Components.kt` | Shared pieces: `TrackRow`, `PlayCount`, `Artwork`, `PlaylistCover`, `BigButton`, `EmptyMessage`, dialogs, `AddToPlaylistButton`, `ActiveListBar`, `CoverRow`/`AlbumRow`, `AddToAlbumtecaButton`, `ArtistRow`, `SectionHeader`, `BackBar`, `songCount`, `formatDuration`. Also the CompositionLocals `LocalPlays` and `LocalSongMenu`. |
| `Theme.kt` | Material You dynamic colors with standard sizes. |

### Outside the Kotlin sources
| Path | What |
|---|---|
| `android/app/src/main/AndroidManifest.xml` | Permissions, intent filters (`esputifai://import`, and `https://david-navarro.github.io/esputifai/l*`, which is not auto-verified), both services. |
| `android/app/proguard-rules.pro` | R8 keep rules: Rhino, NewPipe's timeago patterns, protobuf-lite fields. No obfuscation. |
| `android/gradle/libs.versions.toml` | Every version. |
| `android/gradle/verification-metadata.xml` | SHA-256 of every artifact (Gradle dependency verification). |
| `android/settings.gradle.kts` | Repositories. JitPack is restricted to `com.github.TeamNewPipe`. |
| `android/gradle.properties` | `android.builder.sdkDownload=false` (AGP must not fetch SDK parts on its own). |
| `docker/android-build.Dockerfile` | The build image, pinned by digest and checksum. |
| `scripts/build-apk.sh` | Builds in Docker and creates the signing key on the first run. |
| `docs/` | GitHub Pages (branch `main`, folder `docs/`): `index.html` (home), `l/index.html` (the shared-playlist landing page that decodes the fragment in the browser), `privacy/index.html` (the privacy policy for the OAuth consent screen). Don't put Markdown docs here: everything in it is published. |
| `design/` | The logo source (SVG/PNG) for the adaptive icon. |

---

## 5. Data model and persistence

### Identity
- A **song** is identified by its YouTube `videoId` everywhere: playlists, plays, the download
  cache key, `MediaItem.mediaId`, and the `ytm://track/<videoId>` URI.
- An **album** is identified by its YT Music playlist id (`OLAK5uy_…`). A **public playlist** is
  identified by its playlist id (`PL…` in the results seen so far). Both open through
  `https://music.youtube.com/playlist?list=<id>`.
- An **artist** is identified by its channel id. Songs only carry the artist's *name* (the
  uploader name), so the Top groups artists by normalized name and `findArtist` searches by
  name.
- A **local playlist** is identified by a random UUID.

### Files (in `context.filesDir`)
| File | Format |
|---|---|
| `playlists.json` | v2: `{"version":2,"playlists":[{id,name,createdAtMs,updatedAtMs,offline,tracks:[Track…]}],"deleted":{"<id>":<ms>}}`. v1 files (no `updatedAtMs`, `offline` or `deleted`) are still read. |
| `albums.json` | v1: `{"version":1,"albums":[{id,title,artist,thumbnailUrl,addedAtMs,offline,tracks:[…]}],"deleted":{…}}` (`deleted` is optional). |
| `plays.json` | v2: `{"version":2,"songs":{"<videoId>":{count,lastPlayedMs,track}},"albums":{"<id>":{album,trackIds}}}`. v1 `{"plays":{"<videoId>":n}}` is still read (as songs without a track). **This device's plays only.** |
| `plays-others.json` | Same format: the sum of the other devices' plays as last received by sync. |
| `offline-audio/` | Media3 `SimpleCache` with the saved songs. The download index lives in Media3's own database (`StandaloneDatabaseProvider`). |
| `<name>.unreadable-<ms>` | A JSON file that failed to parse is renamed to this and never overwritten. |

Track JSON: `{videoId,title,artist,durationSec,thumbnailUrl|null}`.

### SharedPreferences
- `ui`: `activeList` (local playlist id), `searchKind`, `albumOrder`, `topKind` (enum names).
- `sync`: `enabled` (bool), `deviceId` (UUID, which names this device's file in Drive).

### `JsonStore<T>` guarantees (every store is built on it)
- The file is read once, lazily, on the first `load()` or `edit()`.
- `edit { value -> next to result }` runs under a `Mutex`. When `next != current` it **writes to
  disk first** (temp file plus rename, on `Dispatchers.IO`) and only then publishes to `state`.
  So the UI never shows unsaved data, and a crash never leaves half a file.
- Data classes are compared by value, so returning the same value means "no write".
- Stores take an `io` dispatcher and a `clock` so tests can inject `Dispatchers.Unconfined` and
  fake time.

### Evolving a file format
1. Add the field with a default, and read it with `opt*` and that default, so old files still
   load.
2. Bump `version` only when old readers would misread the new data. Readers must accept every
   older version.
3. **Sync is affected too.** `DeviceSync` sends `PlaylistStore.encode(...)` and the other encoders
   inside `device-*.json` (sync format `FORMAT_VERSION` = 1). A device running an older app
   reads files from newer ones and **skips** them when the version is higher. Bump the sync
   `FORMAT_VERSION` only when an older app would merge the new data wrongly.
4. Add a test that reads an old-format file (see `PlaylistStoreTest`, `TopTest`).

---

## 6. Threading and state conventions

- `YouTubeMusic`, `RelatedSongs` and `DriveAppData`'s HTTP calls **block**: call them inside
  `withContext(Dispatchers.IO)`. Never call them on the main thread.
- Store methods are `suspend`, so call them from `viewModelScope` or `appScope`. Their
  `StateFlow`s are the source of truth for the UI.
- `MusicApp.appScope` and everything in `OfflineAudio`/`DeviceSync` run on the **main thread**.
  `DownloadManager` must be created on, and reports to, the thread that created it.
- The ViewModel exposes `StateFlow`s. Screens collect them with `collectAsStateWithLifecycle`
  in `MusicScreen` and pass plain values and lambdas down: screens hold no ViewModel reference.
- One-off notices go through `say(text, undo?)` → `messages` channel → snackbar ("Deshacer" when
  an undo lambda is given).
- Session caches in the ViewModel (`albumTrackCache`, `playlistTrackCache`, `artistCache`,
  `albumOfCache`) are plain maps touched only from the main thread.

---

## 7. Main flows

### 7.1 Search (tab "Buscar")
- `SearchKind` = Songs / Albums / Artists / Playlists. The choice is remembered in prefs, and
  changing it repeats the last query.
- `MusicViewModel.search(q)` → `YouTubeMusic.search*` with NewPipe's YT Music filters
  (`MUSIC_SONGS`, `MUSIC_ALBUMS`, `MUSIC_ARTISTS`, `MUSIC_PLAYLISTS`) → `SearchState.*`.
- Song results are followed by **Relacionadas**: `RelatedSongs(first result)`. It loads after the
  results are on screen, 10 at a time.
- **Suggestions:** `typed` → debounce 250 ms → `YouTubeMusic.suggestions` (drops non-music terms).
  While the field has focus, the suggestions replace the results.
- Thumbnails are upscaled by rewriting googleusercontent size suffixes (`=w544-h544-l90-rj`,
  `=s544`).
- **Public playlists (v0.8.0):** the results open `Route.OnlinePlaylistPage` →
  `OnlinePlaylistScreen`, which loads `playlistTracks` (≤ 200 songs, de-duplicated). "Guardar en
  Mis listas" → `saveOnlinePlaylist` creates a local **streaming** list with a free name
  (`freeName`: "X", "X (2)"…). That copy is independent of the original from then on.
  `savedOnline` (session only, not persisted) lets the page say "En Mis listas". YouTube gives
  no song count in search results (`songCount` = -1), and in users' lists the "artist" is
  sometimes the uploading channel.

### 7.2 Playback
1. `MusicViewModel.play(tracks, index)` turns each `Track` into a `MediaItem` (mediaId =
   videoId, metadata = title, artist, artwork, duration) and sends them through the
   `MediaController`.
2. Media3 strips URIs from items sent by controllers. `PlaybackService.Callback.onAddMediaItems`
   restores `ytm://track/<videoId>`.
3. ExoPlayer → `CacheDataSource`. A fully saved song is read from disk and the upstream is
   never opened. Otherwise → `YouTubeDataSource.open` → `YouTubeMusic.resolveAudio`. It picks
   the highest-bitrate original-language progressive audio stream, Opus winning ties (in
   practice Opus/WebM ~160 kbps). The URL is cached until 10 min before its `expire=`.
4. Reads go in 2 MB chunks with `StreamRequests.build`. A 403 on open means the URL is stale:
   invalidate, re-resolve once.
- **Tapping the song that is already playing doesn't restart it.** `play()` rebuilds the queue
  around it with remove + add (not replace, so the items go through `onAddMediaItems`).
- `SkipUnplayable` skips songs that fail, for example offline and not saved.

### 7.3 Play counting
`CountPlays` in the service counts a play when a song **ends**: a transition with reason
AUTO/REPEAT, or STATE_ENDED. A skip (SEEK / PLAYLIST_CHANGED) doesn't count. It stores the
track details with the count (`PlayCountStore.countPlay`) so the Top can show songs that are in
no list. Playing from an album page also calls `rememberAlbum` so the album can rank in the Top.

### 7.4 Library
- **Mis listas:** local playlists. A song appears at most once per list. Removing a song offers
  undo. Lists are reordered with the "Subir/Bajar" menu items. Deleting asks for confirmation.
- **Active list:** "+" next to any song adds it with one tap to the active list (with undo).
  A long press opens `AddToPlaylistSheet`. The first list created or chosen becomes active.
- **Albumteca:** albums with their tracks stored at the moment they are added. Sorting: Más
  escuchados (plays) / A-Z / Z-A, with Spanish collation.
- **Top:** `Top.songs/artists/albums` from `PlayHistory`, at most 100 rows each.
- **Ir al álbum:** `albumOf(track)` checks the Albumteca, then the played albums, then
  `YouTubeMusic.findAlbum`: search albums for "artist title" and open up to 4 candidates.

### 7.5 Saved on the phone (offline)
- The choice is per playlist or album, **never per song**. A song must be on the phone iff
  some saved list or saved album contains it (`wantedOnPhone`).
- `MusicApp` combines playlists and albums → `OfflineAudio.sync(wanted)` → `planSync` adds the
  missing songs and removes the unwanted ones. Failed downloads are retried **only** from
  "Reintentar".
- Downloads run only while `AudioDownloadService` is up, and on Android 12+ it can only start
  while the app is visible (`inForeground`). It is restarted on foreground and when the network
  returns while the app is open.

### 7.6 Sharing and importing playlists
- "⋮ → Compartir" → `PlaylistShare.link`: `https://…/esputifai/l/#<base64url(zlib(JSON))>`,
  where the JSON is `{"v":1,"n":name,"t":[[videoId,title,artist,durationSec]…]}` (no
  thumbnails).
- The page decodes the list in the browser and links to `esputifai://import?d=<data>`. Then
  `MainActivity.handleLink` → `receiveShared` → the "¿Importar?" dialog → `importIncoming` creates
  a streaming list.
- **Forks must change** `PlaylistShare.PAGE` and the manifest's https host.

### 7.7 Device sync (Google Drive appDataFolder)
- Each device writes **only its own file**, `device-<uuid>.json` =
  `{version, device (model), playlists, albums, plays (own only)}`, and reads the others'. So
  there is no locking and no overwriting.
- **A round** (`DeviceSync.syncNow`, under a mutex):
  1. Get a token. If there is none → `NeedsSignIn`.
  2. **Load the local stores first.** An unloaded, empty library would look like "everything
     was deleted".
  3. List the folder.
  4. Download the files whose `modifiedTime` changed.
  5. `playlists.merge`, `albums.merge`, `plays.setOthers(addUp(others))`.
  6. Upload this device's snapshot if it changed.
- **When:** 5 s (debounced) after any local change of the playlists, the albums or this
  device's plays, even in the background. On foreground, and every 60 s while on screen.
  "Sincronizar ahora" from the dialog.
- **Merge rules (`SyncMerge`):** for each playlist, the latest `updatedAtMs` wins as a whole.
  Deletion markers win over older versions, and a later change brings a list back. Albums are
  versioned by `addedAtMs`. The `offline` flag is per device: it is kept locally, and is false
  for what arrives new. Plays are added up, never merged, and each device sends only its own,
  so syncing never double-counts. The merge is order-independent (and so deterministic);
  ties are broken by name + track ids.
- **Setup is outside the code:** a Google Cloud project with the Drive API enabled, a
  **published** consent screen with only `drive.appdata`, and one Android OAuth client per
  package (`dev.personal.music`, `dev.personal.music.probe`) with the signing certificate's
  SHA-1. A new signing key needs new OAuth clients.
- **Known limits:** two devices changing the same list within one round lose one of the
  changes. "Latest" trusts each device's clock. A reinstalled app is a new device: its old file
  keeps counting its plays.

---

## 8. UI conventions

- **Navigation:** `MusicScreen` has `Tab` (Search, Playlists, Albums, Top) and, per tab, a stack
  of `Route`s (`PlaylistPage(id)`, `AlbumPage(album)`, `ArtistPage(artist)`,
  `OnlinePlaylistPage(playlist)`). `push`/`pop`, and Back pops. Tapping the current tab clears
  its stack. Adding a screen means adding a `Route` and a `when` branch in `MusicScreen`.
- **Song rows** always go through `TrackRow`, or through `songItems(tracks, SongActions,
  keyPrefix)` in a `LazyColumn`. `SongActions` bundles the playing id, the ids in the active
  list, and play/add/choose-lists callbacks. LazyColumn keys must be unique across sections,
  hence `keyPrefix`.
- `LocalPlays` (play counters) and `LocalSongMenu` (long press) are provided by `MusicScreen`,
  again around `NowPlayingSheet`, which sits outside the Scaffold.
- **Pushed screens** follow the `AlbumScreen` pattern: `BackBar`, then the optional
  `activeListBar()`, then content loaded with `produceState(…, attempt)`. The states are
  spinner, then `EmptyMessage` with "Reintentar" on failure, then a `LazyColumn` whose header is
  the first item.
- Material 3 **standard sizes** (an app-wide zoom was tried and reverted). Text follows the
  phone's font size.
- Destructive actions ask first (`ConfirmDialog`). Removals that can be undone use a snackbar
  with "Deshacer".
- Song rows of streaming content are dimmed when offline and the song isn't saved.

---

## 9. Testing

All tests are JVM tests in `android/app/src/test/java/dev/personal/music/`. They use the real
`org.json` (the Android stub returns defaults) and `unitTests.isReturnDefaultValues = true`
(OkHttp's Android artifact logs through `android.util.Log`).

| Test | Covers | Network |
|---|---|---|
| `YouTubeMusicSmokeTest` | search → resolve → three byte ranges, with the player's exact request shape | yes |
| `CatalogSmokeTest` | related songs (paging, no repeats), suggestions, `findAlbum`, `titleKey`, album search and tracks, artist page filtering, **playlist search and tracks** | yes |
| `PlaylistStoreTest` | round trip with real tracks, unreadable file set aside, v0.2.0 files, blank names | partly |
| `AlbumtecaTest` | album order, persistence | no |
| `TopTest` | plays persistence (v1 and v2), rankings | no |
| `OfflineRulesTest` | what must be on the phone, sync plan, progress | no |
| `PlaylistShareTest` | link round trip, the three accepted forms, garbage, size | no |
| `SyncMergeTest` | two simulated devices with real stores and fake clocks: creation, latest wins, deletions, per-device offline, albums | no |

**When smoke tests fail and the code didn't change, YouTube changed.** Follow the maintenance
steps in README ("When YouTube breaks playback or search"): usually you bump NewPipeExtractor
(by commit hash), then compare `StreamRequests` with NewPipe's `YoutubeHttpDataSource` and the UA
with NewPipe's `DownloaderImpl`.

Things only a real device can check: playback, notification and lock screen, downloads and
airplane mode, Google sign-in and sync, link opening, and R8 (release) behavior. Say so
explicitly when a change touches them.

---

## 10. Owner's decisions (requirements)

- No server, no YouTube account sign-in. Playlists are never synced with YouTube.
- The offline choice is per playlist or album, never per song. Downloads use any network,
  including mobile data.
- A play counts only when the song plays to its end.
- Tapping the song that is playing doesn't restart it.
- The artist is one page: 5 popular songs ("Ver todas"), then albums.
- Album order "Más escuchados" uses the plays of the album's songs from anywhere.
- "Reproduciendo" is a sheet with a seek bar and the queue, not a full-screen player (a
  full-screen player was dropped).
- Standard Material sizes, no app-wide zoom.
- Device sync goes through each user's own Google Drive (`drive.appdata` only). Wi-Fi-only
  device-to-device sync was dropped.
- A public playlist saved to "Mis listas" is a **copy** (streaming) that never follows the
  original.
- Only critical CVEs (CVSS ≥ 9) are patched, with pinned versions as the priority (see README,
  "Pinned versions and supply chain").

---

## 11. Recipes

### Add a search kind (how "Listas" was added in v0.8.0)
1. `data/`: a model if one is needed (`OnlinePlaylist` in `Album.kt`), plus
   `YouTubeMusic.searchX` with the right `YoutubeSearchQueryHandlerFactory.MUSIC_*` filter and an
   `InfoItem.toX()` mapper.
2. A smoke test in `CatalogSmokeTest` against real YouTube. Run it **before** writing any UI.
3. `MusicViewModel`: a `SearchKind` entry (with its Spanish label), a `SearchState` subtype, and
   a branch in `search()`.
4. `SearchScreen`: the idle text, the results branch, the placeholder, and whether
   `activeListBar` shows.
5. If results open a page: a `Route` and a branch in `MusicScreen`, plus a screen file following
   the `AlbumScreen` pattern.
6. Update README and this file.

### Add a field to a persisted model
Follow §5 "Evolving a file format". Remember `SyncMerge`: decide whether the field is shared
(it travels in the device file) or per device (like `offline`, kept local in the merge).

### Add or update a dependency
Change `libs.versions.toml`, then regenerate the checksums with
`--write-verification-metadata sha256 assembleRelease testDebugUnitTest`, **review the diff** of
`verification-metadata.xml`, and build a release (R8 may need new keep rules). JitPack only
serves `com.github.TeamNewPipe`.

### Something works in debug but not in release
Suspect R8 first: add a `-keep` rule to `proguard-rules.pro` (NewPipe and Rhino use
reflection). Obfuscation is off, so stack traces stay readable.

### compileSdk or build-tools change
Update both `docker/android-build.Dockerfile` (`sdkmanager` packages) and
`android/app/build.gradle.kts` (`compileSdk`, `buildToolsVersion`). AGP may not download SDK
parts by itself.

---

## 12. Gotchas

- **googlevideo URLs are IP-locked and expire.** That is why resolution happens on the phone and
  is cached only until `expire - 10 min`.
- NewPipe v0.27.0 hands out `c=VISIONOS` URLs, which need the visionOS UA
  (`StreamRequests`). A plain GET with a `Range` header gets throttled or rejected.
- NewPipe exposes **no tabs for YT Music artist channels**. The artist pages are built from
  searches filtered by uploader channel id.
- The radio `RDAMVM<id>` returns overlapping pages and video versions of songs already given,
  so `RelatedSongs` de-duplicates by `titleKey`.
- Media3's `CacheDataSource` needs the **same cache key** (the videoId) for downloads and
  playback: `CACHE_KEY` in `OfflineAudio`, and `setCustomCacheKey` on requests.
- `DownloadManager` reports state changes, not byte progress, so `OfflineAudio` polls active
  downloads every second.
- `DeviceSync` must never upload before the stores are loaded (an empty library would mean
  "everything deleted").
- The debug build has the application id suffix `.probe`. It is a separate app with separate
  data and its own OAuth client.
- The shared-playlist `https` link can't be an auto-verified App Link (GitHub project pages
  can't serve `/.well-known/assetlinks.json` at the domain root). Hence the landing page.
- `Album`/`OnlinePlaylist` ids come from the `list=` parameter of NewPipe's item URL. Keep that
  regex in sync if NewPipe changes its URLs.

---

## 13. Glossary (UI Spanish → meaning)

| UI text | Meaning |
|---|---|
| Buscar | Search tab |
| Canciones / Álbumes / Artistas / Listas | Search kinds: songs, albums, artists, public playlists |
| Relacionadas / Más relacionadas | Related songs under song results / load 10 more |
| Mis listas | The user's local playlists (tab) |
| Albumteca | The user's album collection (tab) |
| Top | Most-played songs, artists or albums (tab) |
| Añadir a: [lista ▾] | Active-list bar; "+" adds there |
| Lista activa | The active list |
| En streaming / Guardada en el móvil | A list or album that is streamed / downloaded to the phone |
| Pasar a streaming / Guardar en el móvil | Turn the offline mode off / on |
| Reintentar | Retry |
| Deshacer | Undo (snackbar) |
| Reproduciendo | The now-playing sheet |
| Ir al álbum | Song menu: open the song's album |
| Compartir / ¿Importar? | Share a list as a link / dialog shown when one is received |
| Guardar en Mis listas | Copy a public playlist into a local list |
| Sincronizar con Google | Turn on device sync |
| Reproducir / Aleatorio | Play / Shuffle |
| Ver todas / Ver menos | Show all / fewer songs (artist page) |
