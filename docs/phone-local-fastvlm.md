# Phone-local FastVLM deployment

The `phoneLocal` inference flavor runs `FastVLM-1.5B-Stage3-MNN` entirely on an
arm64 Android device. It does not call the GB10 API. Google Play supplies the
model once as the `fastvlm_model` fast-follow asset pack; after that pack is
installed, image analysis works without an inference server.

## Prepare and build

The model source directory is intentionally excluded from Git. Prepare the
asset pack and build the Android App Bundle with:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\prepare-fastvlm-assets.ps1
.\gradlew.bat :app:bundleRealDatPhoneLocalRelease
```

Use `bundleMockDatPhoneLocalRelease` when testing without physical glasses.
Asset packs are available when the app is installed from an app bundle through
Google Play or `bundletool`; a standalone APK does not deliver fast-follow
assets.

For local device testing, connect the phone over ADB and run:

```powershell
java -jar .\bundletool-all-1.18.3.jar build-apks `
  --bundle=.\app\build\outputs\bundle\mockDatPhoneLocalRelease\app-mockDat-phoneLocal-release.aab `
  --output=.\app\build\outputs\fastvlm-local.apks `
  --connected-device --local-testing --overwrite `
  --adb=D:\Ray\Android\sdk\platform-tools\adb.exe

java -jar .\bundletool-all-1.18.3.jar install-apks `
  --apks=.\app\build\outputs\fastvlm-local.apks `
  --adb=D:\Ray\Android\sdk\platform-tools\adb.exe

& D:\Ray\Android\sdk\platform-tools\adb.exe shell am start `
  -n com.example.smartglassesagents/.MainActivity
```

Do not press Android Studio's Run button after this install. Run installs a
standalone APK over the bundletool build, removes the `local_testing_dir`
manifest marker, and causes Play Asset Delivery to contact the real Play Store.
Launch the installed app from the phone or with the ADB command above.

The current model pack is about 1.23 GB before Play compression. The generated
mock release AAB is about 1.06 GB, below Google Play's 1.5 GB per-asset-pack
limit. Users may need to approve a download this large when they are not on
Wi-Fi.

## Runtime behavior

- `phoneLocal` is arm64-only and requires Android 8.0 (API 26) or newer.
- The app checks the asset-pack location on each use because Play may move or
  invalidate fast-follow assets during updates.
- One inference runs at a time. A second request receives a busy error rather
  than being queued.
- Captured inference images are temporary cache files and are deleted after the
  model returns. The local JSONL run log contains metadata only.
- The MNN native libraries come from the official MNN Chat 0.5.0 release that
  introduced FastVLM support, and
  are isolated to the `phoneLocal` flavor.

Before publishing, configure a production application ID, release signing, the
Meta Wearables credentials required by `realDat`, Play Console declarations,
and physical-device testing on every supported device class.
