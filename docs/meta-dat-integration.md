# Meta Wearables DAT Integration Notes

This repo has two Android DAT build flavors:

- `mockDatDebug`: default development path, no Meta SDK credentials required.
- `realDatDebug`: real Meta Wearables Device Access Toolkit path for paired Ray-Ban Meta glasses.

## Current Implementation

- `app/src/main/java/com/example/smartglassesagents/dat/DatSessionController.kt` defines the app-facing DAT contract.
- `MockDatSessionController.kt` simulates registration, device discovery, camera permission, session start/stop, and frame capture for `mockDat`.
- `app/src/realDat/java/.../RealDatSessionController.kt` initializes DAT, monitors registration/devices, requests DAT camera permission, starts a camera stream session, receives video frames, and captures photos.
- `app/src/realDat/java/.../RealDatPermissionBridge.kt` wraps `Wearables.RequestPermissionContract()`.
- The Android UI captures through DAT when a session is running, falls back to the phone camera, sends the image to GB10, and tags capture source as `rayban_meta_dat`, `phone_camera`, or `mock`.
- `AndroidManifest.xml` includes DAT metadata and opts out of DAT analytics.

## Credentials

DAT 0.3.0 is distributed through GitHub Packages. The real flavor needs a classic GitHub token with `read:packages` scope:

- Environment variable: `GITHUB_TOKEN`
- Or local secret: `github_token=<token>` in `local.properties`
- Optional username override: `GITHUB_ACTOR` or `github_username=<username>` in `local.properties`

The Meta Wearables application ID is injected through the manifest placeholder:

- Environment variable: `META_WEARABLES_APPLICATION_ID`
- Or local secret: `meta_wearables_application_id=<id>` in `local.properties`

Do not commit either value.

Start from the template:

```powershell
Copy-Item local.properties.example local.properties
notepad local.properties
```

Then run the preflight:

```powershell
.\scripts\check-real-dat-setup.ps1
```

The real DAT Gradle tasks also fail early when these values are missing.

## Meta AI Glasses Setup

1. Pair the Ray-Ban Meta glasses in the Meta AI app on the same Android phone.
2. Create or select the app entry in the Meta Wearables Developer Center.
3. Put that app ID in `META_WEARABLES_APPLICATION_ID` or `local.properties` as `meta_wearables_application_id`.
4. Build and install `realDatDebug`.
5. Open Smart Glasses Agents and check the DAT panel says `Build: real`.
6. Tap `Register` and complete the Meta Wearables registration flow.
7. Wait for the active device to show the glasses.
8. Tap `Grant camera`, then `Start session`, then `Capture DAT`.

## Build Commands

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat --gradle-user-home .gradle-user-home :app:assembleMockDatDebug
.\gradlew.bat --gradle-user-home .gradle-user-home :app:assembleRealDatDebug
```

The preflight script can run the real build after checking setup:

```powershell
.\scripts\check-real-dat-setup.ps1 -Build
```

`assembleRealDatDebug` will fail until GitHub Packages credentials can resolve the Meta artifacts.

## Real SDK Pattern

The sample app pattern from the local DAT checkout uses:

- `Wearables.startRegistration(context)`
- `Wearables.registrationState`
- `Wearables.devices`
- `Wearables.checkPermissionStatus(Permission.CAMERA)`
- `Wearables.startStreamSession(context, deviceSelector, StreamConfiguration(...))`
- `StreamSession.videoStream`
- `StreamSession.capturePhoto()`

Keep the Android UI and GB10 request schema unchanged when iterating on the real controller.
