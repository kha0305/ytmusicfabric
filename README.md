# YT Music Fabric

[![Latest Release](https://img.shields.io/github/v/release/kha0305/ytmusicfabric?display_name=tag&style=for-the-badge)](https://github.com/kha0305/ytmusicfabric/releases/latest)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1%20%7C%201.21.11-3C8527?style=for-the-badge)](https://github.com/kha0305/ytmusicfabric/releases/latest)
[![Loader](https://img.shields.io/badge/Loader-Fabric-EA4AAA?style=for-the-badge)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge)](https://adoptium.net/)
[![Build](https://github.com/kha0305/ytmusicfabric/actions/workflows/build.yml/badge.svg)](https://github.com/kha0305/ytmusicfabric/actions/workflows/build.yml)
[![License](https://img.shields.io/github/license/kha0305/ytmusicfabric?style=for-the-badge)](./LICENSE)

Mod Fabric `client-side` cho Minecraft Java, cho phép phát nhạc YouTube trong game bằng `yt-dlp` + `ffmpeg`, kèm GUI điều khiển, HUD, playlist 5 link và playlist code để chia sẻ cho người chơi khác cùng cài mod.

## Liên kết nhanh

- [Bản phát hành mới nhất](https://github.com/kha0305/ytmusicfabric/releases/latest)
- [Tất cả bản phát hành](https://github.com/kha0305/ytmusicfabric/releases)
- [Mã nguồn](https://github.com/kha0305/ytmusicfabric)
- [Báo lỗi / góp ý](https://github.com/kha0305/ytmusicfabric/issues)

## Tương thích

| Thành phần | Hỗ trợ |
|---|---|
| Minecraft | `1.21.1`, `1.21.11` |
| Loader | Fabric |
| Java | `21` |
| Phía chạy | Chỉ client |

Mỗi phiên bản Minecraft dùng jar riêng.

## Tính năng

- Phát nhạc YouTube trực tiếp từ link.
- Queue tối đa `5` link mỗi lần phát.
- GUI điều khiển nhanh bằng phím `Y`.
- Hỗ trợ `play`, `pause`, `resume`, `stop`, `status`, `volume`, `loop`, `hud`.
- Playlist code để `copy / paste / share`.
- Tự kiểm tra hoặc cài `yt-dlp` và `ffmpeg` trên Windows.
- Hiển thị HUD trạng thái phát.
- Tự lưu cache WAV để phát ổn định hơn.
- Log trong game bằng tiếng Việt.

## Tệp phát hành

Release mới nhất luôn nằm ở đây:

- [Trang phát hành mới nhất](https://github.com/kha0305/ytmusicfabric/releases/latest)
- [Bản phát hành v0.1.0](https://github.com/kha0305/ytmusicfabric/releases/tag/v0.1.0)

Các file jar chính:

- `ytmusicfabric-0.1.0+mc1.21.1.jar`
- `ytmusicfabric-0.1.0+mc1.21.11.jar`

## Lưu ý quan trọng

- Mod này là **client-only**.
- Khi bạn phát nhạc, chỉ máy bạn nghe.
- Người chơi khác muốn nghe cùng playlist thì họ cũng phải cài mod và dùng cùng link hoặc playlist code.
- `yt-dlp` cho YouTube đôi khi cần thêm JavaScript runtime như Node.js tùy thời điểm.

## Cài đặt

1. Cài Fabric Loader và Fabric API đúng với phiên bản Minecraft.
2. Tải đúng jar từ [Releases](https://github.com/kha0305/ytmusicfabric/releases/latest) rồi bỏ vào thư mục `mods`.
3. Chuẩn bị `yt-dlp` và `ffmpeg` theo một trong hai cách:

- Thêm vào `PATH`.
- Hoặc cấu hình thủ công trong `config/ytmusicfabric.properties`:

```properties
ytDlpPath=C:/path/to/yt-dlp.exe
ffmpegPath=C:/path/to/ffmpeg.exe
```

`ffmpegPath` có thể là file `ffmpeg.exe` hoặc thư mục chứa `ffmpeg`.

## Lệnh

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

Playlist code:

```text
/ytmusic playlist code
/ytmusic playlist code export
/ytmusic playlist code play <code>
/ytmusic playlist code import <code>
```

## GUI

- Phím mặc định: `Y`
- `Copy mã`: tạo playlist code và copy vào clipboard
- `Dán mã`: đọc playlist code từ clipboard, nạp lại playlist và phát
- `Dán link`: dán tối đa 5 link từ clipboard
- Panel dưới hiển thị playlist, trạng thái và log realtime

## Biên dịch

Biên dịch mặc định:

```powershell
.\gradlew.bat build
```

Biên dịch đa phiên bản:

```powershell
.\scripts\build-multi-version.ps1
```

Output đa phiên bản ở `dist/multi-version/`.

## Khắc phục lỗi thường gặp

- Thiếu `yt-dlp` hoặc `ffmpeg`: chạy `/ytmusic tools`.
- `yt-dlp` báo lỗi JavaScript runtime: cài Node.js bản mới.
- Đổi code Java nhưng game không cập nhật: tắt hẳn client rồi mở lại.
- GUI reload không thay thế được code Java của mod, chỉ reload resource/config.

## Ghi chú pháp lý

Project chỉ cung cấp công cụ phát nội dung theo link do người dùng nhập vào.
Bạn tự chịu trách nhiệm về quyền sử dụng nội dung và việc tuân thủ điều khoản của nền tảng nguồn.
