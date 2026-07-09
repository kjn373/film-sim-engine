# B7 — Device Profile Calibration Shoot

Status: software complete (`tooling/profile-calibrator`, camera-core capture/harvest,
CAL mode in the app). The physical shoot is blocked on materials — see below.

Schema and methodology are specified in `ARCHITECTURE.md` §16; this doc is the
operational checklist for actually producing a `deviceprofile.json` for a
reference device.

## What's needed

- A physical reference Android device with RAW capture support (the app's
  **CAL** toggle only appears when the device exposes it).
- A ColorChecker / X-Rite 24-patch chart — required for steps 2 and 3.
- A tungsten/incandescent lamp (~2700–3000K bulb, not LED "warm white") —
  required for step 3.
- Dark frames (step 1) and the flat field (step 4) need no special
  equipment and can be shot at any time.

## Procedure

0. Install the APK, grant camera permission, switch exposure mode to **M**
   (manual) — AE would otherwise clip the chart or drift between shots. If no
   **CAL** toggle appears next to RAW, this device doesn't support RAW
   capture and B7 can't run on it at all.

1. **Dark frames** (black levels + noise ladder):
   - Cap the lens completely (lens cap or a hand held flat over it, opaque
     and light-tight).
   - Tap **CAL** until it shows **DARK**.
   - Shoot at 4–5 ISO points spanning the device's full range (e.g. min,
     ~400, ~800, ~1600, max), tapping the shutter once at each. Keep the
     lens capped throughout.

2. **ColorChecker under daylight**:
   - Outside or next to a window in direct daylight — no glare, no shadow
     across the chart.
   - Tap **CAL** until it shows **CHART☀**.
   - Fill as much of the frame as possible with the chart, held flat and
     roughly perpendicular to the lens.
   - Tap the shutter. One good frame is enough; a second doesn't hurt.

3. **ColorChecker under tungsten**:
   - Same framing as step 2, lit only by the tungsten lamp.
   - Tap **CAL** until it shows **CHART💡**.
   - Tap the shutter.

4. **Flat field** (vignetting):
   - Point the lens at an evenly lit, texture-less white/grey surface (a
     wall, a diffuser, a sheet of paper), close enough to fill the frame,
     slightly out of focus so no texture shows.
   - Tap **CAL** until it shows **FLAT**.
   - Tap the shutter.

5. **Hand it back**: leave the phone connected via USB with debugging on.
   From the dev machine:
   ```
   adb pull /sdcard/Android/data/app.filmengine.camera/files/calibration_report.json .
   ./gradlew :tooling:profile-calibrator:run --args="calibration_report.json deviceprofile.json"
   adb push deviceprofile.json /sdcard/Android/data/app.filmengine.camera/files/
   ```

⚠ Exposure check for steps 2–3: don't let the chart's white patch clip or
its black patch crush — use the histogram/zebra overlay if the build has one
enabled.

## Current blocker

No ColorChecker chart or tungsten lamp on hand yet, so steps 2–3 are on
hold. Steps 1 and 4 (dark frames, flat field) have no such dependency and
can be shot at any time.
