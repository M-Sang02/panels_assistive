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


## 2026-11-20 — v28.7 incremental interaction repair (based on v28.5)

### Changes completed
- Volume sliders now debounce system volume writes by 500 ms. Each drag update replaces the pending write for that stream; the final value is applied only after movement has paused for half a second. This applies while the overlay service is running, regardless of whether the Panels Assistive settings activity is foregrounded.
- Pending volume callbacks are cancelled and cleared when the service is destroyed.
- Edge-hide positioning now places 50% of the Float Ball outside the left/right display edge, leaving half of the bubble visible as the touch target.

### Files changed
- `app/src/main/java/com/manh/panelsassistive/FloatBallService.kt`
- `PROJECT_MASTER_NOTE.md`

### Not yet implemented in this incremental revision
- One-second post-release Float Ball reveal followed by a gradual return animation.
- Center snap-point behavior and the dedicated Left / Center / Right position subpage.
- Shared “Nền bảng” settings with per-property utility-panel overrides.
- Correct view-local `BackgroundBlurDrawable` implementation for panel-only blur; current panel-only path still uses the window blur API and must be replaced.
- Full settings hierarchy/subpage reorganization and layout spacing pass.
- Independent brightness configuration page and the final vertical slider/panel layout refinements.

### Verification
- Source edits are limited to the volume update scheduling and edge-hide offset; ZIP integrity will be checked after packaging.
- Gradle compilation is **not verified** in this environment. Previous attempts could not download Gradle 8.9 because `services.gradle.org` was unreachable. Run GitHub Actions `assembleDebug` before installing.

### Next implementation order
1. Implement shared background preference model and utility-panel per-field overrides with migration-safe defaults.
2. Replace panel-only blur with an API 31+ view-background blur drawable, retaining a safe static-background fallback.
3. Add Float Ball reveal/return animation and center snap behavior.
4. Reorganize settings into dedicated subpages and finish spacing/slider layout refinements.


## 2026-11-20 — v28.8 follow-up: expansion layout and shared panel background entry

### User-reported issues addressed in source
- Added an explicit collapsible “Âm lượng / Media” section in the Expansion Panel. Its four volume streams can be hidden/shown independently of the brightness row.
- Wrapped the Expansion Panel content in a ScrollView and increased its available height cap so the shortcut row is reachable rather than being clipped by a fixed 390dp dialog height on shorter screens.
- Added a dedicated “Nền bảng” entry to the main Settings screen and a separate BackgroundSettingsActivity.
- Added shared preferences for panel base color, opacity, and corner radius; the Expansion Panel now reads these shared values. Blur enabled/scope/radius use the existing popup preferences and are editable from the new page.

### Files changed
- app/src/main/java/com/manh/panelsassistive/FloatBallService.kt
- app/src/main/java/com/manh/panelsassistive/AssistiveCore.kt
- app/src/main/java/com/manh/panelsassistive/SettingsScreen.kt
- app/src/main/java/com/manh/panelsassistive/BackgroundSettingsActivity.kt (new)
- app/src/main/AndroidManifest.xml
- app/src/main/res/values/strings.xml

### Known incomplete items / verification
- The utility-panel per-field inheritance/override model is not implemented yet.
- The panel-only blur path still uses Window.setBackgroundBlurRadius and has not yet been replaced with a view-attached BackgroundBlurDrawable; do not claim panel-only blur is fixed.
- The shared color/opacity/corner values are currently applied to the Expansion Panel; other popup surfaces still need migration to the shared defaults.
- The collapse control currently collapses the four volume streams; the shortcut row remains visible.
- Static source review and ZIP integrity checks are required. Gradle compilation/device testing are not verified in this environment.


## v28.9 – Slider redesign and Kotlin compile fix (2026-11-20)

### Updated
- Replaced the platform `SeekBar` in the Expansion Panel and compact brightness popup with one custom-drawn slider component (`SliderTrackView`). The new control uses a consistent rounded track, blue active segment, centered white thumb, and direct touch-to-value mapping.
- Removed the percentage text from slider rows to keep the control uncluttered. Brightness still calls `updateBrightness`; audio rows still use the existing `scheduleStreamVolume` debounce callback.
- Reworked slider row surfaces to a subtler translucent rounded style.
- Fixed the reported Kotlin error around `ScrollView.LayoutParams` by using `ViewGroup.LayoutParams` when adding the panel to the ScrollView.

### Validation
- Source-level edits applied to `app/src/main/java/com/manh/panelsassistive/FloatBallService.kt`.
- ZIP integrity will be checked before delivery. Android/Gradle compilation and on-device visual/touch testing have **not** been verified in this environment.

### Remaining checks
- Run the GitHub Actions Android build and share any new compiler output if it fails.
- Test thumb positioning, drag responsiveness, volume update timing, brightness permission flow, and shortcut visibility on-device.
- Confirm whether the redesigned slider appearance matches the requested reference; further visual tuning may be needed after a device screenshot.

## v28.10 — Modern action glyphs, shortcut-only collapse, standalone vertical brightness

### Changes
- Replaced the Expansion Panel's collapsible “Âm lượng / Media” header. All four volume rows (media, ring, notification, alarm) now remain visible; there is no media-collapse control.
- Added a centered minimal chevron toggle that collapses/expands only the shortcut tile row. The chevron direction changes with the state.
- Removed the brightness row from the Expansion Panel. The dedicated Brightness Slider action now opens its own narrow vertical control with only a brightness icon and a draggable vertical track; it does not include volume controls or shortcuts.
- Refined the action glyph drawing style toward consistent, thin, rounded, minimal line icons. Updated flashlight, Home, and Recents glyph geometry and reduced stroke weight across the action glyph set and popup icons.
- Kept existing brightness permission handling and system brightness writes, plus existing volume stream debounce behavior.

### Files changed
- `app/src/main/java/com/manh/panelsassistive/FloatBallService.kt`
- `app/src/main/java/com/manh/panelsassistive/ActionGlyphView.kt`

### Verification
- Source checks: confirmed the Expansion Panel no longer contains a brightness row or a media-collapse header; shortcut visibility is controlled by the centered chevron; the standalone brightness action uses a dedicated vertical slider view.
- ZIP integrity: checked after packaging.
- Gradle compilation is **not verified**. `./gradlew --offline --no-daemon :app:compileDebugKotlin` still attempted to download Gradle 8.9 and failed because `services.gradle.org` could not be resolved (`UnknownHostException`). Run GitHub Actions to verify Kotlin compilation and APK packaging.

### Device checks
1. Open Expansion Panel: confirm all volume rows stay visible.
2. Tap the centered chevron: confirm only shortcut tiles hide/show.
3. Open the Brightness Slider action: confirm it opens a separate vertical brightness-only control and dragging changes system brightness (after granting WRITE_SETTINGS if requested).
4. Review action icon appearance and report any specific glyphs that still look outdated.
