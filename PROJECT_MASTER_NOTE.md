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

## 2026-11-20 — v28.11 XGesture icon library and panel restoration

### User-requested changes
- Replace the legacy hand-drawn action glyphs with the Lucide outline icon library used in XGesture 1.66.0's icon library.
- Restore brightness control in the Expansion Panel.
- Fix the standalone vertical brightness panel so the top brightness icon is not clipped.
- Make Expansion Panel and standalone brightness panel use the shared “Nền bảng” preferences.

### Changes in this source
- Added `LucideGlyphView.kt`, a small runtime renderer that resolves icon names through `lucide_icons.tsv` and draws glyphs using the Lucide font.
- Added the Lucide font resource and icon-name/codepoint catalog from XGesture's packaged icon library. The font/catalog are attributed in `THIRD_PARTY_NOTICES.md` under the ISC license; no XGesture application implementation files were copied.
- Replaced `ActionGlyphView` hand-drawn paths with Lucide outline glyphs.
- Replaced the local popup control glyph renderer with Lucide glyphs, including chevrons for shortcut collapse/expand.
- Rebuilt the Expansion Panel's level-control section in the XGesture five-column vertical-slider arrangement: brightness, media, ringtone, notifications, and alarm. Each column has a Lucide icon, a vertical draggable track, and a compact label.
- Added `sharedPanelBackground()` and applied the shared background color, opacity, corner radius, and border to both the Expansion Panel and standalone brightness panel. Existing popup blur preferences continue to be applied by the shared overlay-window path.
- Increased the standalone brightness panel's available height and adjusted its top padding/icon size so the brightness icon and slider fit without clipping.

### Files changed
- `app/src/main/java/com/manh/panelsassistive/ActionGlyphView.kt`
- `app/src/main/java/com/manh/panelsassistive/LucideGlyphView.kt` (new)
- `app/src/main/java/com/manh/panelsassistive/FloatBallService.kt`
- `app/src/main/res/font/lucide.ttf` (new)
- `app/src/main/assets/lucide_icons.tsv` (new)
- `THIRD_PARTY_NOTICES.md`

### Verification and remaining work
- Verified every icon name referenced by `ActionGlyphView` and the popup icon mapping exists in the bundled Lucide catalog.
- Source ZIP integrity is checked after packaging.
- Gradle compilation and on-device visual/interaction tests are not verified in this environment. Run GitHub Actions `assembleDebug` and test both panels on-device.
- The Expansion Panel now uses the five-column vertical slider layout based on XGesture's `ExpandPanelSlidersSection` structure. This is a UI reimplementation in the existing Android View project, not a direct copy of XGesture's Compose application code.

## 2026-11-20 — v28.12 Unified panel appearance and working flashlight toggle

### User-requested changes
- The “Nền bảng” page and the widget panel (“Bảng tiện ích”) appearance settings diverged; synchronize them.
- The flashlight quick action did not turn the torch on.

### Changes in this source
- Made `AppPrefs.sharedPanelColor/Opacity/Corner` the single source of truth for every overlay panel: the widget popup now uses `sharedPanelBackground()` exactly like the Expansion Panel and standalone brightness panel (previously the popup used per-page color/opacity with a different corner radius).
- Moved the appearance controls to one place. `BackgroundSettingsActivity` (“Nền bảng”) owns the palette (`AppPrefs.panelPalette`), a custom HSV color picker, opacity, corner radius, and blur (scope + intensity), with a live preview that mirrors the service styling (1 dp stroke `argb(65,225,238,250)`).
- Removed the duplicated blur switch/scope/intensity, opacity slider, and color palette from `WidgetPanelSettingsActivity`; its card now shows a note plus a “Cài đặt Nền bảng” button, and its preview renders with the shared color/opacity/corner.
- Added `OverlaySurface` tracking (widgets/expansion/brightness) in `FloatBallService` so `ACTION_UPDATE_POPUP_APPEARANCE` rebuilds the currently open surface and re-applies its window background immediately.
- Flashlight: `ACTION_TOGGLE_FLASHLIGHT` previously re-sent its own intent (infinite loop, torch never toggled). It now calls `performFlashlightToggle()`, which uses `CameraManager.setTorchMode` on the back camera with a flash unit, persists state in `AppPrefs.flashlightEnabled`, and observes real torch state via a `CameraManager.TorchCallback` registered in `onCreate` / unregistered in `onDestroy`. When the CAMERA permission is missing it opens `MainActivity` with `EXTRA_REQUEST_CAMERA_PERMISSION` + `EXTRA_RUN_TORCH_AFTER_PERMISSION`, and the existing permission flow toggles the torch after the grant.

### Files changed
- `app/src/main/java/com/manh/panelsassistive/FloatBallService.kt`
- `app/src/main/java/com/manh/panelsassistive/BackgroundSettingsActivity.kt`
- `app/src/main/java/com/manh/panelsassistive/WidgetPanelSettingsActivity.kt`
- `app/src/main/java/com/manh/panelsassistive/AssistiveCore.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-vi/strings.xml`

### Verification and remaining work
- Gradle compilation and on-device tests are not verified in this environment. Run GitHub Actions `assembleDebug`, then on-device: change color/opacity/corner/blur in “Nền bảng” and confirm the widget popup, Expansion Panel, and brightness panel all update; invoke the flashlight action (first time should prompt for camera permission) and confirm the torch turns on and off.

## 2026-11-20 — v28.13 XGesture-style faux-glass settings UI

### User-requested changes
- Make the interface more professional, modelled on XGesture (the project follows XGesture).
- Stability first: the default visual is faux glass; blur remains an optional enhancement.

### Changes in this source
- Added `GlassUi.kt`, a shared design kit adapted from XGesture's capsule design tokens: navy page gradient `#091422 → #0D1E30 → #07101D`, blue accent `#7CBEFF`, 16/18 dp page padding, 16 dp card padding, 14 dp pill buttons, 1 dp light rim, icon tiles with bundled Lucide glyphs.
- Faux glass is implemented with static `LayerDrawable`s (1 dp `argb(36,255,255,255)` stroke + vertical gradient `argb(32→13)` fill). No runtime blur is used by default; the existing blur switch in "Nền bảng" stays as an optional enhancement applied by `FloatBallService`.
- Restyled all settings screens with the kit: main settings (`SettingsScreen.kt`), widget panel editor (`WidgetPanelSettingsActivity.kt`), "Nền bảng" (`BackgroundSettingsActivity.kt`), widget picker (`WidgetPickerActivity.kt`), float icon crop editor (`FloatBallIconCropActivity.kt`).
- Edge-hide/orientation selectors in settings are now segmented pill tabs; selected tab text uses the accent color. Sliders use the accent track/thumb; switches use white thumb on a blue tinted track.
- `MainActivity` and activity system bars use the shared `PAGE_COLOR`; `colors.xml` updated to the new palette.
- Blur controls in "Nền bảng" are grouped in the blur card and only visible while the optional blur switch is on; the live preview mirrors `sharedPanelBackground()` styling.

### Files changed
- `app/src/main/java/com/manh/panelsassistive/GlassUi.kt` (new)
- `app/src/main/java/com/manh/panelsassistive/SettingsScreen.kt`
- `app/src/main/java/com/manh/panelsassistive/WidgetPanelSettingsActivity.kt`
- `app/src/main/java/com/manh/panelsassistive/BackgroundSettingsActivity.kt`
- `app/src/main/java/com/manh/panelsassistive/WidgetPickerActivity.kt`
- `app/src/main/java/com/manh/panelsassistive/FloatBallIconCropActivity.kt`
- `app/src/main/java/com/manh/panelsassistive/MainActivity.kt`
- `app/src/main/res/values/colors.xml`

### Verification and remaining work
- Static review only; Gradle compilation and on-device visual tests are not possible in this environment. Run GitHub Actions `assembleDebug` and check the main settings, "Bảng tiện ích", "Nền bảng", widget picker, and crop screens; verify blur remains optional and panels are unaffected in their default faux-glass state.

## 2026-11-20 — v28.14 Compact settings menu, per-panel background, edge hide v2, effects screen

### User-requested changes
- The widget popup background is edited only inside "Bảng tiện ích"; brightness slider and expansion panel keep the shared "Nền bảng".
- Main settings became a compact menu: Float Ball (with an inline quick on/off switch), Widget panels, Quick recording, Actions & gestures, Effects, Advanced settings.
- Edge hide reworked: per-orientation switches (portrait/landscape) with a centre-notched -100..100 slider (0 = no hide), plus auto side switching so whichever side is dragged is the side hidden.
- Effects screen: blur toggle + blur area + intensity, haptics toggle + strength, tab transition style (none/fade/slide/flip), entry to shared panel background.

### Changes in this source
- `AppPrefs`: added popup panel color/opacity/corner (defaults follow the shared values), `tabTransition`, and per-orientation edge-hide flags/values; `FloatBallService` uses `popupPanelBackground()` for the widget popup while expansion/brightness use `sharedPanelBackground()`. Edge hide is applied on show, drag end, and reposition with auto-mirror side detection. `changePanel()` animates the widget list using the selected tab transition.
- New screens: `FloatBallSettingsActivity` (icon, size, opacity, corner, boot start, snap, idle dim, edge hide), `GestureSettingsActivity` (all gesture actions + assistant replacement), `RecordingSettingsActivity` (input sources, control labels, mic permission), `EffectsSettingsActivity` (blur, haptics, transitions, background entry), `AdvancedSettingsActivity` (permissions, battery, QS tiles, backup, status). `SettingsScreen` is now a short menu with Lucide icons, quick switches, and chevrons; the landscape-layout toggle moved into the widget panel editor.

### Files changed
- `app/src/main/java/com/manh/panelsassistive/SettingsScreen.kt`
- `app/src/main/java/com/manh/panelsassistive/FloatBallSettingsActivity.kt` (new)
- `app/src/main/java/com/manh/panelsassistive/GestureSettingsActivity.kt` (new)
- `app/src/main/java/com/manh/panelsassistive/RecordingSettingsActivity.kt` (new)
- `app/src/main/java/com/manh/panelsassistive/EffectsSettingsActivity.kt` (new)
- `app/src/main/java/com/manh/panelsassistive/AdvancedSettingsActivity.kt` (new)
- `app/src/main/java/com/manh/panelsassistive/WidgetPanelSettingsActivity.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/values/strings.xml`

### Verification and remaining work
- Static review only; no local JDK/Android SDK, so Gradle compilation must run through GitHub Actions. On-device checks: menu navigation, quick switches, edge-hide slider per orientation with auto side switching, widget popup background independence, and each tab transition style.

## 2026-11-20 — v28.15 In-panel blur fix, popup blur split, 8 expansion shortcuts, brightness bar, popup/preview inset

### User-requested changes
- "Mờ trong phạm vi nền bảng" did nothing: panel-scope blur now opts the window into the blur pipeline and actually blurs behind the panel (XGesture style).
- The widget panel gets its own blur toggle + intensity inside "Bảng tiện ích → Nền bảng tiện ích", independent from the Effects screen.
- Expansion panel shortcuts grew from 4 to 8 slots, laid out as two rows of four.
- Standalone brightness slider is taller, sits in a wider panel, and has larger top/bottom insets so the icon and thumb never crowd each other.
- The live widget popup and the in-settings preview now share the same layout: top-aligned grid, 2dp grid inset, no vertical re-centering, so row 0 sits close to the panel's top edge in both.

### Changes in this source
- `FloatBallService.applyPopupBlur(window, surface)`: `FLAG_BLUR_BEHIND` is added whenever blur is active (it was missing for panel scope, which is why only full-screen blur worked); background radius is used for panel scope, behind radius for screen scope; widget popup reads the new widget blur prefs and is always panel-scoped. Blur is re-applied after `dialog.show()`/`setLayout`, after background drawable swaps, and on cross-window blur changes.
- `AppPrefs`: added `widgetPanelBlurEnabled`/`widgetPanelBlurRadius`; `EXPANSION_SHORTCUT_SLOTS = 8` with padded reads for saved lists and widened write bounds.
- `WidgetPanelSettingsActivity`: new "Làm mờ nền bảng" block in the background card (toggle + 0..80 slider, SDK 31+ only), and the preview container now mirrors `showPopup()` exactly (top-aligned surface, 2dp grid padding, horizontal centering only).
- `FloatBallService.showBrightnessSlider()`: panel 92x374dp, 24dp top/bottom padding, 26dp icon with 16dp clearance, 44x250dp track.
- Effects screen copy clarifies that its blur applies to the expansion panel and brightness slider, while the widget panel has a separate setting.

### Files changed
- `app/src/main/java/com/manh/panelsassistive/FloatBallService.kt`
- `app/src/main/java/com/manh/panelsassistive/WidgetPanelSettingsActivity.kt`
- `app/src/main/java/com/manh/panelsassistive/AssistiveCore.kt`
- `app/src/main/res/values/strings.xml`

### Verification and remaining work
- Static review only; no local JDK/Android SDK, so Gradle compilation must run through GitHub Actions. On-device checks: panel-scope blur on Android 12+, independent widget blur, 8 expansion shortcuts (picker + long-press remove), brightness bar spacing, and matching top inset between preview and live popup.

## 2026-11-20 — v28.16 Widget updates only while panels are visible, fade-in opening

### User-requested changes
- Widgets no longer keep receiving/applying updates in the background while the float ball service idles; the widget host listens only while the widget panel is open (and pauses while the screen is off).
- The widget panel now fades in (~200 ms, slight scale for self-drawn panels) so RemoteViews inflation does not read as a janky instant appearance.
- The widget panel preview in settings only keeps its host alive while the editor screen is in front (loads on entering/resuming, stops on pause).

### Changes in this source
- `FloatBallService`: `widgetHost.startListening()` moved out of `onCreate` into `startWidgetUpdates()`, paired with `stopWidgetUpdates()` in `removePopup()`, `onDestroy()`, and screen-off; screen-on re-arms the host and rebuilds the grid. `showPopup()` starts the host, shows the window first, then inflates widgets via `root.post { populateWidgetList(root) }` during the fade. `showOverlayPanel()` fades/scales content in when `ValueAnimator.areAnimatorsEnabled()` allows it.
- `WidgetPanelSettingsActivity`: preview host lifecycle tied to `onResume`/`onPause`/`onDestroy` through `startPreviewHost()`/`stopPreviewHost()`, with a guard flag.

### Verification and remaining work
- Static review only; run the GitHub Actions build and check: open/close widget panel (no background widget updates after close, fade-in without a hard pop), screen off/on with the panel open, and settings preview while leaving/returning to the editor.

## 2026-11-20 — v28.17 Recording UI redesign, QS quick-action tile, settings search, widget blur scope

### User-requested changes
- Quick recording got a new, consistent identity: recording settings, playback dialog, saved-recordings library and the floating recording controls now all follow the expansion panel / brightness slider glass language, with Lucide icons instead of platform drawables.
- The quick-recording row in Settings uses `audio-lines`, and action shortcuts reuse the settings-screen icons (`audio-lines` for recording, `square-stack` for the widget panel, `settings-2` for app settings, ...).
- The widget panel editor now offers both blur scopes: "Làm mờ toàn bộ màn hình" and "Chỉ làm mờ trong phạm vi bảng".
- The live widget panel's top gap was still thicker than the preview; dialog decor system-bar insets are now consumed so content starts at the panel edge, and the preview/live chains stay identical.
- "Thao tác và cử chỉ" moved directly under "Bóng nổi", and Settings gained a search field in the top-right corner (diacritic-insensitive filtering of every row).
- New "Cài đặt nhanh" card inside "Thao tác và cử chỉ": pick action 1 (its icon becomes the QS tile icon, tap runs it) and action 2 (long-press on the tile runs it through the QS tile preferences activity), plus a one-tap "Thêm ô vào cài đặt nhanh" request.

### Changes in this source
- `AppPrefs`: `widgetPanelBlurScope`, `quickTilePrimaryAction` (default widget popup) and `quickTileSecondaryAction` (default flashlight).
- `FloatBallService`: `applyPopupBlur` reads the widget scope for `OverlaySurface.WIDGETS`; `showOverlayPanel` disables decor-fit system windows and consumes window insets (with the pre-R layout flags) so overlay content is never pushed down by status/navigation bar padding.
- `WidgetPanelSettingsActivity`: scope chips (glass tab style) inside the widget blur block.
- `QuickSettingsTiles.kt`: `QuickActionTileService` renders the action glyph into the tile icon, runs action 1 on click and refreshes its label/state; `QuickTileActionsActivity` (Theme.NoDisplay, `QS_TILE_PREFERENCES`) runs action 2 on long-press; `requestAddQuickAction` requests the tile via `StatusBarManager`.
- `WidgetPickerActivity`: new picker targets for the two quick-tile actions.
- `RecordingSettingsActivity`: glass header cards with icon tiles, selectable source rows (at least one source stays on), labels toggle, gesture shortcut / saved-recordings shortcuts, permission card.
- `RecordingPlaybackActivity`: full glass redesign — rounded gradient panel, Lucide play/pause/rewind/folder controls, glass seek card, library rows with icon tiles, and custom glass dialogs replacing `AlertDialog`/`PopupMenu` for share/delete.
- `BackgroundRecordingService`: floating controls use Lucide pause/play/square glyphs, a grip drag handle, glass colors and secondary labels.
- `SettingsScreen`: search field in the header row, row filtering, empty-state text, and the new menu order.
- `AndroidManifest.xml`: registers `QuickActionTileService` and `QuickTileActionsActivity`.

### Files changed
- `app/src/main/java/com/manh/panelsassistive/AssistiveCore.kt`
- `app/src/main/java/com/manh/panelsassistive/FloatBallService.kt`
- `app/src/main/java/com/manh/panelsassistive/WidgetPanelSettingsActivity.kt`
- `app/src/main/java/com/manh/panelsassistive/SettingsScreen.kt`
- `app/src/main/java/com/manh/panelsassistive/GestureSettingsActivity.kt`
- `app/src/main/java/com/manh/panelsassistive/WidgetPickerActivity.kt`
- `app/src/main/java/com/manh/panelsassistive/QuickSettingsTiles.kt`
- `app/src/main/java/com/manh/panelsassistive/ActionGlyphView.kt`
- `app/src/main/java/com/manh/panelsassistive/RecordingSettingsActivity.kt`
- `app/src/main/java/com/manh/panelsassistive/RecordingPlaybackActivity.kt`
- `app/src/main/java/com/manh/panelsassistive/BackgroundRecordingService.kt`
- `app/src/main/AndroidManifest.xml`

### Verification and remaining work
- Static review only; no local JDK/Android SDK, so Gradle compilation must run through GitHub Actions. On-device checks: widget blur scope both ways on Android 12+, live-vs-preview top gap, settings search with/without Vietnamese diacritics, quick-action tile add/tap/long-press (ROM dependent), recording overlay, playback dialog and library, and share/delete glass dialogs.

## 2026-11-20 — v28.18 Theme + widget sections, panel-scope blur actually working, thinner live top rim

### User-requested changes
- New "Giao diện" section with 5 palettes (Aurora, San hô, Bạc hà, Tím mây, Hổ phách) that recolours the whole app: accents, page gradients, status/navigation bars, floating panels and every home-screen widget.
- New "Widget" section listing only the widgets from the reference mockup: Pin, Âm lượng (horizontal + vertical), Đồng hồ, Nhạc. Widgets are `AppWidgetProvider`s, so third-party launchers can host them; each card offers "Thêm …" via `requestPinAppWidget` when supported.
- Widget and Giao diện rows sit directly below Hiệu ứng in the main settings list.
- Live widget panel still had a thick top rim versus the preview, and "Chỉ làm mờ trong phạm vi bảng" had no visible effect; find open-source references to consult.

### Changes in this source
- `styles.xml`: new `GlassOverlayDialog` (`windowIsTranslucent`, `windowFloating`, transparent window background, no dim/min-width) used by every overlay panel dialog.
- `FloatBallService`:
  - `showOverlayPanel()` creates the dialog with `GlassOverlayDialog`, sets `PixelFormat.TRANSLUCENT`, assigns the glass drawable as the *window* background, zeroes decor padding before and after `show()`, consumes window insets (`WindowInsets.CONSUMED` on R+), and uses `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`.
  - `applyPopupBlur()` now sets `setBackgroundBlurRadius` only when a real blur is available and the scope is panel-only; lowers the glass background alpha to 168 while panel-scope blur is on (blur draws under the window surface, so a ~80 % opaque glass hid it) and restores 255 when off.
  - `ACTION_UPDATE_POPUP_APPEARANCE` for the widget panel refreshes the window background, re-zeroes decor padding and re-applies blur after theme changes.
  - Expansion panel and brightness slider moved their glass to `windowBackground` instead of the content view, so they follow the same chamfered, un-padded path.
- `Themes.kt` (`AppTheme`, `AppThemes.ALL/current/apply`, `PanelsApp`): palette application writes theme key + shared/popup panel colour/opacity/corner, refreshes GlassUi, the popup window and all widgets.
- `GlassUi`: palette is no longer constant — `applyTheme()` re-syncs accents and page colours from the active theme.
- `ThemeSettingsActivity`, `WidgetSettingsActivity`: glass settings screens (palette rows with preview + check, widget cards with pin action, cross-links).
- `GlassWidgets.kt` + `widget_*` layouts/provider XMLs + 4 receivers in `AndroidManifest.xml`: battery, volume (portrait/landscape), clock, music widgets with bitmap-drawn glass, theme accent glow and rim; `GlassWidgets.refreshAll()` on theme change.
- `SettingsScreen`: "Widget" (`layout-grid`) and "Giao diện" (`palette`) rows below "Hiệu ứng".

### Root causes behind the two bugs (references consulted)
- Blur never applied: AOSP `DecorView.updateBackgroundBlurRadius()` forces the radius to 0 unless `Window.isTranslucent()`; the service's default dialog theme was opaque. Fixed with the translucent dialog theme.
- Blur invisible even when enabled: the glass background alpha (~204/255) sat above the blurred content. AOSP's window-blur guide lowers the window background alpha while blur is on; we follow that (168 vs 255).
- Thick live top rim: `DecorView.setWindowBackground()` reads the drawable's `getPadding()` into decor padding, and prior inset handling left status-bar padding in place — both pushed content down. Zeroing decor padding and consuming insets removes it.

### Files changed
- `app/src/main/res/values/styles.xml`
- `app/src/main/java/com/manh/panelsassistive/FloatBallService.kt`
- `app/src/main/java/com/manh/panelsassistive/Themes.kt`
- `app/src/main/java/com/manh/panelsassistive/GlassUi.kt`
- `app/src/main/java/com/manh/panelsassistive/ThemeSettingsActivity.kt`
- `app/src/main/java/com/manh/panelsassistive/WidgetSettingsActivity.kt`
- `app/src/main/java/com/manh/panelsassistive/GlassWidgets.kt`
- `app/src/main/java/com/manh/panelsassistive/SettingsScreen.kt`
- `app/src/main/java/com/manh/panelsassistive/AssistiveCore.kt`
- `app/src/main/AndroidManifest.xml`
- new widget layouts in `app/src/main/res/layout/widget_*.xml`, `app/src/main/res/xml/glass_widget_*.xml`

### Verification and remaining work
- Static review only; compile through GitHub Actions "Android build". On-device: Android 12+ with "Allow window-level blurs" enabled — panel-scope blur inside the panel bounds only, live vs preview top rim, brightness/expansion glass intact, theme switch recolouring widgets, widget pin on a third-party launcher.
- Known limit: a Dialog window cannot draw behind the status bar; if the rim still looks off with a translucent bar, the next step is switching the overlay from Dialog to a `WindowManager` view.

### Follow-up CI fix (v28.18)
- GitHub Actions đầu tiên báo `GlassWidgets.kt:124/125 Argument type mismatch: Long but Int expected` — hằng `0xE4000000` vượt `Int.MAX_VALUE` nên Kotlin suy ra `Long`. Đã thêm `.toInt()` cho cả hai biểu thức trong `cardBitmap()`; đã rà toàn bộ hằng hex 8 chữ số khác (đều đã có `.toInt()` hoặc nằm trong giới hạn Int).

## v28.19 — Glass widgets, expansion settings, free layout

### Widgets (home screen)
- `GlassWidgets.kt` rewritten around a bitmap-drawn white glass card (`cardBitmap`): vertical white gradient, white rim, optional stacked lightning watermark in blue-grey. Text on the card is now dark navy (`#182434` / `#3A4A60`) so it reads like the reference white card; theme accent only tints the volume +/− and music play buttons.
- Sizes: battery 2x2, clock 2x2, music 2x2, volume vertical 1x2, volume horizontal 2x1 (split into `GlassVolumeVerticalWidget` / `GlassVolumeHorizontalWidget` with separate provider XMLs and manifest receivers).
- Interaction: value/bg taps open the app (`MainActivity`); music card taps go through `WidgetLaunchActivity`, which resolves the package currently playing via `AudioManager.getActivePlaybackConfigurations()` and opens its launcher intent (fallback: `CATEGORY_APP_MUSIC`, then `MainActivity`). Prev/play/next dispatch media keys.
- `BatteryWidgetReceiver` (power connected/disconnected, battery low/okay) refreshes the battery card without polling; `updatePeriodMillis` stays at 30 min.

### Expansion panel
- New `ExpansionSettingsActivity` (Cài đặt → Bản mở rộng): reorder bars up/down, show/hide each bar (at least one stays), add/replace/remove shortcuts (8 slots, `WidgetPickerActivity` action picker).
- `showExpansionPanel()` now renders `AppPrefs.expansionSliders()` in order (default brightness, ring, notification, alarm, media — media last, far right), has no "+" cells, and hides the chevron/shortcut block when all slots are empty. Window width/height is computed from the bar count and shortcut rows, and stays centered.
- Audio bars call `applyStreamVolume` while dragging and `finishStreamVolume` on release.

### Volume outside the app
- `finishStreamVolume` verifies `getStreamVolume` after `setStreamVolume`; when an OEM ROM (OnePlus/Oppo/Realme) silently ignores the background-service call, it retries through `VolumeApplyActivity` (transparent, `Theme.Translucent.NoTitleBar`, `noHistory`), which is the accepted workaround for the background volume restriction.

### Panel editor
- Free layout switch (`AppPrefs.panelFreeMode`, default off) in `WidgetPanelSettingsActivity`; when on, live panel move/resize and preview move/resize skip overlap checks, and `addPanelItem(..., allowOverlap = true)` keeps the chosen cell.
- Auto-size when adding a widget into a panel: span = ceil(min size / cell size) clamped to 2..6 in both axes. If the remaining columns at the target cell are narrower than 2 (e.g. 1x3), a confirm dialog (`Tiếp tục` / `Thôi`) explains that the widget may display oddly; manual resize still allows values beyond 6x6 (e.g. 5x10).

### Cleanup
- Removed now-unused `createSliderPill`, `SliderTrackView`, `sliderPillBackground`, `scheduleStreamVolume`, `pendingVolumeUpdates`, `addVolumeSliderRow`.

### Verification
- Static review only. Gate: GitHub Actions "Android build". On device: pin each widget and check glass/watermark/text contrast, tap targets, music tap opening the playing app, expansion reorder/hide + shortcuts add/remove, free layout overlap in the live panel, auto-size confirm at a 1-column edge, and media volume change while Facebook is in front.

## v28.20 — Cửa sổ tự do + icon pack

### Cửa sổ tự do (`Cài đặt nâng cao`)
- `FloatingApps`: app mở từ bảng tiện ích / action "mở app" đi qua `FloatingApps.open()`. Khi bật, thử theo thứ tự: Shizuku (`am start-activity --windowingMode 5 <pkg>/<cls>`, cần quyền Shizuku) → `ActivityOptions.setLaunchWindowingMode(5)` + `setLaunchBounds` (reflection, không cần quyền) → mở bình thường.
- Shizuku chỉ được nhắc quyền khi người dùng mở màn Cài đặt nâng cao và bấm "Cấp quyền Shizuku"; nếu chưa cài, nút dẫn tới Play Store. `enableFreeformSupport()` bật `enable_freeform_support` + `development_force_resizable_activities` một lần khi bật công tắc.
- Thêm dependency `dev.rikka.shizuku:api/provider:13.0.0`, khai báo `rikka.shizuku.ShizukuProvider` với authority `${applicationId}.shizuku`.

### Icon pack (`Giao diện`)
- `IconPacks`: phát hiện gói icon ADW/Nova (6 theme actions), đọc `assets/appfilter.xml`, `assets/drawable.xml`, chuỗi `iconback/iconmask/iconupon` từ resources của gói mà không cần thư viện zip.
- Công tắc "Áp dụng icon pack" + chọn gói; công tắc "Mặt nạ & lớp phủ" ghép iconback + icon gốc (clip bằng iconmask) + iconupon cho app không có icon trong gói; bọc bọc fallback glass squircle khi gói thiếu iconback.
- `IconOverrideActivity`: 2 tab App / Lối tắt (lối tắt lấy từ các trang panel + expansion shortcuts), ô tìm kiếm, mỗi dòng mở tuỳ chọn "Chọn ảnh từ máy" (copy PNG vào `filesDir/icons`), "Chọn icon từ gói" (danh sách có tìm kiếm) hoặc "Đặt lại".
- Override lưu JSON trong prefs (`icon_overrides`), key `type|value`; icon panel (`FloatBallService.createPanelShortcut`), preview editor và danh sách chọn app đều đi qua `IconPacks.resolve()`.

### Verification
- Static review only. Gate: GitHub Actions "Android build". On device: bật cửa sổ tự do với/không có Shizuku trên ROM hỗ trợ freeform; cài 1 icon pack, bật/tắt mặt nạ; thay icon 1 app bằng ảnh và bằng icon gói rồi xem trong bảng tiện ích.

### Follow-up CI fix (v28.20)
- GitHub Actions báo manifest merger: `dev.rikka.shizuku:api:13.1.5` khai báo minSdk 24 > 23 của dự án. Đã hạ cả `api` và `provider` về **13.0.0** (minSdk 23, vẫn có `newProcess`, `checkSelfPermission`, `isPreV11`, listener Shizuku) thay vì nâng minSdk và mất hỗ trợ Android 6.

### Follow-up CI fix (v28.20 #2)
- `FloatBallService`: hai lời gọi `addVerticalLevelSlider` / `VerticalBrightnessTrackView` truyền trailing lambda nên rơi vào tham số `onFinished` (optional) thay vì `onChanged`/`onValueChanged`; đổi sang named argument.
- `IconPacks`: `android.content.res.Resources` không có `packageName`; truyền package của gói icon xuyên suốt `findFirst`/`resolveId`.
- `OverlayActivities`: `AudioPlaybackConfiguration.isActive()` và `getClientUid()` là API ẩn (không có trong SDK công khai); đọc bằng reflection trong try/catch, lỗi thì rơi về `CATEGORY_APP_MUSIC` như trước.

## v30 — Widget glass 2.0, mặt nạ icon, icon bóng nổi

### Mặt nạ icon pack
- `IconPacks.maskMode()` nhận diện hai quy ước ADW/Nova bằng alpha góc (1,1) so với tâm: góc đục + tâm trong = khung đen → `DST_OUT`, ngược lại = shape trắng → `DST_IN`. Trước đây luôn dùng `DST_IN` nên pack dạng khung đen bị khoét lỗ.
- `masked()`: chỉ vẽ `iconback` khi pack có, vẽ icon full-bleed rồi clip bằng mask, cuối cùng mới ghép `iconupon`. Bỏ lớp back giả tự tạo.

### Bảng tiện ích
- `FloatBallService.createPanelShortcut` và `WidgetPanelSettingsActivity.previewTile` không còn đặt background cho ô, mất viền trắng quanh app/shortcut.

### Widget
- Biên tập lại `GlassWidgets`: mặt kính bo `34dp`, watermark tia sét, màu chữ lấy từ `AppPrefs.widgetTextColor` (mặc định `0xFF182434`), fill trắng theo mức (pin dọc từ đáy, âm lượng ngang trái→phải mép mềm 14dp), `refreshSoon` bắn 2 update 70ms/180ms để thấy hoạt ảnh.
- Âm lượng: nhãn đổi thành icon loa, nút +/− tint accent, text % dùng màu chữ; widget dọc fill theo chiều cao.
- Nhạc: layout 1 hàng × 3 cột `[ −  ‹  phát/tạm dừng  ›  + ]`, không còn chữ; icon đổi play/pause theo `AudioManager.isMusicActive`; thêm `ic_wg_pause`, `ic_wg_volume`; xoá `ic_media_stop` không dùng.
- Pin: sửa fill + màu chữ + tap target.
- Mọi widget bấm vào thân mở app đã gán (`AppPrefs.widgetShortcut`) hoặc `MainActivity`; widget nhạc có thêm lựa chọn mở app đang phát (`WidgetLaunchActivity` + `EXTRA_OPEN_MEDIA`).
- Cài đặt widget: thẻ "Mở nhanh" (chọn/bỏ gán app, công tắc "Widget nhạc mở app đang phát") và thẻ "Màu chữ" (5 swatch + `PanelColorPicker` tuỳ chỉnh).

### Bóng nổi
- Icon mặc định trong `FloatBallIconView.onDraw` thay bằng gradient accent (`GlassUi.ACCENT` → `ACCENT_STRONG`) squircle theo `cornerPercent`, glyph vòng tròn đứt nét + chấm giữa màu trắng — cùng ngôn ngữ với icon trong Cài đặt.

### Verification
- Static review only. Gate: GitHub Actions "Android build". On device: icon pack dạng khung đen không còn khoét lỗ, bảng tiện ích hết viền, widget pin/nhạc/âm lượng hiển thị và bấm đúng, bóng nổi mặc định khớp màu accent.

### Follow-up (v30) — icon bảng tiện ích theo kích thước ô
- `createPanelShortcut` nhận `tileWidthDp`/`tileHeightDp`; icon = `min(tileW, tileH) - 18dp` (kẹp 18..320) nên ô 2x3 hiển thị icon cỡ 2x2, ô 1x1 icon cỡ 1x1.
- `createPopupItem`, grid của bảng nổi và `previewTile` trong trình biên tập truyền span đã kẹp theo số cột/hàng, nên icon phóng to/thu nhỏ đúng ô thực tế.
- Lối tắt ở bảng mở rộng giữ nguyên cỡ cũ (tile 52dp → icon 40dp).

## v30.1 — Công tắc viền trắng cho nền bảng

### Yêu cầu
- Thêm nút bật/tắt viền trắng trong bảng tiện ích và Nền bảng; khi gạt tắt, nền bảng mất hẳn đường viền sáng (trong suốt).

### Thay đổi trong mã
- `AppPrefs.panelBorderEnabled` / `setPanelBorderEnabled` (mặc định bật, khóa `panel_border_enabled`) là một công tắc dùng chung cho mọi bảng nổi.
- `FloatBallService.panelBackground()` chỉ gọi `setStroke(1dp, argb(65,225,238,250))` khi công tắc bật, nên bảng tiện ích, bảng mở rộng và thanh độ sáng cùng mất viền khi tắt.
- `BackgroundSettingsActivity` (“Nền bảng”) và `WidgetPanelSettingsActivity` (thẻ “Nền bảng tiện ích”) có hàng “Viền trắng” + `GlassUi.switch`; bật/tắt cập nhật ngay phần xem trước và gọi `refreshPopupAppearance` / `refreshPopupSurface` để vẽ lại bảng đang mở.
- Preview của cả hai màn hình cũng chỉ vẽ stroke khi công tắc bật.

### Tệp thay đổi
- `app/src/main/java/com/manh/panelsassistive/AssistiveCore.kt`
- `app/src/main/java/com/manh/panelsassistive/FloatBallService.kt`
- `app/src/main/java/com/manh/panelsassistive/BackgroundSettingsActivity.kt`
- `app/src/main/java/com/manh/panelsassistive/WidgetPanelSettingsActivity.kt`

### Kiểm chứng
- Chỉ soát mã tĩnh; cổng build là GitHub Actions “Android build”. Trên máy: mở bảng tiện ích rồi tắt “Viền trắng” trong Nền bảng tiện ích — viền biến mất ngay; mở Nền bảng tắt cùng công tắc — bảng mở rộng/thanh sáng cũng mất viền; bật lại thì viền trở về như cũ.

## v31 — Widget nhạc kính mờ, tự co giãn và điều khiển MediaSession

### Yêu cầu
- Thẻ nhạc phong cách kính mờ: ảnh bìa bên trái, tên bài + nghệ sĩ, hàng nút chia đều; trạng thái chờ đẹp khi không phát nhạc.
- Chạm vào ảnh bìa/thông tin mở app đang phát; không có app phát thì mở app được gán cho widget.
- Play/Pause/Prev/Next điều khiển đúng MediaSession đang hoạt động.
- Gán app nhạc mặc định, gán riêng từng widget ghim, và nút “Phát nhạc ngay”.
- Widget phải đổi bố cục theo kích thước: 2×2 mặc định, 4×2 kéo dài, 4×4 phóng to; nút chia đều, không bị ẩn khi thu nhỏ.

### Thay đổi trong mã
- `WidgetMedia.kt` (mới): đọc `MediaSessionManager.getActiveSessions` (sau khi người dùng cấp truy cập thông báo) để lấy tên bài, nghệ sĩ, ảnh bìa và trạng thái phát; gửi lệnh qua `MediaController.TransportControls`, rơi về `AudioManager.dispatchMediaKeyEvent` khi không có session. `MediaNotificationListener` chỉ đánh thức khi thông báo media xuất hiện/đổi/biến mất (không polling) và gọi `GlassWidgets.refreshSoon` + refresh sau 900 ms. `activePackage()` ưu tiên session đang phát, sau đó `activePlaybackConfigurations` (reflection, API 26+).
- `GlassWidgets.kt`: `MusicSize` + `musicSize(options)` (COMPACT < 200dp rộng, WIDE ≥ 200dp, LARGE ≥ 220×200) chọn 3 layout; `bindMusicCard` dùng kính dày hơn (alpha 140/82, viền 150) có sheen phía trên; `roundedBitmap` cắt ảnh bìa bo góc, `artPlaceholderBitmap` vẽ ô kính + glyph Lucide “music” khi chưa có ảnh; `musicTapPending` / `musicPlayNowPending` mở `WidgetLaunchActivity`; `GlassWidgetProvider.onReceive` chuyển `EXTRA_WIDGET_ID` xuống `handleAction(context, action, widgetId)`; `GlassMusicWidget` đọc snapshot, đổi cỡ chữ/icon theo size, gắn PendingIntent prev/next/toggle (hoặc play-now khi chưa có session), và xoá gán riêng trong `onDeleted`.
- Layout mới: `widget_music.xml` (2×2, art 56dp + hàng nút 3 ô weight bằng nhau), `widget_music_wide.xml` (4×2, art 88dp bên trái, cột phải chứa text + hàng nút weight), `widget_music_large.xml` (4×4, art 132dp, hàng nút 58dp chia đều), `widget_art_bg.xml` (ô ảnh bìa bo 18dp). Provider `res/xml/glass_widget_music.xml`: min 110×110dp, resize cả hai chiều, target 2×2.
- `OverlayActivities.kt`: `WidgetLaunchActivity` nhận `EXTRA_MEDIA_TAP` (mở app đang phát → app gán riêng → app “Mở nhanh” → app nhạc hệ thống) và `EXTRA_PLAY_NOW` (resume session của app gán hoặc mở app rồi gửi KEYCODE_MEDIA_PLAY sau 1,5 s).
- `WidgetSettingsActivity.kt`: thẻ âm thanh mới — trạng thái truy cập thông báo + nút cấp quyền, chọn app nhạc mặc định, “Phát nhạc ngay”, và danh sách widget nhạc đang ghim để gán app riêng từng cái. Bỏ công tắc “Widget nhạc mở app đang phát” (logic mới tự ưu tiên app đang phát).
- `AssistiveCore.kt`: prefs `music_widget_package` (mặc định) và `music_widget_package_<id>` (riêng từng widget, fallback về mặc định rồi app “Mở nhanh”).
- Manifest: đăng ký `MediaNotificationListener` với `BIND_NOTIFICATION_LISTENER_SERVICE`; `<queries>` thêm `MediaBrowserService` và `MEDIA_BUTTON`; chuỗi `music_listener_label` (en/vi).

### Quyền / component
- `NotificationListenerService` chỉ để lấy quyền đọc `MediaSessionManager` (Android 5.1+ yêu cầu người dùng bật trong Cài đặt → Truy cập thông báo). Không có quyền này widget vẫn chạy: hiện “Đang phát nhạc/Chưa phát nhạc” theo `AudioManager.isMusicActive`, điều khiển bằng phím media.
- `AppWidgetProvider` (`GlassMusicWidget`) + `PendingIntent` broadcast cho nút, `PendingIntent.getActivity` cho vùng chạm (trampoline trong suốt vì Android 10+ chặn khởi chạy activity từ background).

### Tệp thay đổi
- `app/src/main/java/com/manh/panelsassistive/WidgetMedia.kt` (mới)
- `app/src/main/java/com/manh/panelsassistive/GlassWidgets.kt`
- `app/src/main/java/com/manh/panelsassistive/OverlayActivities.kt`
- `app/src/main/java/com/manh/panelsassistive/WidgetSettingsActivity.kt`
- `app/src/main/java/com/manh/panelsassistive/AssistiveCore.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/layout/widget_music.xml`, `widget_music_wide.xml`, `widget_music_large.xml`
- `app/src/main/res/drawable/widget_art_bg.xml`
- `app/src/main/res/xml/glass_widget_music.xml`
- `app/src/main/res/values/strings.xml`, `values-vi/strings.xml`

### Kiểm chứng
- Chỉ soát mã tĩnh; cổng build là GitHub Actions “Android build”. Trên máy: ghim widget nhạc (2×2), kéo thành 4×2 và 4×4 để thấy art/nút phóng to và chia đều; cấp truy cập thông báo rồi mở YouTube Music/Nhaccuatui để thấy tên bài + ảnh bìa, thử pause/next/prev; tắt nhạc rồi chạm Play để kiểm tra “Phát nhạc ngay”; chạm vào thân widget khi đang phát để mở đúng app, khi im lặng để mở app đã gán.

### Follow-up CI fix (v31)
- GitHub Actions: `WidgetMedia.kt:281 Unresolved reference 'release'` — `android.media.session.MediaController` không có `release()` trong SDK công khai (chỉ `MediaBrowser` mới có `disconnect()`); đã bỏ hàm `release()` và mọi lời gọi `sessions.forEach { release(it) }` trong `snapshot`/`activePackage`/`send`/`resume`. Controller do framework quản lý, không cần giải phóng thủ công.

## v32 — Thiết kế kính tối, ảnh tròn căn giữa, widget độ sáng và cập nhật thời gian thực

### Yêu cầu
- Widget nhạc: ảnh bìa tròn hoàn toàn căn giữa trên cùng, tên bài + nghệ sĩ căn giữa, hàng Prev / Play / Next căn giữa dưới cùng; nền kính tối có độ loang; trạng thái chờ gọn.
- Hệ màu thống nhất: chữ và mọi icon màu trắng (mặc định), vệt accent chỉ dùng cho thanh mức và nút Play.
- Widget âm lượng ngang mỏng, bỏ %, chỉ còn icon (đổi theo mức); nửa trái = giảm, nửa phải = tăng; bỏ chạm mở app.
- Widget âm lượng dọc: nửa trên = tăng, nửa dưới = giảm; icon + ở sát mép trên, − sát mép dưới, luôn bám mép khi kéo dài; bỏ chạm mở app.
- Icon tăng/giảm của cả 4 widget mức nằm sát mép ngoài (trên/dưới hoặc trái/phải) và bám mép khi đổi kích thước.
- Cập nhật thời gian thực khi bấm nút âm lượng vật lý hoặc đổi độ sáng.
- Bỏ widget Pin (không hoạt động); thêm widget độ sáng dọc + ngang cùng hệ với âm lượng.
- Các widget khác (đồng hồ) theo ngôn ngữ thiết kế của thẻ nhạc; gán riêng từng widget nhạc có thêm lựa chọn “Không mở app”.

### Thay đổi trong mã
- `GlassWidgets.kt`:
  - `cardBitmap(...)` vẽ kính navy đậm (alpha 148/102, thẻ nhạc 176/124), sheen trắng phía trên, quầng accent `RadialGradient` góc dưới phải, halo trắng thêm cho thẻ nhạc, viền trắng 1dp alpha 92; `drawLevelFill` tô accent có vạch sáng 2dp ở biên mức.
  - `circularArtBitmap` cắt ảnh bìa thành hình tròn có vành sáng, hoặc đĩa kính + glyph “music” khi chờ; `glyphBitmap` render glyph Lucide trắng cho icon RemoteViews.
  - `GlassWidgetProvider` không còn watermark pin; `GlassLevelWidget` dùng chung cho âm lượng/độ sáng: một glyph ở giữa, hai vùng chạm chia đôi (`widget_up`/`widget_down`), icon +/− đặt ở mép (`widget_up_icon`/`widget_down_icon`) nên bám góc khi kéo dài; `GlassVolumeWidget` chỉnh `STREAM_MUSIC` với `FLAG_SHOW_UI`; `GlassBrightnessWidget` ghi `SCREEN_BRIGHTNESS` (bước 24, kẹp 1..255, chuyển chế độ MANUAL), khi thiếu `WRITE_SETTINGS` thì vùng chạm mở màn hình cấp quyền qua `WidgetLaunchActivity`.
  - `WidgetLevelObserver`: `ContentObserver` trên `volume_music` và `SCREEN_BRIGHTNESS` (không polling) → `refreshLevelWidgets()`; đăng ký từ `PanelsApp.onCreate`, `BootReceiver`, `onUpdate` và `bind` của widget mức.
  - Xoá `GlassBatteryWidget`, `BatteryWidgetReceiver`, `roundedBitmap`, `artPlaceholderBitmap`, watermark tia sét; thẻ nhạc dùng chữ trắng, nghệ sĩ `#D9FFFFFF`, nút Play tròn accent + icon trắng.
- `AssistiveCore.kt`: sentinel `AppPrefs.WIDGET_NO_APP`; `musicWidgetPackage(id)` trả rỗng khi widget được gán “không mở app”; thêm `musicWidgetNoApp(id)`.
- `OverlayActivities.kt`: `WidgetLaunchActivity` thêm `EXTRA_OPEN_WRITE_SETTINGS` (mở `ACTION_MANAGE_WRITE_SETTINGS` cho package); `openMusicApp` tôn trọng lựa chọn “Không mở app”.
- Layout/drawable/provider mới: `widget_brightness_vertical.xml`, `widget_brightness_horizontal.xml`, `glass_widget_brightness_vertical.xml`, `glass_widget_brightness_horizontal.xml`; viết lại `widget_volume_vertical.xml`, `widget_volume_horizontal.xml` (2 vùng chạm + icon mép), `widget_music*.xml` (ảnh tròn căn giữa/chữ trắng), `widget_clock.xml` (chữ trắng), `widget_card_bg.xml` (kính navy dự phòng); xoá `widget_battery.xml`, `glass_widget_battery.xml`; provider âm lượng đặt `updatePeriodMillis=1800000` để đăng ký lại observer sau khi process bị kill.
- `AndroidManifest.xml`: bỏ receiver Pin + `BatteryWidgetReceiver`; thêm receiver độ sáng dọc/ngang.
- `WidgetSettingsActivity.kt`: thay thẻ Pin bằng 2 thẻ độ sáng; cập nhật mô tả âm lượng (chia đôi, không %, không mở app) và nhạc (ảnh tròn căn giữa); hộp chọn app nhạc có thêm “Không mở app (chỉ hiển thị bài hát)” cho gán riêng từng widget; thẻ màu chữ đổi thành ghi chú hệ chữ trắng cố định.

### Kiểm chứng
- Chỉ soát mã tĩnh (máy không có JDK/Android SDK); cổng build là GitHub Actions “Android build”.
- Trên máy: bấm nút âm lượng vật lý và xem widget ngang/dọc cập nhật ngay; kéo dài widget để icon +/− bám mép; chạm nửa trái/phải và nửa trên/dưới; cấp quyền “Chỉnh sửa cài đặt hệ thống” cho widget độ sáng; ghim thẻ nhạc 2×2/4×2/4×4 để xem ảnh tròn căn giữa và hàng nút chia đều; chọn “Không mở app” cho một widget nhạc rồi chạm thân thẻ.

## v33 — Chuẩn kính mờ trong suốt nhẹ, loang theo ảnh bìa, chữ trắng có bóng

### Yêu cầu
- Nền widget chỉ đục khoảng 18–35% để thấy rõ hình nền; thêm gradient/ánh loang mềm (trắng hoặc màu nhẹ từ một góc); viền 0,75–1dp trắng alpha 25–35%; bo góc lớn.
- Toàn bộ chữ/icon dùng tông trắng; không còn tình trạng đen/trắng lẫn lộn; không để tiêu đề bị cắt cụt bằng “…”.
- Thẻ nhạc: ảnh bìa tròn căn giữa, tên bài + nghệ sĩ 1 dòng căn giữa, hàng Prev / Play / Next căn giữa; loang lấy tông nhẹ từ ảnh bìa; giữ nguyên mở app đang phát/app gán, điều khiển MediaSession, gán app riêng và “Play music now”.
- Widget âm lượng/độ sáng giữ thiết kế đồng bộ: ngang mỏng chia đôi trái/phải, dọc chia đôi trên/dưới, icon tăng/giảm bám mép ngoài, không hiện %, cập nhật thời gian thực.

### Thay đổi trong mã (vòng tinh chỉnh này)
- `GlassWidgets.cardBitmap`: hạ alpha nền navy còn 76→46 (≈30%→18%, thẻ nhạc 88→52 ≈35%→20%); sheen trắng 22/30; bloom RadialGradient góc dưới phải (music 64, thẻ mức 40); halo trắng 30; thêm scrim đen mờ 18% ở nửa dưới thẻ nhạc để chữ trắng rõ trên hình nền sáng; viền trắng 0,9dp alpha 76 (≈30%); bo góc 32dp.
- `GlassWidgets.artTint(art)`: lấy màu trung bình 12×12 từ ảnh bìa (ưu tiên pixel rực), pha 45% + 15% về trắng để làm quầng loang mềm. `bindMusicCard` nhận thêm `art` và được gọi từ `GlassMusicWidget.bind` (thay cho `bindBackground`).
- `glyphBitmap`: thêm `setShadowLayer` bóng đen mờ giúp icon trắng đọc được trên kính sáng; dùng glyph Lucide `skip-back/forward/play/pause` cho nút nhạc (bỏ `tintedBitmap` không còn dùng).
- Chữ nhạc: tự thu nhỏ cỡ chữ khi tiêu đề/nghệ sĩ dài (0,72/0,84 cho tiêu đề; 0,8/0,9 cho nghệ sĩ), `ellipsize="marquee"` + `singleLine` thay cho “…” ở cả 3 layout; thêm `shadow` cho text.
- `widget_clock.xml`: chữ trắng + bóng mềm; `widget_card_bg.xml`: placeholder kính sáng 35%→18%, viền 0,9dp alpha 30%.
- Copy trong `WidgetSettingsActivity` cập nhật theo chuẩn kính trong nhẹ.

### Kiểm chứng
- XML đã parse hợp lệ bằng PowerShell `[xml]`; Kotlin chỉ soát tĩnh (máy không có JDK/SDK) — chạy GitHub Actions để xác nhận biên dịch.
- Trên máy: đặt widget trên hình nền sáng/tối để kiểm tra độ xuyên thấu và độ rõ chữ; phát nhạc có ảnh bìa màu để xem quầng loang đổi theo bìa; thử tiêu đề dài để xác nhận chữ thu nhỏ, không có dấu “…”; kiểm tra icon +/− bám mép khi kéo dài widget.

## v34 — Widget nhạc thứ 2: ảnh bìa phủ kính mờ (provider độc lập)

### Yêu cầu
- Thêm một widget nhạc hoàn toàn tách biệt, không sửa/ghi đè widget nhạc cũ: ảnh bìa làm nền trong thẻ kính mờ, tên bài (1 dòng, trắng đậm, cắt “…”) và nghệ sĩ (1 dòng, trắng nhạt) ở trên, hàng Previous / nút tròn Play–Pause / Next ở dưới.
- Bo góc 22–26dp, viền mảnh 0,75–1dp có bắt sáng ở mép trên; lớp phủ đủ tương phản để chữ/icon không chìm vào ảnh bìa.
- Trạng thái chờ khi chưa có nhạc/chưa cấp quyền: kính mờ gọn, tiêu đề chờ + gợi ý chạm mở app nhạc, nút điều khiển mờ; không báo lỗi, không lẫn đen/trắng.
- Chạm thân thẻ/thông tin mở app đang phát hoặc app đã gán; các nút điều khiển MediaSession; dùng chung hệ thống gán app riêng và “Play music now”.
- Tách provider, file appwidget-provider, layout và drawable riêng; khai báo manifest.

### Thay đổi trong mã
- `GlassMusicCoverWidget.kt` (mới): provider thứ hai. `coverBitmap()` vẽ thẻ 24dp bo góc trên `Bitmap`: ảnh bìa thu nhỏ 1/14 rồi phóng lên (mờ mịn, center-crop), scrim navy 3 điểm dừng (52%/29%/69% ở đỉnh/giữa/đáy), sheen trắng 11%, bloom RadialGradient lấy tông từ bìa (fallback accent), vệt sáng trắng dọc mép trên, viền 0,9dp trắng alpha 88. Không có bìa → nền kính navy + glyph “music” mờ 15% làm watermark.
- `sideCircleBitmap()`: nút Previous/Next là đĩa 34dp (nền trắng 18%, viền 1dp trắng 30%) có glyph skip-back/forward, alpha 178 khi chờ và 245 khi phát; nút giữa dùng `circleBitmap` 46dp tô accent (alpha B3 chờ / E6 phát) + glyph play/pause trắng.
- `bind()`: lấy snapshot MediaSession với app gán của chính widget; tiêu đề “Chưa phát nhạc”/“Đang phát nhạc” và gợi ý “Chạm để mở ứng dụng nhạc”/“Chạm để mở <app>”/“Không mở ứng dụng”; cỡ chữ co theo kích thước (15,5–19sp tiêu đề, 12–14sp nghệ sĩ). Click: prev/next/toggle qua `GlassWidgets.pending`, Play khi chờ dùng `musicPlayNowPending`, chạm thân/thông tin dùng `musicTapPending`; `onDeleted` xoá gán riêng.
- `handleAction()`: gửi lệnh MediaSession với app gán, sau đó `refreshMusicSoon` + `refreshMusicWidgets` để cả hai thẻ nhạc đồng bộ.
- `GlassWidgets.kt`: thêm `GlassMusicCoverWidget` vào `providers`; thêm `musicProviders`, `refreshMusicWidgets()`, `refreshMusicSoon()`; mở `artTint()` để thẻ mới dùng chung.
- `WidgetMedia.kt`: mọi lần refresh nhạc (notification listener, playNow, resume) chuyển sang refresh cả hai provider.
- `BootReceiver.kt`: refresh cả hai widget nhạc sau khởi động.
- `WidgetSettingsActivity.kt`: thêm thẻ “Nhạc ảnh bìa (2×2)” để ghim; mục “Gán riêng từng widget” liệt kê widget của cả hai kiểu (“Widget nhạc N”, “Widget nhạc ảnh bìa N”).
- Tài nguyên mới: `res/layout/widget_music_cover.xml` (FrameLayout + ảnh nền, tiêu đề/nghệ sĩ căn giữa, spacer weight, hàng prev/play/next dùng weight + centerInside), `res/xml/glass_widget_music_cover.xml` (2×2, resize cả hai chiều, minResize 120×110dp, update 30 phút), `res/drawable/widget_music_cover_bg.xml`, `res/drawable/widget_music_cover_play_bg.xml`; manifest thêm receiver `.GlassMusicCoverWidget` nhãn “Nhạc ảnh bìa”.

### Kiểm chứng
- Toàn bộ XML parse hợp lệ; Kotlin chỉ soát tĩnh (không có JDK/SDK trên máy) — chạy GitHub Actions “Android build”.
- Trên máy: ghim cả hai widget nhạc rồi phát một bài có ảnh bìa màu — thẻ mới phải mờ nền theo bìa, chữ trắng rõ, nút giữa đổi play/pause; thử tiêu đề dài (cắt “…”, không tràn), trạng thái chưa phát (kính mờ + gợi ý), chạm thân thẻ mở đúng app, gán app riêng cho từng widget và “Phát nhạc ngay”; kiểm tra hai thẻ cùng cập nhật khi bấm prev/next.
