# Ray-Ban Meta DAT Acceptance Checks

Use these checks for the `realDatDebug` build on a physical Android phone paired to the Ray-Ban Meta glasses.

## Build And Install

- Add `GITHUB_TOKEN` or `github_token` for GitHub Packages.
- Add `GITHUB_ACTOR` or `github_username` only if the default GitHub Packages username does not work.
- Add `META_WEARABLES_APPLICATION_ID` or `meta_wearables_application_id` from the Meta Wearables Developer Center.
- Run `.\scripts\check-real-dat-setup.ps1`.
- Build `:app:assembleRealDatDebug`.
- Install the generated real DAT debug APK on the phone.
- Confirm the DAT panel says `Build: real`.

## Device Session

- Confirm the glasses are paired and connected in the Meta AI app.
- Open Smart Glasses Agents.
- Tap `Register` and complete Meta Wearables registration.
- Confirm registration changes to `Registered`.
- Confirm the active device shows the Ray-Ban Meta glasses.
- Tap `Grant camera` and confirm camera permission changes to `Granted`.
- Tap `Start session` and confirm session changes to `Running`.
- Tap `Stop` and confirm the session returns to `Stopped`.

## Capture To GB10

- Start the GB10 host on the same Wi-Fi network.
- In Android, set the GB10 host URL to the desktop Wi-Fi IP, not `10.0.2.2`.
- Tap `Check host` and confirm model profiles load.
- Select exactly one model profile.
- Start a DAT session and tap `Capture DAT`.
- Confirm the preview updates and capture source reads `Ray-Ban Meta DAT`.
- Send to GB10 and confirm a spoken response is returned.
- On GB10, inspect `/experiment_runs` and confirm `capture_source` is `rayban_meta_dat`.

## Fallbacks

- Stop the DAT session and confirm `Capture DAT` is disabled.
- Capture with the phone camera and confirm `capture_source` is `phone_camera`.
- Run sampled live mode from a DAT session and confirm only one request is in flight.
- Run sampled live mode from the latest still image and confirm it does not queue unbounded requests.

## Audio

- Pair the phone audio output to the Ray-Ban Meta glasses.
- Keep `Force Bluetooth call route` off first and confirm TTS uses the normal media route.
- Only enable the forced route if Android does not send TTS to the glasses.
- Confirm `Stop` interrupts speech and `Replay` repeats only the latest `speech_text`.

## Privacy

- Confirm face tasks report only count and approximate location.
- Confirm raw image storage on GB10 is off unless `GB10_STORE_RAW_IMAGES=1` is explicitly set.
- Confirm no provider token or DAT token is stored in Android source.
