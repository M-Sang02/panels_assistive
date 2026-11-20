# Panels Assistive — Project Master Note

## Bản cập nhật
v0.2.7 / source package v27

## Đã hoàn thành trong bản này

### Floating Bubble icon engine — ưu tiên tiết kiệm pin
- Tách nguồn chọn **Ảnh tĩnh** và **GIF** trong Settings.
- Ảnh tĩnh hỗ trợ chọn **một hoặc nhiều ảnh** bằng Android document picker.
- GIF hỗ trợ chọn một file GIF.
- Mặc định sau khi chọn file:
  - GIF playback = OFF.
  - Slideshow = OFF.
  - GIF OFF chỉ hiển thị frame đầu, không chạy animation timer.
  - Ảnh tĩnh nhiều file chỉ hiển thị ảnh đầu tiên nếu slideshow OFF.
- Slideshow chỉ chạy khi người dùng bật và có ít nhất 2 ảnh.
- GIF chỉ khởi tạo animation khi người dùng bật **Phát hiệu ứng GIF**.
- GIF có tùy chọn tạm dừng khi không chạm; mặc định ON, timeout mặc định 10 giây.
- Khi GIF hết thời gian không tương tác, animation dừng và quay về frame đầu.
- Khi màn hình tắt:
  - GIF animation dừng ngay.
  - slideshow dừng ngay.
- Khi Bubble bị detach/destroy hoặc animation bị tắt, các callback/runnable animation được hủy.
- Bitmap được decode theo kích thước hiển thị của Bubble thay vì giữ nguyên độ phân giải nguồn.
- Android 9+ dùng ImageDecoder cho decode có target size; GIF animation dùng AnimatedImageDrawable khi playback thực sự được bật.
- Có fallback Movie cho GIF playback trên Android cũ.
- Static bitmap dùng memory policy thấp trên Android mới.
- Các callback/timer animation không chạy khi tính năng động đang OFF.

### Cài đặt
- Thêm các preference cho:
  - icon mode;
  - danh sách static URI;
  - GIF URI;
  - GIF playback;
  - GIF idle pause;
  - GIF idle timeout;
  - slideshow;
  - slideshow interval;
  - pause slideshow khi screen off.
- Giữ khả năng đọc URI icon cũ để tương thích dữ liệu hiện tại.
- Reset icon xóa cả static/GIF state.

### Giữ nguyên
- Float Ball gesture/action system.
- Overlay permission flow.
- Widget Panel hiện có.
- Panel edge/grid/border fixes của bản trước.
- Accessibility architecture hiện có.
- GitHub Actions/source structure.

## Chưa hoàn thành
- Chưa có Widget Engine hoàn chỉnh cho launcher gồm Music/Volume/Brightness.
- Chưa có Theme Engine chung cho toàn bộ launcher widgets.
- Chưa có UI editor hoàn chỉnh cho custom widget theme.
- Slideshow hiện dùng bitmap từng ảnh, chưa có prefetch/cache nhiều ảnh; chủ ý giữ memory thấp.
- GIF fallback trên Android < 9 vẫn dùng Movie khi playback bật và có thể tốn decode hơn AnimatedImageDrawable.

## Build/test
- Đã kiểm tra cấu trúc source và đóng ZIP.
- Đã thử `./gradlew :app:assembleDebug --offline`.
- Build local **chưa xác nhận thành công** vì Gradle wrapper cần tải Gradle 8.9 từ `services.gradle.org`, môi trường hiện tại không có network.
- Chưa tuyên bố đã test APK trên thiết bị.
- GitHub Actions là bước build xác nhận tiếp theo.

## File chính bị thay đổi
- `app/src/main/java/com/manh/panelsassistive/AssistiveCore.kt`
- `app/src/main/java/com/manh/panelsassistive/MainActivity.kt`
- `app/src/main/java/com/manh/panelsassistive/SettingsScreen.kt`
- `app/src/main/java/com/manh/panelsassistive/FloatBallService.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-vi/strings.xml`
- `app/build.gradle.kts`

## Kiểm tra cần làm trên thiết bị
1. Chọn một ảnh tĩnh → xác nhận không có timer/animation.
2. Chọn nhiều ảnh → xác nhận slideshow vẫn OFF.
3. Bật slideshow → xác nhận đổi ảnh đúng interval.
4. Chọn GIF → xác nhận GIF đứng ở frame đầu.
5. Bật Phát hiệu ứng GIF → xác nhận GIF chạy.
6. Chờ timeout → xác nhận GIF dừng và về frame đầu.
7. Tắt màn hình → xác nhận GIF/slideshow dừng.
8. Bật màn hình lại → xác nhận không tự chạy nếu animation đã OFF.
9. Tắt Float Ball → xác nhận không còn callback animation.
10. Đổi kích thước Bubble → xác nhận ảnh/GIF được decode gần kích thước hiển thị, không load ảnh gốc quá lớn.

## Nguyên tắc tiếp tục
Không xóa các service/class hiện có chỉ để rút gọn project. Khi bổ sung Widget Engine, phải giữ Float Ball hiện tại và tái sử dụng cùng model/theme nếu có thể.
