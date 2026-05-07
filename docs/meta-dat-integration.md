# Meta Wearables DAT Integration Notes

This repo now has a compileable DAT-facing layer, but it deliberately uses `MockDatSessionController` until the real Meta Wearables Device Access Toolkit dependency can be resolved.

## Current Implementation

- `app/src/main/java/com/example/smartglassesagents/dat/DatSessionController.kt` defines the app-facing DAT contract.
- `MockDatSessionController.kt` simulates registration, device discovery, camera permission, session start/stop, and frame capture.
- The Android UI can capture a generated mock Ray-Ban Meta frame and send it to the GB10 host with `capture_metadata.source = mock`.
- `AndroidManifest.xml` includes DAT metadata placeholders and opts out of DAT analytics.

## Real SDK Wiring

The local `../meta-wearables-dat-android` checkout shows that DAT 0.3.0 is distributed through GitHub Packages. To enable the real SDK:

1. Add a classic GitHub token with `read:packages` scope as either:
   - environment variable `GITHUB_TOKEN`, or
   - `github_token=<token>` in `local.properties`.
2. Replace `@string/meta_wearables_application_id` with the application ID from the Wearables Developer Center.
3. Add the GitHub Packages Maven repository to `settings.gradle.kts`.
4. Add dependencies:

```kotlin
implementation(libs.mwdat.core)
implementation(libs.mwdat.camera)
implementation(libs.mwdat.mockdevice)
```

5. Implement a production `RealDatSessionController` behind the existing `DatSessionController` interface.

The sample app pattern from the local DAT checkout uses:

- `Wearables.startRegistration(context)`
- `Wearables.registrationState`
- `Wearables.devices`
- `Wearables.checkPermissionStatus(Permission.CAMERA)`
- `Wearables.startStreamSession(context, deviceSelector, StreamConfiguration(...))`
- `StreamSession.videoStream`
- `StreamSession.capturePhoto()`

Keep the Android UI and GB10 request schema unchanged when replacing the mock controller.
