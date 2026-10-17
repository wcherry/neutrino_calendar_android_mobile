# Neutrino Calendar for Android

The Android port of `neutrino_calendar_ios_mobile`: events, reminders, tasks (with Smart Add and
arrival reminders at saved places), public holidays, encrypted Drive attachments, widgets and
launcher shortcuts, on top of the calendar API in
[`wcherry/neutrino`](https://github.com/wcherry/neutrino) (`/api/v1/calendar`).

- `core/` — plain Kotlin/JVM: models, recurrence, repeat rules, Smart Add, ICS import, holiday
  engine, the calendar API client, offline write queue and the services the UI binds to.
- `app/` — the Android app (Jetpack Compose, Glance widgets).

## Layout

This repository must sit next to `neutrino_shared_android`, which it builds from source:

```
getneutrino.app/
├── neutrino_shared_android/
└── neutrino_calendar_android_mobile/   ← this repo
```

## Build

Needs JDK 17 and the Android SDK (API 36).

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :core:test             # logic, iOS vectors, services against a fake server
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
NEUTRINO_LIVE_URL=http://localhost:8080 ./gradlew :core:test --tests '*LiveServerTest*'
```

The live test registers a fresh account each run — point it at a throwaway database.

## Platform registration

- **OAuth client** `neutrino-calendar-android` is seeded by `neutrino/migrations/00142_oauth__*`.
  Sign-in fails against a server without it.
- **App config and brand** are `NeutrinoApps.calendar` (shared) and `CalendarBrand`
  (`app/.../ui/Components.kt`). The prefs/secrets prefix is `ncal`. Never change or reuse it.

From the emulator, a server on your computer is `http://10.0.2.2:8080`. See
`agent_docs/android-port.md` for decisions, what is verified, and what is left.
