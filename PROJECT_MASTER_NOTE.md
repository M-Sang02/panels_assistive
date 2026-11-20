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


## v28.4 Kotlin compile-error repair — 2026-11-20

### GitHub Actions errors addressed
- `FloatBallIconCropActivity.kt`: replaced the nonexistent `AppPrefs.floatBallCropCorner(...)` call with the existing `AppPrefs.floatBallCrop(...).corner` value.
- `FloatBallService.kt`: restored the missing Expansion Panel UI using the four persisted `AppPrefs.expansionShortcuts` slots. Empty slots open `WidgetPickerActivity` with `EXTRA_EXPANSION_SLOT`; assigned actions, apps, and shortcuts dispatch through `ActionDispatcher`; long-press clears an assigned slot.
- `FloatBallService.kt`: restored the missing brightness slider dialog. It reads/writes `Settings.System.SCREEN_BRIGHTNESS`; if WRITE_SETTINGS permission is absent, it presents the existing localized permission explanation and opens Android's per-app write-settings screen.
- `FloatBallService.kt`: fixed flashlight dispatch to call `FloatBallService.toggleFlashlight(context)` with its required context.
- `FloatBallService.kt`: replaced unresolved `refreshTile()` calls with the existing `QuickSettingsTiles.updateListeningServices(context)` helper.

### Files changed
- `app/src/main/java/com/manh/panelsassistive/FloatBallIconCropActivity.kt`
- `app/src/main/java/com/manh/panelsassistive/FloatBallService.kt`
- `PROJECT_MASTER_NOTE.md`

### Verification status
- Source-level checks: confirmed the crop preference API, expansion shortcut data model, WidgetPicker extras, ActionDispatcher encoding/dispatch, brightness permission strings, and QuickSettingsTiles helper exist in this project.
- ZIP integrity: to be checked after packaging.
- Gradle build: **not verified**. `./gradlew --no-daemon assembleDebug` was attempted, but Gradle 8.9 could not be downloaded because this environment cannot resolve `services.gradle.org` (`UnknownHostException`). GitHub Actions must run the actual compile.

### Next steps
1. Upload all files from the v28.4 source ZIP over the current repository.
2. Run GitHub Actions `assembleDebug`.
3. If compilation succeeds, test expansion shortcut add/launch/clear, brightness permission flow and brightness adjustment, flashlight action, and crop-corner persistence on a device.


## 2026-11-20 — v28.5 restore/redesign of brightness and expansion controls

### User reference and design direction
- Reviewed the supplied `Panels-Assistive-v26-thin-border-source.zip` and the supplied `80283.mp4`.
- The video shows a compact rounded volume-widget capsule with a central level indicator and +/- controls. The requested adaptation is a true draggable slider instead of +/- buttons, with an icon instead of the word label.
- The previous v28.4 brightness popup and expansion panel had drifted from the earlier control design. They are replaced with a consistent icon-first slider treatment.

### Changes
- `showBrightnessSlider()` now opens a compact horizontal pill: brightness icon, real SeekBar, and numeric percentage. No +/- buttons and no text label.
- `showExpansionPanel()` now uses the same rounded pill style for brightness, media, ringtone, notification, and alarm levels.
- Each slider row uses its matching icon, an actual SeekBar, and a percentage readout; labels are omitted from the visual UI.
- Existing system volume streams are updated through `AudioManager.setStreamVolume`.
- Brightness updates retain the Android `WRITE_SETTINGS` permission flow and manual brightness-mode handling.
- Existing expansion shortcut slots are retained as icon-only tiles; tap an empty tile to add a shortcut and long-press an occupied tile to remove it.
- Default panels use a static translucent gradient/background and do not require Android cross-window blur support. The existing optional blur preference remains responsible for applying blur when enabled.
- The crop editor, image/GIF metadata, battery-saving behavior, and other v28.4 project files are preserved.

### Files changed
- `app/src/main/java/com/manh/panelsassistive/FloatBallService.kt`
- `PROJECT_MASTER_NOTE.md`

### Build and test status
- ZIP structure and CRC integrity checked after packaging.
- Attempted `./gradlew --offline --no-daemon assembleDebug`; wrapper still attempted to download Gradle 8.9 and failed with `UnknownHostException: services.gradle.org`.
- Therefore, **no successful local compilation is claimed**. GitHub Actions remains the authoritative compile/build check.

### Device verification checklist
1. Open Brightness Slider and verify the icon + slider + percentage capsule.
2. Drag the slider and verify brightness changes after granting WRITE_SETTINGS.
3. Open Expansion Panel and verify five icon-only slider rows update their corresponding system levels.
4. Verify the shortcut tiles can launch, add, and remove shortcuts.
5. Test with blur disabled on a device without cross-window blur support; the panel should still open using the static background.
