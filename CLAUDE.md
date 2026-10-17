# CLAUDE.md — Neutrino Calendar for Android

Part of the Neutrino platform — read the root `../CLAUDE.md` coupling rules first. This is the
Android port of `neutrino_calendar_ios_mobile`, built on `../neutrino_shared_android` (consumed by
local path through `includeBuild`, unversioned — a change there reaches this app and Drive at
once). Its OAuth client id is `neutrino-calendar-android` (`neutrino/migrations/00142_oauth__*`);
its prefs/secrets prefix is `ncal`. Never change or reuse either.

- Build with JDK 17: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
- Put calendar logic in `:core` (JVM-tested), not `:app`. Recurrence, repeat rules and Smart Add
  are checked against vectors exported from the iOS tests (`core/src/test/resources/`); when the
  iOS behaviour changes, re-export them rather than editing them by hand.
- Saved places and attachments use the shared E2EE code — never add crypto here.
- Status, decisions, the iOS → Android feature map and the next-session checklist:
  `agent_docs/android-port.md`.
