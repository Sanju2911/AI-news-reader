# AI News Reader — Project Documentation

Reviewed 14 October 2026 • Android phone, Wear OS companion, and web application

## 1. Project overview

NewsReader turns topic-based news headlines into spoken bulletins. It fetches articles from GNews, asks Gemini to produce conversational story segments, and synthesizes audio for listening. The Android phone handles playback; the Wear OS app controls the phone. The web application implements a separate browser-based reader with an Express API proxy.

Eight topics are implemented: World, Tech, Business, Sports, Health, Science, Entertainment, and India. GNews requests use English, country `in`, and up to ten articles. India maps to GNews category `nation`.

This documentation describes the files in this checkout, the observed web session, and native watch emulator screens. See sections 3.6–3.11 for watch functions, personality, screenshots and verification limits. Provider pricing and supported models have not been independently researched. Existing README claims about free quotas should not be treated as verified service terms.

## 2. Review scope and verification

| Area | Evidence | Result |
| --- | --- | --- |
| Android phone | Kotlin, XML layouts, manifest, Gradle configuration | Source reviewed; app not launched |
| Wear OS | Current source built and launched in Wear OS Large Round emulator | Native player, topics, active-topic styling and volume captured; sample state clearly labeled |
| Web | Running Express server and browser UI | World and Tech loaded with ten articles each |
| Voice settings | Running browser dialog | Language and speaker selectors displayed; changes cancelled |
| GNews | Server responses during browser session | Articles fetched successfully |
| Gemini | Server response | HTTP 404; configured model reported unavailable; title/description fallback used |
| Supabase | Server diagnostics | Configured host failed DNS resolution; persistence not verified |
| Sarvam | Server responses and browser controls | One World audio segment generated; subsequent requests returned HTTP 402, no credits available |
| Audio playback | Tech play button attempt | No successful playback verified; button remained in play state |
| Android devices | SDK ADB inventory | Watch emulator launched for follow-up capture; no paired phone |

Screenshots 01–04 capture the running web application. Screenshots 05–10 capture the native watch app built from this checkout on the Wear OS Large Round emulator. The populated watch screens use explicitly synthetic local state; they do not verify a phone connection or real audio playback. Headlines in the web screenshots were not fact-checked. The watch debug build passed; the phone was not built or launched, and no database migration was performed.

## 3. Screenshots and user guide

### 3.1 Headlines screen

![World headlines screen](screenshots/01-web-headlines.jpg)

The header contains refresh and voice settings buttons. A voice information bar shows the selected language and speaker. Topic chips sit above the scrollable article list. Each card shows its publisher, image when available, headline, summary, audio status, and play button. The fixed player contains the topic, article position, current headline, progress bar, and previous/play/next controls.

This capture shows World, article 1 of 10, with audio marked ready. The “Saved to Supabase” badge does not establish a successful save: the runtime logs showed DNS failures, and the report route still returned `saved: true`.

### 3.2 Voice settings

![Web voice settings dialog](screenshots/02-web-voice-settings.jpg)

Open the gear button, select a language and speaker, and choose Apply. Cancel closes the dialog without changing the selection. Applying settings clears in-memory audio so future synthesis uses the new settings.

The web UI lists ten languages: English (India), Hindi, Tamil, Telugu, Kannada, Malayalam, Marathi, Bengali, Gujarati, and Punjabi. Its eight speaker options are Ritu, Amol, Amartya, Diya, Neel, Maitreyi, Advika, and Arjun. These are configured UI choices, not proof of current provider support. Web voice preferences are held in memory and reset on reload. Choosing a TTS language does not translate the English report text.

### 3.3 Topic navigation

![Technology headlines screen](screenshots/03-web-technology.jpg)

Choose a topic chip to load that topic. The capture shows Tech selected with ten stories and the player reset to article 1. Switching topics stops current playback and resets the web article index. Topics extend horizontally beyond the initial visible chip row.

### 3.4 Audio unavailable during verification

![Technology screen after unsuccessful audio attempt](screenshots/04-web-audio-unavailable.jpg)

The Tech play attempt could not obtain audio because Sarvam returned HTTP 402. The UI returned to “Tap to listen” and retained the play icon rather than displaying an explicit quota error. This screenshot records the resulting screen; it is not evidence of working audio playback.

### 3.5 Common operations

| Action | Web | Android phone | Wear OS |
| --- | --- | --- | --- |
| Choose a topic | Topic chip | Topic chip | Topics page; selection returns to controls |
| Listen to a story | Card or player play button | Card/player controls; loading can start audio automatically | Commands phone player |
| Pause/resume | Player button or Space | Player/media notification | Main play/pause button |
| Previous/next | Buttons or Left/Right arrow keys | Player/media notification | Main previous/next buttons |
| Change voice | Gear dialog | Settings dialog; separate Google/Sarvam engine toggle | No synthesis engine configured on watch |
| Adjust volume | Device/browser audio controls | Device media volume | Circular volume page or rotary input |
| Refresh | Reloads current topic through API | Refresh button triggers immediate background refresh | Topic requests handled by phone |

Web Escape dismisses the settings dialog. Web playback automatically advances within the current topic when audio ends. Phone auto-play has a toggle: when enabled, it inserts a two-second gap and can advance through topics, stopping after the last topic.

### 3.6 Native watch: disconnected player

![Native Wear OS player without a paired phone](screenshots/05-watch-player.png)

This is the actual current watch app, not a phone screen or web mockup. Without phone state it shows “Loading news...” and “World · 1/0.” The empty total produces a misleading counter; no stories have loaded. The companion depends on a phone for news and audio.

### 3.7 Native watch: populated playback display

![Native watch player with synthetic Science story state](screenshots/06-watch-player-sample.png)

The sample state is Science, story 3 of 10, 42% progress, and playing=true. It exercises the real state parser and UI observers, displaying the pause icon and orange circular progress ring. The title is “Sample story: New discoveries in space,” which scrolls as a marquee. No audio is playing; playing=true is a fixture input.

Previous and next flank the central button. The lower-left shortcut opens Topics, and the lower-right shortcut opens Volume. In normal use, play/pause, previous and next send commands to the phone and wait for updated phone state.

### 3.8 Native watch: topics and active selection

![Native watch scrollable topic list with sample phone topics](screenshots/07-watch-topics.png)

The eight topic names were supplied locally using the phone-message format. The native list uses large rounded cards on a black background. World, Tech and Business are visible at the top; scrolling reveals the remaining topics.

![Native watch active Science topic and lower topic cards](screenshots/08-watch-active-topic.png)

The current Science topic has an orange outline, orange text, a dark brown fill, and a musical-note marker. Entertainment and India use neutral gray cards. Clicking Science was observed to return to the main player; logs confirmed selection of `SCIENCE`. With no connected node, the command could not reach a phone.

### 3.9 Native watch: interactive volume

![Native watch circular volume control at level 7](screenshots/09-watch-volume.png)

The volume page uses a 240-degree arc with a dark track, orange fill and white thumb. A white speaker icon and large numeric value occupy the center. The sample initial level was 7 out of 15; normally the phone supplies both volume and maximum volume.

![Native watch volume after local adjustment to 13](screenshots/10-watch-volume-adjusted.png)

Clicking the ring changed the local value from 7 to 13 and moved the thumb and orange fill. This confirms the watch's local interaction and optimistic state update. It does not confirm a change in phone volume. The code also maps rotary input to volume commands; physical crown operation was not tested. Back returns from Topics or Volume to the player.

### 3.10 Watch personality and visual design

The watch feels like a compact media remote. Its personality comes from the dark sculpted background, warm orange highlights, circular progress and volume rings, and a large light-gray central button. White media icons provide contrast, while the orange topic/counter connects the playback state to the topic list.

| Design choice | Visible implementation | Purpose |
| --- | --- | --- |
| Round composition | Main button and rings centered within the round display | Matches watch shape and keeps the main action prominent |
| Background | Blue-gray contour texture, dark overlay | Adds depth while supporting white labels |
| Accent | Orange `#E07A3A` | Progress, topic context and active selection |
| Topic cards | Rounded gray cards; brown/orange active card | Makes choices easy to scan and tap |
| Motion | Fade transitions and scrolling headline | Changes pages smoothly and fits longer headlines |
| Hierarchy | Central playback; secondary topic/volume shortcuts | Prioritizes one-handed, glanceable interaction |

“Personality” here means the app's appearance and interaction character. The watch has no independent AI persona, conversation, voice selection or narration; those functions belong to the phone. The current watch screen does not visibly surface its connection LiveData, and the disconnected topic page can be blank. The sample marquee also clips text around the round screen edge, which is worth checking on physical watch sizes.

### 3.11 Watch capture method and reproduction

The source was built with `:wear:assembleDebug` and installed on `emulator-5554`. The supplied release APK could not replace the earlier emulator app because its signature differed; the debug build installed successfully. Captures therefore show the current source build, not the older installed version.

`docs/watch-capture/` contains an opt-in instrumentation fixture. It injects sample state and topic JSON into the real ViewModel without editing production code or sending fabricated phone messages. Only builds invoked with the initialization script include this fixture. It keeps the app alive for up to ten minutes while screens are captured.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
.\gradlew.bat :wear:assembleDebug :wear:assembleDebugAndroidTest -I docs/watch-capture/capture.init.gradle
adb -s emulator-5554 install -r wear/build/outputs/apk/debug/wear-debug.apk
adb -s emulator-5554 install -r wear/build/outputs/apk/androidTest/debug/wear-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w com.newsreader.test/com.newsreader.capture.WatchCaptureRunner
```

Use your own JDK path and SDK platform-tools path if `adb` is not on PATH. Captures were made with the Computer Use skill against the native emulator window. No phone pairing, command delivery, real playback, or physical rotary input is claimed by these sample screenshots.

## 4. Architecture and data flow

```text
Android phone                              Web browser
  MainActivity / NewsAdapter                 index.html: UI + in-memory state
            |                                          |
  MainViewModel                            Express server.js
            |                                          |
  Supabase latest report lookup <----------> Supabase news_reports
            | cache miss                               | cache miss
           GNews <------------------------------------ GNews
            |                                          |
  Gemini report generation                 Gemini report generation
            | split by ---NEXT---                      | split by ---NEXT---
  Google device TTS or Sarvam               Sarvam TTS
            |                                          |
  WAV file / AudioPlaybackService           WAV Blob / HTML Audio
            |
  Wearable MessageClient <---------------> Wear OS controls

Phone WorkManager: fetch all topics -> generate reports -> save -> prepare audio
Supabase SQL: optional hourly deletion of reports older than 48 hours
```

The phone and web client call providers separately; the Express server is not the phone's backend. Both implementations share the Supabase report format and topic identifiers. The watch does not fetch news or synthesize speech independently.

### 4.1 Phone pipeline

`MainActivity` binds views and observes LiveData from `MainViewModel`. The ViewModel maintains separate topic states and background jobs, so switching topics does not cancel speech generation for another topic.

Loading first restores existing in-memory topic state. Otherwise it reads the latest Supabase report. A cache hit splits `report_text` into stories and reconstructs card metadata from `articles_json`. Cached audio is read from local files and Supabase; when both exist, the implementation selects the cache with more populated segments.

On a cache miss, `NewsRepository` fetches GNews articles, preserving full metadata in memory. Gemini receives all articles in one request, including titles, descriptions, and up to 400 content characters per article. The prompt asks for two or three spoken sentences per story and uses `---NEXT---` as a separator. Failed provider requests fall back to titles and descriptions.

The default phone speech engine is device Google TTS unless a persisted preference changes it. Sarvam is an alternative. The first available segment can start playing automatically. Remaining synthesis requests are sequential within each topic with a 1.5-second delay; the actual semaphore is one, despite an older comment mentioning three concurrent requests. Separate topic jobs can still overlap.

`AudioPlaybackService` owns MediaPlayer, a media session, foreground notification controls, audio focus, and progress callbacks. Audio is decoded into WAV files in the app cache directory. Sarvam HTTP 429 retries use up to five attempts with delays of 2, 4, 8, and 16 seconds.

### 4.2 Web pipeline

`web/public/index.html` contains HTML, CSS, and JavaScript in a single file. On startup it loads World through Express. Supabase hits supply pre-generated reports; otherwise GNews articles are followed by `/api/report`. The browser then calls `/api/tts` for individual segments, decodes base64 into a WAV Blob, and uses HTML Audio for playback.

`prepareTTS` recursively prepares subsequent stories, so loading a topic can synthesize multiple segments without clicking Play. The web client does not use the stored `audio_base64` cache; it only logs its presence. There is no current client call to `/api/cache-audio`. Browser state and audio are in memory, with no localStorage persistence.

### 4.3 Scheduled refresh and freshness

`NewsRefreshWorker` runs periodic Android work every four hours with a connected-network constraint. It checks for an existing topic/batch report, fetches headlines, generates a report, saves it, and attempts to pre-generate audio. The UI also schedules immediate work for manual refresh.

Batch keys are rounded down to UTC slots 00:00, 04:00, 08:00, 12:00, 16:00, and 20:00. In India these correspond to 05:30, 09:30, 13:30, 17:30, 21:30, and 01:30 the following day. Initial worker delay is calculated against local four-hour slots; it is not aligned to the UTC batch boundaries. Android periodic work is not an exact wall-clock scheduler.

The repository's GNews memory cache expires after four hours and can return stale articles on failure. Supabase reads request the latest report without enforcing a maximum age. Refreshing a screen can therefore reload an old database report. The web server has a manual refresh-all endpoint but no periodic scheduler.

## 5. Repository map

| Location | Responsibility |
| --- | --- |
| `app/build.gradle` | Phone SDK levels, dependencies, release configuration |
| `app/src/main/java/com/newsreader/ui/` | Phone activity and article adapter |
| `app/src/main/java/com/newsreader/viewmodel/MainViewModel.kt` | Topic state, pipeline orchestration, caching, playback commands |
| `app/src/main/java/com/newsreader/data/ApiConfig.kt` | Provider configuration, topics, preferences, watch constants |
| `app/src/main/java/com/newsreader/data/NewsRepository.kt` | GNews, Gemini, speech generation, audio file decoding |
| `app/src/main/java/com/newsreader/data/GoogleTTSHelper.kt` | Android TextToSpeech initialization and file synthesis |
| `app/src/main/java/com/newsreader/data/SupabaseClient.kt` | Raw HTTP requests to PostgREST |
| `app/src/main/java/com/newsreader/data/SupabaseRepository.kt` | Report queries, saves, audio cache, cleanup |
| `app/src/main/java/com/newsreader/data/Models.kt` | Provider payloads, story and playback models |
| `app/src/main/java/com/newsreader/data/ApiInterfaces.kt` | Retrofit API declarations |
| `app/src/main/java/com/newsreader/service/` | Foreground playback, background refresh, watch commands |
| `app/src/main/res/layout/` | Main phone screen, story card, voice dialog |
| `wear/src/main/java/com/newsreader/wear/` | Watch activity/ViewModel/listener/service and circular volume control |
| `wear/src/main/res/layout/` | Watch controls and topic rows |
| `web/server.js` | Express routes and external API proxy |
| `web/public/index.html` | Entire browser client |
| `supabase_schema.sql` | Report table, indexes, policies, cleanup schedule |
| `release-apks/` | Existing phone and watch APK distributions |
| `docs/` | This guide, offline HTML edition, and screenshots |

`MainViewModel.kt.bak` is a backup file rather than the active ViewModel. APK filenames include versions beyond some current Gradle version names; file naming alone does not establish the APK's build provenance.

## 6. Build and run

### 6.1 Web

Requires Node.js and npm. The package has one runtime dependency, Express, and one script, `start`. Its server port is hard-coded to 3000.

```powershell
cd web
npm ci
npm start
```

Open `http://localhost:3000`. Stop the terminal process with Ctrl+C. Configure the provider credentials and Supabase connection in `web/server.js`; environment variables are not currently wired into this implementation.

The verification session used the already installed dependencies. The server and temporary application tab were stopped after capture.

### 6.2 Android phone and watch

| Setting | Phone | Watch |
| --- | --- | --- |
| Module | `app` | `wear` |
| Application ID | `com.newsreader` | `com.newsreader` |
| Namespace | `com.newsreader` | `com.newsreader.wear` |
| Minimum SDK | 26 | 28 |
| Compile SDK | 35 | 35 |
| Target SDK | 35 | 33 |
| Source versionName | 1.0 | 1.1 |
| Source versionCode | 1 | 2 |

The wrapper uses Gradle 8.13; the root build specifies Android Gradle Plugin 8.12.3 and Kotlin 2.0.21. Java/Kotlin source targets are 17. Use Android Studio with support for this Gradle configuration, JDK 17, and Android SDK platform 35. Set your machine's SDK directory in the root `local.properties`.

```powershell
.\gradlew.bat :app:assembleDebug :wear:assembleDebug
```

Open the project in Android Studio, sync Gradle, select `app`, and run on a phone. Select `wear` and run on a Wear OS device. Pair the devices using their supported companion setup or Android Studio emulator pairing. Matching application IDs and signing identity matter for the wearable connection; both modules reference the same release keystore in this checkout.

Debug APKs are normally emitted under `app/build/outputs/apk/debug/` and `wear/build/outputs/apk/debug/`. The watch debug APK and documentation instrumentation APK built successfully during the follow-up review. The local SDK contains `Medium_Phone_API_36.0` and `Wear_OS_Large_Round`; the watch emulator was launched for capture, while the phone emulator was not launched.

### 6.3 Supabase

Configure the project URL and publishable/anonymous access key in the phone Supabase client and web server. Run `supabase_schema.sql` in the intended Supabase project only as an explicit setup operation. It creates a report table, enables RLS with a permissive development policy, grants access, and schedules cleanup through pg_cron. Existing policy/job state should be checked before rerunning this script.

Credentials and signing material exist in the source tree. Their values are deliberately omitted from this documentation. Before distributing the project, move secrets into appropriate configuration, rotate exposed provider credentials, protect signing material, and replace the development database policy with scoped access rules.

## 7. Data model and cache format

| `news_reports` field | SQL type | Purpose |
| --- | --- | --- |
| `id` | UUID primary key | Report identifier, generated by database |
| `topic` | TEXT, required | Uppercase topic key |
| `report_text` | TEXT, required | Spoken stories separated by `---NEXT---` |
| `articles_json` | TEXT, required | JSON-encoded metadata list |
| `batch_time` | TIMESTAMPTZ, required | UTC four-hour batch key |
| `audio_base64` | TEXT, nullable | Android JSON-encoded segment cache; web endpoint accepts opaque text |
| `created_at` | TIMESTAMPTZ | Creation time, defaults to NOW() |

The schema indexes topic, batch time descending, and topic plus batch time. It does not declare a unique constraint for `(topic, batch_time)`, so concurrent check-then-insert clients can create duplicate reports.

Current writers save article title and source only. Therefore persisted reports do not retain original URLs, images, descriptions, or publication timestamps. Cached phone cards explicitly use empty source URLs and no images.

Android's audio field contains a JSON string shaped like this:

```json
{
  "lang": "en-IN",
  "speaker": "ritu",
  "audios": ["<base64 WAV segment>", ""]
}
```

Empty entries represent missing segment audio. Cache validity checks language and speaker, but the cache format does not include speech engine identity. ViewModel writes local `audio_cache_<reportId>.json` files and limits database uploads to roughly 10 MB of JSON characters; the worker uses a roughly 5 MB threshold. Android cache-directory files may be evicted by the operating system.

The SQL cleanup job deletes rows older than 48 hours based on `created_at`, once each hour. The phone also invokes report cleanup during ViewModel initialization.

## 8. Web API reference

These routes have no application authentication or request validation layer beyond basic presence checks. Treat the current server as a development implementation.

| Method and route | Input | Output / behavior |
| --- | --- | --- |
| `GET /api/news/:topic` | Topic key such as WORLD | Latest Supabase report with `_source`, `_report`, `articles`; otherwise GNews response |
| `POST /api/report` | `articles`, `topicName`, optional `topic` | `{segments, saved}`; Gemini generation or title/description fallback; attempts report save |
| `POST /api/tts` | `text`; optional `language`, `speaker` | `{audio}` base64; uses Sarvam bulbul:v3, pace 1.1, 48 kHz |
| `POST /api/cache-audio` | `reportId`, `audioBase64` | Attempts report audio update; `{ok: true}` may mask rejected database updates |
| `GET /api/supabase/status` | None | Up to 50 report metadata rows, count, currentBatch; `ok` can remain true after select failure |
| `POST /api/refresh-all` | None | Processes all eight topics sequentially; returns batchTime and per-topic outcomes |

`/api/report` returns HTTP 400 for missing articles; `/api/tts` returns HTTP 400 for missing text and propagates provider error statuses; `/api/cache-audio` returns HTTP 400 for missing inputs. The HTTPS helper has a 60-second timeout. Other status/error handling is inconsistent: helper functions can return null while routes still report success.

## 9. Phone/watch communication

Both apps use Google Play Services Wearable `MessageClient`. The phone broadcasts to connected nodes, and the companion sends control commands back. The watch manifest marks it as a non-standalone app.

| Message path | Direction | Payload |
| --- | --- | --- |
| `/news/control` | Watch to phone | UTF-8 command text |
| `/news/state` | Phone to watch | JSON playback and volume state |
| `/news/topics` | Phone to watch | JSON array containing topic name/display pairs |

Commands are `play_pause`, `next`, `previous`, `topic:SPORTS` (or another uppercase key), `volume_up`, `volume_down`, and `volume_set:<integer>`.

State fields are `isPlaying`, `currentIndex` (zero-based), `totalItems`, `title`, `topic` (display label), `progress`, `volume`, and `maxVolume`. The watch displays the index plus one. Playback and volume commands are handled through the phone listener/service; a topic change launches the phone activity to access the ViewModel. Background next/previous behavior still depends on the ViewModel callback being available.

The watch uses a ViewFlipper with controls, topics, and circular volume pages. Its foreground service keeps a companion connection process active; it is not an independent audio player.

## 10. Observed issues and troubleshooting

| Symptom / finding | Evidence or likely cause | Next action |
| --- | --- | --- |
| Supabase unavailable | Runtime DNS error for configured host | Verify project existence and configured hostname, then connectivity |
| No AI-written summaries | Gemini returned HTTP 404 for configured `gemini-2.0-flash` | Verify an available provider model and update both implementations consistently |
| Most stories cannot play on web | Sarvam returned HTTP 402, insufficient credits | Check provider account quota; Android device TTS is an existing alternative |
| “Saved to Supabase” despite failure | `/api/report` sets `saved: true` after unsuccessful persistence | Return actual persistence result and show it accurately |
| Stored audio unused by browser | Supabase branch logs audio presence only | Implement compatible segment-cache parsing and cache writes |
| Refresh retains old headlines | Latest-report query has no freshness check | Define report age rules and a force-refresh path |
| Voice language selection does not translate stories | Report prompt remains English | Separate translation from synthesis language selection |
| Possible duplicate batch reports | No composite unique constraint | Enforce topic/batch uniqueness and use an atomic write strategy |
| Google TTS worker audio missing | Worker calls unified synthesis without a cache directory or initialized Google helper | Initialize device TTS in worker or constrain that path to a supported engine |
| Wrong engine audio reused | Audio cache identifies language/speaker only | Include engine in cache identity and invalidation rules |
| Watch controls fail | Pairing, signature, node connectivity, or phone service state | Confirm both apps, pairing, matching signatures, and live phone playback |

The runtime failures above were observed on the review date. The suggested actions are follow-up work; application logic was not changed during this documentation task.

## 11. Validation checklist for future changes

1. Build both debug modules with the checked-in wrapper and installed SDK 35.
2. On phone, verify fresh load and cached load, all topics, both speech engines, setting persistence, and interrupted-network fallback.
3. Verify play/pause, previous/next, auto-play, screen-off audio, and notification controls.
4. Pair phone/watch and verify controls, topic selection, progress updates, volume and rotary input, and disconnection recovery.
5. On web, verify both Supabase and GNews paths, voice settings, keyboard controls, article transitions, and explicit provider error states.
6. Confirm saved reports and segment audio directly in the database; do not rely solely on success badges.
7. Exercise expiry, duplicate writes, segment count mismatch, partial audio, and credential/quota failures.

## 12. Documentation artifacts

- `PROJECT_DOCUMENTATION.md`: editable source guide.
- `PROJECT_DOCUMENTATION.html`: standalone offline reading edition with embedded screenshots.
- `screenshots/01-web-headlines.jpg`: World headlines and ready audio status.
- `screenshots/02-web-voice-settings.jpg`: language and speaker dialog.
- `screenshots/03-web-technology.jpg`: Tech topic selected.
- `screenshots/04-web-audio-unavailable.jpg`: screen after unsuccessful Tech audio attempt.
- `screenshots/05-watch-player.png`: native player with no paired phone.
- `screenshots/06-watch-player-sample.png`: native player with synthetic story/progress state.
- `screenshots/07-watch-topics.png`: populated native topic list, using sample input.
- `screenshots/08-watch-active-topic.png`: Science active-topic styling, using sample input.
- `screenshots/09-watch-volume.png`: native circular volume page at sample level 7.
- `screenshots/10-watch-volume-adjusted.png`: volume after observed local adjustment to 13.
- `watch-capture/`: opt-in local fixture for reproducing populated watch screenshots.

The offline edition contains no application API credentials and does not require a running NewsReader server.
