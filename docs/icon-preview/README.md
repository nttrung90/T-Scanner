# Launcher icon

The Android 26+ adaptive resources use the same vector foreground and background
for both `ic_launcher` and `ic_launcher_round`. The launcher supplies the mask.
The app currently has minSdk 26; the existing legacy PNGs are not the adaptive
foreground and were left unchanged.

- Canvas: 108 x 108 dp; full-bleed blue gradient background.
- Foreground: document, T, camera and PDF badge; no baked-in border or mask.
- Foreground sampled at 10 pixels/dp: maximum nontransparent radius 32.88 dp
  from (54, 54), within the 33 dp safe radius (alpha threshold 8/255).
- `launcher-shapes.png` / `.svg`: static circle and rounded-square previews,
  using paths from the foreground XML and the same gradient endpoints.
- `foreground.svg`: vector inspection copy. Android XML is the source of truth.

These previews do not replace checking the installed app on device launchers.
Reference: https://developer.android.com/codelabs/basic-android-kotlin-compose-training-change-app-icon
