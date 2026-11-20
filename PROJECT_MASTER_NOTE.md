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


## 2026-11-20 — v28.2 repair of missing FloatBallService panel methods and default visual mode

### Changes
- Restored the missing outer-service panel/Popup implementation methods in `FloatBallService.kt` from the earlier intact project source: `createPanelShortcut`, `launchPanelItem`, `scheduleCloseAfterTap`, `panelSwipeListener`, `changePanel`, `popupIconButton`, `popupActionButton`, `roundedControlBackground`, `frostedPanelBackground`, `selectedCellWidth`, `showOverlayPanel`, `applyPopupBlur`, `removePopup`, `keepFloatBallAvailable`, `bringFloatBallToFront`, `removeFloatBall`, `scheduleIdleDim`, and `overlayWindowType`. This restores real Popup lifecycle, gestures, panel navigation, shortcut rendering, and overlay presentation rather than adding placeholder methods.
- Kept the v28 image/GIF crop and battery-saving implementation intact.
- Changed `AppPrefs.popupBlurEnabled()` default from `true` to `false`. The default is now the static translucent/fake-glass panel without system background blur.
- When optional blur is enabled, existing settings for blur scope and radius/intensity remain available; dependent controls are hidden when blur is disabled.
- Reduced no-blur background dimming to 18%. Android versions below 12 continue using the static background because cross-window blur is guarded by the SDK check.

### Files changed
- `app/src/main/java/com/manh/panelsassistive/FloatBallService.kt`
- `app/src/main/java/com/manh/panelsassistive/AssistiveCore.kt`

### Build and test status
- Source restoration completed; ZIP integrity checked.
- Local Gradle build remains **not verified**: Gradle 8.9 wrapper download failed because this environment cannot resolve `services.gradle.org` (`UnknownHostException`); no system Gradle executable is installed.
- GitHub Actions must run `:app:compileDebugKotlin` / `assembleDebug` to confirm compilation.

### Next steps
1. Upload/overwrite the repository with this complete ZIP.
2. Run GitHub Actions and inspect the Kotlin compile stage.
3. On device, verify Popup open/close, swipe between panels, shortcut/app launch, idle dim, and static no-blur appearance.
4. Enable optional blur on a supported Android 12+ device and verify blur controls appear and blur can be disabled again.

## 2026-11-20 — v28.3 follow-up Kotlin compile repair

### Reported GitHub Actions errors addressed
- Added the missing screen-position helpers in `FloatBallService.kt`: `maxX`, `safeTop`, `maxY`, `clampPosition`, `persistPosition`, and `applyEdgeHide`.
- Corrected `selectedCellWidth()` to return the selected page's `cellWidthDp` (with a safe 64dp fallback), rather than returning a nullable `WidgetPanelPage`.
- Completed `overlayWindowType()` with the pre-Android-O `TYPE_PHONE` fallback, fixing the incomplete `if` expression.
- Confirmed that `panelSwipeListener`, `createPanelShortcut`, `removePopup`, and `scheduleIdleDim` are present as actual service members in this source. The previous diagnostics were consistent with parser/type errors around the incomplete helpers and malformed expressions, not missing stubs for these methods.

### Behavior notes
- Float Ball position is normalized and persisted against the usable screen area; status-bar height is accounted for vertically.
- Optional edge hiding moves most of the Float Ball beyond the nearest horizontal edge while leaving a small visible grip.
- Drag clamping keeps the Float Ball within the usable screen area before optional edge hiding is applied.

### Build/test status
- ZIP integrity is checked after packaging.
- Compilation is still not confirmed in this environment unless the Gradle wrapper can run with a locally available Gradle distribution. GitHub Actions remains the authoritative compile check.
