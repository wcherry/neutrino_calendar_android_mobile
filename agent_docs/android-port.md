# Neutrino Calendar for Android — port notes and handoff

**Started:** 2026-10-16. **Status:** feature parity with the iOS Calendar app as of that date;
run on an Android 14 emulator against a real local server. Never yet on a physical device or the
hosted server.

## 1. Decisions

| Decision | Why |
|---|---|
| Kotlin + Jetpack Compose, `minSdk 26`, `targetSdk 36` | Same toolkit and floor as Drive for Android, so the shared library has one target. |
| `:core` is plain Kotlin/JVM; `:app` is Android | Recurrence, repeat rules, Smart Add, ICS, holidays, the API client and the services are tested on the JVM in seconds. iOS-exported vectors (`core/src/test/resources/*_vectors.json`) pin the behaviour to iOS. |
| Shared code extracted to `neutrino_shared_android` | Account, OAuth, keyring, E2EE, sign-in and key screens were in Drive; Calendar needs all of it. Consumed by `includeBuild("../neutrino_shared_android")` — by path, like `neutrino_shared_ios`. Drive was refactored onto it in the same change. |
| OAuth client `neutrino-calendar-android` | Root CLAUDE.md rule 3. Registered by `neutrino/migrations/00142_oauth__2026-10-16-000000_add_android_calendar_client` (branch `feature/android-calendar-client`). **Sign-in fails against any server without it.** |
| Prefix `ncal`, Keystore alias `neutrino.calendar.secrets.v1` | Same namespace as iOS Calendar's Keychain prefix; Android apps don't share a keychain, so each Neutrino app holds its own session and its own copy of the keyring. |
| Holidays computed on the phone with `date-holidays` in Rhino | iOS runs the web's `date-holidays` bundle in JavaScriptCore so the country list never leaves the device and the dates match the web. Rhino 1.7.15 in interpreted mode, on its own thread with a 16 MB stack (the bundle recurses deeply). The bundle's version is checked against the web's in a test. |
| Reminders are exact alarms (`USE_EXACT_ALARM`) | A reminder app must fire on the minute. Rescheduled from the last sync, on boot, and when alerts are toggled. |
| Arrival reminders use Play Services geofencing, ≤ 100 regions | Android's per-app limit (iOS: 20). `GeofencePlan` picks the nearest/soonest. Location is never sent to the server. Needs "Allow all the time". |
| Background refresh is a 30-minute periodic WorkManager job, plus the calendar signals socket while open | Android's equivalent of iOS's `BGAppRefreshTask` + the live socket. Writes made offline are queued (`PendingWrites`, `noBackupFilesDir/pending-writes.json`) and replayed exactly as first sent. |
| Every network read on `Dispatchers.IO` (shared `NeutrinoApi.text`, `AuthClient`) | Coroutines resume on the caller's dispatcher; reading a body on Main throws `NetworkOnMainThreadException`. Found creating an event on the emulator. |

## 2. Feature map (iOS → Android)

Ported directly: sign-in/registration/2FA, key setup, pairing, recovery kit, key code · month,
week, day, year and agenda views · event create/edit/delete with time zones, guests, notes,
location and repeat rules, "this / this and following / all" edits and exceptions, edit conflicts
· reminders (presets, repeat, snooze, done from the notification, linked to events/tasks) · tasks
with Smart Add (`^date #tag !1 *weekly @Place`), tags, filters, reorder, scheduling onto the
calendar, task detail · saved places (encrypted), arrival reminders · calendars (show/hide, colour,
add, holidays by country/region) · Drive attachments (encrypted into Drive's `Attachments`
folder) · ICS import (open/share a `.ics`) · offline write queue · app lock · settings · report a
bug.

Mapped to Android equivalents:

| iOS | Android |
|---|---|
| WidgetKit (Up Next, Today, Month) | Glance widgets of the same three |
| Live Activity for the next event | Ongoing low-importance "next event" notification, moved on by alarms at start/end |
| Siri / App Shortcuts / Spotlight | Launcher shortcuts (New event / task / reminder) and `neutrinocalendar://` deep links |
| Focus filter | In-app source filter (Settings › Show events from) |
| MapKit search and map | `Geocoder` search and an osmdroid map with the radius circle |
| JavaScriptCore | Rhino |
| `UNUserNotificationCenter` | Notification channels `reminders`, `arrivals`, `next_event` |

**Not ported** (not in iOS either, or web-only): connecting Google/Outlook/iCloud (web only, the
app says so), invitations, shared calendars, drag-to-move in the time grid, tablet layout.

## 3. What is verified (2026-10-16, macOS host)

- `./gradlew :core:test` — 43 tests (+ the live test, skipped without a server): iOS vectors for
  recurrence, repeat rules and Smart Add; holidays (US Thanksgiving/Labor Day where the web puts
  them, bundle version vs. the web); calendar rules; services against a fake backend (offline
  queue and replay, conflicts, recurrence scopes).
- `neutrino_shared_android`: 41 core tests and `scripts/crosscheck-web-crypto.sh` both ways against
  the web's `e2e-crypto`, including saved-place envelopes.
- `LiveServerTest` against a locally built server with migration 00142: register, key, calendars +
  holidays, weekly series with a single-occurrence edit, change feed, reminders, tasks scheduled
  onto the calendar, a saved place read back by a second service, an attachment encrypted up,
  decrypted back and attached.
- On the emulator (Pixel 7, API 34) against that server: sign in; month/week views show series,
  exception, holiday and scheduled task; create an event; Smart Add a task; create a reminder (exact
  alarm scheduled); reminder notification with Done/Snooze; ongoing next-event notification;
  add a holiday country; Up Next widget on the home screen showing the next event; launcher
  shortcuts and deep links.

**Not verified:** a physical device; the hosted server; an arrival reminder actually firing on
entering a geofence; Today/Month widgets placed by hand; ICS import from another app; attachment
open/add through the UI (its core path is in the live test); app lock with biometrics; release
(R8) build at run time.

## 4. Next session checklist

1. Merge/deploy `neutrino` `feature/android-calendar-client` (migration 00142).
2. Physical device: sign in to an existing account, pair with the web, open an event attachment
   added on the web, add a saved place and check the web/iOS can read it.
3. Arrival reminder: a task at a saved place, "Allow all the time", walk (or mock-location) in.
4. `./gradlew :app:assembleRelease` with a signing config; run it (R8 + serialization + Rhino).

## 5. Found while porting (not fixed here)

- Canada (and other federal countries) at country level shows only the holidays `date-holidays`
  defines nationally — e.g. Thanksgiving is per province, so it appears only with a region picked.
  Same on the web and iOS; it is the data, not a port bug.

## 6. Building

JDK 17: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`. `./gradlew :core:test`,
`./gradlew :app:assembleDebug`. Live test: `NEUTRINO_LIVE_URL=http://localhost:8080 ./gradlew
:core:test --tests '*LiveServerTest*'` (registers a fresh account; use a throwaway database).
