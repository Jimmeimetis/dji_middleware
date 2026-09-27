# dji_middleware

Android middleware between DJI aircraft (MSDK V5) and the Ikaros/Warden web app.
Built on DJI's Mobile SDK V5 sample, stripped down to the parts it uses.

Tested with:
- DJI Mini 4 Pro, phone (Poco F5) connected to the RC over USB
- DJI Matrice 3T, running on the controller's built-in Android

## What's in it

- `app/`: launcher screen (`DJIMainActivity`). It registers the SDK, shows
  product info and edits the connection settings (vehicle id, token, user id,
  Warden host). Settings are saved to SharedPreferences `AppPrefs`.
- `uxsdk/`: DJI's UX SDK widget library, plus
  `dji.v5.ux.sample.showcase.defaultlayout.DefaultLayoutActivity`, the FPV screen.
  That screen handles waypoint commands, telemetry and mission-status HTTP calls
  to Ikaros, RTMP live streaming, and the Warden Flask updates.

## Setup

1. Copy `secrets.properties.example` to `secrets.properties` and fill it in. It is
   git-ignored and holds the DJI app key, map keys, Ikaros token and RTMP password.
2. Open the project root in Android Studio. The Gradle JDK must be 17: Kotlin
   1.8.10 cannot run on JDK 21.
3. Build and run `app`.

The DJI app key is tied to the package name `com.dji.sampleV5.aircraft`, so keep
the `applicationId` unless you register a new key.
