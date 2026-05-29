# YT Music Fabric

Mod Fabric `client-side` cho phép phát nhạc YouTube trong Minecraft Java bằng `yt-dlp` + `ffmpeg`.

Command giữ tiếng Anh (`/ytmusic`), nhưng log/trạng thái trong game hiển thị tiếng Việt.

## Tương thích

- Minecraft `1.21.1`
- Minecraft `1.21.11`
- Java `21`

Mỗi phiên bản Minecraft dùng jar riêng.

## Tính năng chính

- Phát nhạc từ YouTube theo hàng chờ tối đa 5 link/lần.
- GUI điều khiển nhanh (phím mặc định `Y`).
- Điều khiển đầy đủ: phát, tạm dừng, tiếp tục, dừng, âm lượng, loop playlist, HUD.
- Tự kiểm tra/cài công cụ: `/ytmusic install ytdlp|ffmpeg|all` (Windows).
- Playlist code để chia sẻ:
  - `Copy mã` để tạo code từ playlist hiện tại.
  - `Dán mã` để nạp lại playlist code và phát.
  - Lệnh: `/ytmusic playlist code play <code>`.
- Cache audio WAV tại `config/ytmusicfabric-cache`.

## Lưu ý quan trọng

- Đây là mod **client-only**.
- Bạn phát thì chỉ máy bạn nghe.
- Người chơi khác muốn nghe giống bạn cần cài mod và dùng cùng playlist/link/code.

## Cài đặt

1. Cài Fabric Loader + Fabric API đúng version game.
2. Thêm jar mod vào thư mục `mods`.
3. Chuẩn bị `yt-dlp` và `ffmpeg` theo 1 trong 2 cách:

- Cách A: thêm vào `PATH`.
- Cách B: chỉnh file `config/ytmusicfabric.properties`:

```properties
ytDlpPath=C:/path/to/yt-dlp.exe
ffmpegPath=C:/path/to/ffmpeg.exe
```

`ffmpegPath` có thể là file `ffmpeg.exe` hoặc thư mục chứa `ffmpeg`.

## Sử dụng nhanh

```text
/ytmusic play <link1> [link2 ... link5]
/ytmusic pause
/ytmusic resume
/ytmusic stop
/ytmusic status
/ytmusic volume 80
/ytmusic loop on
/ytmusic hud off
/ytmusic tools
/ytmusic install all
/ytmusic cache clear
/ytmusic gui
```

Lệnh playlist code:

```text
/ytmusic playlist code
/ytmusic playlist code export
/ytmusic playlist code play <code>
/ytmusic playlist code import <code>
```

## GUI

- Mở GUI: phím `Y` (đổi trong Keybinds nếu cần).
- Top actions:
  - `Copy mã`: copy playlist code vào clipboard.
  - `Dán mã`: đọc code từ clipboard, nạp playlist và phát.
  - `Dán link`: dán tối đa 5 link từ clipboard.
- Khu vực dưới hiển thị playlist, trạng thái và log realtime.

## Build

Build mặc định:

```powershell
.\gradlew.bat build
```

Jar output ở `build/libs/`.

Build đa phiên bản (`1.21.1` + `1.21.11`):

```powershell
.\scripts\build-multi-version.ps1
```

Output ở `dist/multi-version/`.

## Khắc phục lỗi thường gặp

- Thiếu `yt-dlp` hoặc `ffmpeg`: chạy `/ytmusic tools` để kiểm tra.
- YouTube báo lỗi JS runtime: cài Node.js bản mới rồi thử lại.
- GUI không cập nhật sau khi thay jar: tắt hẳn client và mở lại (reload mềm không thay được code Java).

## Ghi chú pháp lý

Project chỉ cung cấp công cụ phát nội dung theo link bạn nhập.
Bạn tự chịu trách nhiệm về quyền sử dụng nội dung và tuân thủ điều khoản của nền tảng nguồn.
