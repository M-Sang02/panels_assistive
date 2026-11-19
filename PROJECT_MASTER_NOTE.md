# Panels Assistive - Project Master Note

## Goal and scope

Panels Assistive is an independent Android app inspired by the documented XGesture experience. The uploaded Panels Assistive source ZIP and `Panels_Assistive_Copilot_Spec.md` are the product requirements; XGesture 1.66.0 is a behavior and UI reference only.

- App: Panels Assistive
- Package: `com.manh.panelsassistive`
- Current stage: customizable Float Ball, editable widget panels, system-action controls, optional Accessibility and assistant-role support
- Minimum Android version: API 23

## Requirements and behavior

- Dark navy Settings UI with English and Vietnamese Android string resources. Settings do not use full-screen blur.
- Float Ball uses the Android overlay permission, can use a picked image/GIF instead of the default icon, exposes 20-80 dp size and opacity controls, and supports dimming/freezing its animation after an idle timeout.
- Single, double, triple tap, and long press can each be assigned independently. Gesture and assistant actions include the widget panel, expansion panel, media volume, flashlight, background recording, notification shade, Back, Recents, Home, optional system-assistant launch, Accessibility settings, app launch, and Float Ball toggle. Only navigation, assistant launch, and notification actions require the Accessibility service; the assistant replacement list intentionally omits assistant launch.
- Optional haptics default to off and can be enabled with adjustable strength for dispatched actions.
- Quick background recording requests microphone/notification permissions, saves AAC audio, and shows pause/resume and stop-and-save controls in a side bar. A persistent notification displays the elapsed mm:ss and a completed notification retains the saved duration. The side bar replaces the Float Ball during recording, or appears alone when the Float Ball is disabled; optional labels can accompany its icons.
- Widget panels use `AppWidgetHost` and system bind/configuration flows. Each orientation has named, reorderable panels; apps and app-provided shortcuts can be added alongside widgets. The popup has no heading, reveals its add affordance on long-press in blank space, and switches panels by horizontal swipe.
- The dedicated panel editor previews bound widgets and icons, supports add/remove/reorder, panel naming/deletion, grid columns, labels, click-to-close behavior, and a live overlay preview.
- Landscape panels are independently stored and are used only while landscape mode is enabled and the device is rotated. Otherwise the portrait panel is reused.
- Panel and expansion overlays use a rounded translucent window background; Android 12+ background blur is clipped to the panel window rather than using full-screen blur-behind. Blur enablement and radius are shared.
- The expansion panel provides a screen-brightness slider, media-volume UI, and flashlight toggle. Brightness and camera permissions are requested only for their respective controls.
- Assistant replacement is registered as an Android VoiceInteractionService/ROLE_ASSISTANT candidate. The selected action runs only after the user chooses Panels Assistive as the default assistant.
- The Panels Assistive Quick Settings tile toggles Float Ball. A separate Accessibility tile opens Android Accessibility settings only; it does not enable Accessibility.
- Accessibility service does not retrieve window content. It is needed only for navigation, notification, and system-assistant actions; other actions remain usable while Accessibility is off.
- Overlay permission and Accessibility are checked before dependent actions. No permission is silently enabled.

## Architecture

- The implementation stays compact: eight Kotlin source files, without one-class-per-feature scaffolding.
- `MainActivity` hosts the Settings screen and handles return from overlay permission settings.
- `AssistiveCore.kt` contains `AppPrefs`, `ActionDispatcher`, and `AccessibilityActionService`.
- `FloatBallService` owns the Float Ball, widget/expansion overlays, GIF/image icon rendering, system actions, and foreground notification.
- `BackgroundRecordingService` owns microphone capture, saved audio, the side controls, and elapsed-duration notifications.
- `WidgetPanelSettingsActivity` manages panels, previews, orientation-specific layouts, and layout controls.
- `WidgetPickerActivity` handles widget binding/previews, launcher apps, and app-provided shortcut creation.
- `QuickSettingsTiles.kt` contains tile registration and both `TileService` implementations.
- Runtime app features use Android platform APIs; no added runtime library dependencies.

## Reference and licensing

XGesture 1.66.0 was inspected as a behavior reference only. No XGesture source code was copied. The archive identifies XGesture as AGPL-3.0; see `THIRD_PARTY_NOTICES.md`.

## Completed

- Localized Settings, permission status, image/GIF icon selection, 20-80 dp size with keyboard entry, opacity and idle-dimming controls, optional haptics, gesture assignment, blur options, and Quick Settings tile requests.
- Foreground Float Ball overlay with drag, configurable size, snap preference, orientation-aware bounds, and persisted normalized position.
- Configurable single, double, and triple tap plus long-press dispatch.
- Android widget provider search/preview, bind/configuration flow, panel hosting, and item management.
- Independent named portrait/landscape panels with app and shortcut entries, live preview, grid controls, and long-press-to-add popup behavior.
- Panel-scoped background blur and shared expansion-panel blur.
- Media volume, screen brightness, flashlight and Float Ball toggle actions.
- Back, Recents, Home, and optional system-assistant actions with Accessibility gating; assistant replacement cannot select call-assistant.
- Optional idle opacity, delay, GIF freeze, and configurable vibration strength (vibration off by default).
- Permission-gated background audio recording with side pause/resume/stop controls, notification timer, and saved-duration notification.
- Optional Android assistant role service and configurable replacement action.
- Optional no-screen-content Accessibility service for opening the notification shade.
- Quick Settings tiles for Float Ball toggle and opening Android Accessibility settings.
- Updated manifest service declarations and permission checks; verified the existing reference-only third-party notice.

## Not completed

- Drag-positioning/resizing of panel entries is not implemented; ordering and grid column count are editable.
- Shortcut availability depends on apps exposing Android's `ACTION_CREATE_SHORTCUT` flow.
- Full window-inset/cutout behavior and OEM-specific popup/assistant-role behavior need device testing.
- The widget chooser uses each provider's preview image when supplied; interactive configuration occurs through Android's normal bind/configure flow.

## Known issues and validation

- Earlier CI errors for the `AppWidgetHost` import, Quick Settings tile-add API owner, unsupported voice-service XML attribute, and `WidgetPickerActivity` button layout parameters were corrected; CI has not yet been rerun.
- Local `gradlew.bat :app:compileDebugKotlin` cannot start because Java is unavailable in the current environment; no APK has been produced.
- XML parsing, localized resource-reference checks, manifest checks, delimiter checks, and `git diff --check` pass locally.
- Device behavior and Android version compatibility have not been tested on hardware or an emulator.

## Files changed

- App behavior: `app/src/main/java/com/manh/panelsassistive/`
- Manifest, Accessibility, and assistant service declarations: `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/`
- English and Vietnamese resources: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-vi/strings.xml`
- Project status note: `PROJECT_MASTER_NOTE.md`

## Build and next steps

Build locally with `gradlew.bat assembleDebug` on Windows or `bash gradlew assembleDebug` on a configured environment. GitHub Actions remains configured to build the debug APK.

Next, run the Android build in an environment with JDK 17 and Android SDK 35, then test widget binding, panel rotation, overlay blur bounds, camera/write-settings permissions, assistant-role setup, and Quick Settings on representative Android versions.

## 2026-11-19 — Thin grid border alignment fix

### Completed
- Synchronized the live Widget Popup grid inset from 4dp to 2dp in `FloatBallService.kt`.
- Reduced the preview grid inset from 8dp to 2dp in `WidgetPanelSettingsActivity.kt`.
- Reduced the shared `PanelViewportMetrics` grid inset from 8dp to 4dp in `AssistiveCore.kt` so the calculated surface size matches the thinner grid treatment.
- Reduced the edit-mode guide drawable inset from 4dp to 2dp in `PanelEditDialogs.kt` so guide lines align with the live/preview grid edge.
- Preserved the rest of the existing source tree and functionality.

### Source files changed
- `app/src/main/java/com/manh/panelsassistive/AssistiveCore.kt`
- `app/src/main/java/com/manh/panelsassistive/FloatBallService.kt`
- `app/src/main/java/com/manh/panelsassistive/WidgetPanelSettingsActivity.kt`
- `app/src/main/java/com/manh/panelsassistive/PanelEditDialogs.kt`

### Verification
- Confirmed the four intended values in source after editing.
- Local Gradle build was attempted with `./gradlew :app:assembleDebug --no-daemon --stacktrace` but could not start because this environment could not resolve/download `services.gradle.org` (`UnknownHostException`).
- Therefore APK build success and device behavior are **not claimed** here.

### Next step
- Upload the complete ZIP to GitHub.
- Run GitHub Actions build on the repository and install/test the resulting APK on the target device.
- Compare live Widget Popup and edit preview again, especially the top/bottom border thickness and guide alignment.

## v26 — 2026-11-20
- Sửa thêm độ dày viền ngoài Widget Popup/preview.
- `FloatBallService.kt`: frosted panel outer stroke giảm từ `dp(1)` xuống `1px`.
- `WidgetPanelSettingsActivity.kt`: preview outer stroke giảm từ `dp(1)` xuống `1px`.
- Giữ nguyên viền xanh `2dp` của widget khi ở chế độ chỉnh sửa; không thay đổi viền điều khiển resize/move.
- Chưa xác nhận build APK local trong môi trường này; source/ZIP được đóng đầy đủ để đưa lên GitHub Actions.
