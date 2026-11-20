# Panels Assistive v0.2.8

## Completed in this revision
- Added a dedicated 1:1 Floating Bubble image/GIF crop editor.
- Crop editor supports drag position, zoom, and corner rounding.
- Static multi-image selections share the saved crop transform so slideshow does not create duplicate re-encoded files.
- GIFs are NOT re-encoded: source URI + crop metadata are stored, preserving animation and reducing storage/CPU work.
- Float Ball renderer applies the crop transform to both static images and animated GIF frames.
- Moved “Hiện nhãn cạnh biểu tượng ghi âm” into the “Ghi âm nhanh” section because it controls recording UI.
- Preserved the existing battery-saving GIF/slideshow defaults and lifecycle behavior.

## Files changed
- app/src/main/java/com/manh/panelsassistive/FloatBallIconCropActivity.kt
- app/src/main/java/com/manh/panelsassistive/AssistiveCore.kt
- app/src/main/java/com/manh/panelsassistive/MainActivity.kt
- app/src/main/java/com/manh/panelsassistive/SettingsScreen.kt
- app/src/main/java/com/manh/panelsassistive/FloatBallService.kt
- app/src/main/AndroidManifest.xml
- app/src/main/res/values/strings.xml
- app/src/main/res/values-vi/strings.xml
- app/build.gradle.kts

## Build/test status
- Source ZIP structure preserved from v0.2.7 except for the new crop editor and metadata changes.
- Local Gradle build was not verified in this environment because the Gradle distribution download is unavailable offline.
- GitHub Actions should be used as the authoritative build check.

## Important behavior
- Animation remains OFF by default.
- GIF OFF = first-frame static display with no animation runner.
- GIF crop uses metadata rather than generating a new animated GIF.
- Screen-off and lifecycle shutdown still stop animation/timers.

## Next
- Add per-image crop editing for each image in a multi-image slideshow if desired.
- Continue Widget Engine: Music, Volume, Brightness and shared widget theme/color system.


## Build correction (2026-11-20)
- Fixed missing helper methods referenced by popup editing controls in `FloatBallService.kt`:
  - service-level `dp(Int)` conversion;
  - `roundedBackground(color, radiusDp)`;
  - `usesLandscapeLayout()` using the saved landscape-layout preference and current orientation;
  - `showOverlayDialog(AlertDialog.Builder)` for overlay dialogs;
  - `openWidgetPicker(type, position)` with the picker activity extras.
- Added the missing `android.net.Uri` import in `MainActivity.kt`.
- These changes address the reported unresolved references (`dp`, `roundedBackground`, `usesLandscapeLayout`, `showOverlayDialog`, `openWidgetPicker`, and `Uri`) and the cascading `FrameLayout.LayoutParams` type-inference errors.
- Build status: attempted `./gradlew :app:compileDebugKotlin --no-daemon`; Gradle 8.9 could not be downloaded because this environment has no DNS/network access to `services.gradle.org`. Therefore compilation remains unverified here. Run GitHub Actions to confirm.
