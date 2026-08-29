#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def write(rel: str, text: str) -> None:
    path = ROOT / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    print(f"updated: {rel}")


# Vietnamese resources used by the parts already resourceized upstream.
write(
    "app/src/main/res/values-vi/strings.xml",
    r'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">MoRealm</string>
    <string name="app_description">Trình đọc sách cục bộ · Bộ máy dàn trang tùy chỉnh</string>
    <string name="privacy_notice">MoRealm là công cụ đọc nội dung số cục bộ và không cung cấp nội dung. Người dùng chịu trách nhiệm đối với mọi tệp và cấu hình được nhập vào ứng dụng.</string>

    <string name="yes">Có</string>
    <string name="no">Không</string>
    <string name="create">Tạo</string>
    <string name="cancel">Hủy</string>
    <string name="open">Mở</string>
    <string name="save">Lưu</string>
    <string name="share">Chia sẻ</string>
    <string name="allow">Cho phép</string>
    <string name="deny">Từ chối</string>
    <string name="confirmation_enable">Bật</string>
    <string name="confirmation_disable">Tắt</string>

    <string name="tab_shelf">Tủ sách</string>
    <string name="search_shelf">Tìm sách trong tủ sách.</string>
    <string name="empty_shelf">Tủ sách đang trống\nHãy nhập một ebook để bắt đầu đọc</string>
    <string name="import_local">Nhập sách từ máy</string>
    <string name="import_folder">Nhập thư mục sách</string>
    <string name="continue_reading">Đọc tiếp</string>
    <string name="shortcut_continue">Đọc tiếp</string>
    <string name="shortcut_continue_long">Tiếp tục cuốn sách đọc gần nhất</string>
    <string name="tab_explore">Khám phá</string>
    <string name="tab_listen">Nghe</string>
    <string name="tts_playing">Đang phát</string>
    <string name="tab_profile">Cá nhân</string>
    <string name="theme_settings">Cài đặt giao diện</string>
    <string name="custom_theme">Giao diện tùy chỉnh</string>
    <string name="custom_theme_desc">Chưa có giao diện tùy chỉnh. Nhấn bên dưới để tạo hoặc nhập.</string>
    <string name="content_rules">Regex phân tích nội dung</string>
    <string name="content_rules_desc">Quản lý cấu hình phân tích nội dung tùy chỉnh</string>
    <string name="webdav_sync">Đồng bộ WebDAV</string>
    <string name="data_migration">Di chuyển dữ liệu</string>
    <string name="advanced_settings">Cài đặt nâng cao</string>
    <string name="widget_continue_reading_label">MoRealm · Đọc tiếp</string>
    <string name="widget_continue_reading_title">📖 Đọc tiếp</string>
    <string name="widget_continue_reading_desc">Hiển thị cuốn sách đọc gần nhất và thời gian đọc hôm nay; nhấn để tiếp tục</string>
    <string name="widget_preview_book_title">Mở trang đọc gần nhất</string>
    <string name="widget_loading">Đang tải…</string>
    <string name="widget_empty_title">Chưa có lịch sử đọc</string>
    <string name="widget_empty_subtitle">Mở MoRealm, chọn một cuốn sách và bắt đầu đọc</string>
    <string name="widget_today_read_format">Hôm nay đã đọc %1$s</string>

    <string name="tts_paragraph_progress">%1$d / %2$d đoạn</string>
    <string name="tts_prev_chapter">Chương trước</string>
    <string name="tts_prev_paragraph">Đoạn trước</string>
    <string name="tts_pause">Tạm dừng</string>
    <string name="tts_play">Phát</string>
    <string name="tts_next_paragraph">Đoạn sau</string>
    <string name="tts_next_chapter">Chương sau</string>
    <string name="tts_speed">Tốc độ</string>
    <string name="tts_voice">Giọng đọc</string>
    <string name="tts_default_voice">Mặc định</string>
    <string name="tts_system_engine">Hệ thống</string>
    <string name="tts_system_engine_label">Bộ máy TTS hệ thống</string>
    <string name="tts_follow_system_default">Theo mặc định hệ thống</string>
    <string name="tts_edge_engine">Edge</string>
    <string name="tts_sleep_timer">Hẹn giờ dừng</string>
    <string name="tts_no_timer">Không hẹn giờ</string>
    <string name="tts_15_minutes">15 phút</string>
    <string name="tts_30_minutes">30 phút</string>
    <string name="tts_1_hour">1 giờ</string>
    <string name="tts_1_5_hours">1,5 giờ</string>
    <string name="tts_stop_after_minutes">Dừng sau %1$d phút</string>
    <string name="tts_stop">Dừng</string>
</resources>
''',
)

# Separate resource file: no need to rewrite upstream strings.xml.
def overlay(path: str, body: str) -> None:
    write(path, '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' + body.strip() + '\n</resources>\n')


overlay(
    "app/src/main/res/values/strings_tts_overlay.xml",
    r'''
    <string name="tts_paragraph_progress">%1$d / %2$d 段</string>
    <string name="tts_prev_chapter">上一章</string>
    <string name="tts_prev_paragraph">上一段</string>
    <string name="tts_pause">暂停</string>
    <string name="tts_play">播放</string>
    <string name="tts_next_paragraph">下一段</string>
    <string name="tts_next_chapter">下一章</string>
    <string name="tts_speed">语速</string>
    <string name="tts_voice">语音</string>
    <string name="tts_default_voice">默认</string>
    <string name="tts_system_engine">系统</string>
    <string name="tts_system_engine_label">系统引擎</string>
    <string name="tts_follow_system_default">跟随系统默认</string>
    <string name="tts_edge_engine">Edge</string>
    <string name="tts_sleep_timer">定时关闭</string>
    <string name="tts_no_timer">不定时</string>
    <string name="tts_15_minutes">15分钟</string>
    <string name="tts_30_minutes">30分钟</string>
    <string name="tts_1_hour">1小时</string>
    <string name="tts_1_5_hours">1.5小时</string>
    <string name="tts_stop_after_minutes">%1$d分后停</string>
    <string name="tts_stop">停止</string>
''',
)

overlay(
    "app/src/main/res/values-en/strings_tts_overlay.xml",
    r'''
    <string name="tts_paragraph_progress">%1$d / %2$d paragraphs</string>
    <string name="tts_prev_chapter">Previous chapter</string>
    <string name="tts_prev_paragraph">Previous paragraph</string>
    <string name="tts_pause">Pause</string>
    <string name="tts_play">Play</string>
    <string name="tts_next_paragraph">Next paragraph</string>
    <string name="tts_next_chapter">Next chapter</string>
    <string name="tts_speed">Speed</string>
    <string name="tts_voice">Voice</string>
    <string name="tts_default_voice">Default</string>
    <string name="tts_system_engine">System</string>
    <string name="tts_system_engine_label">System TTS engine</string>
    <string name="tts_follow_system_default">Follow system default</string>
    <string name="tts_edge_engine">Edge</string>
    <string name="tts_sleep_timer">Sleep timer</string>
    <string name="tts_no_timer">No timer</string>
    <string name="tts_15_minutes">15 minutes</string>
    <string name="tts_30_minutes">30 minutes</string>
    <string name="tts_1_hour">1 hour</string>
    <string name="tts_1_5_hours">1.5 hours</string>
    <string name="tts_stop_after_minutes">Stop after %1$d min</string>
    <string name="tts_stop">Stop</string>
''',
)

# Android 13+ per-app language list.
write(
    "app/src/main/res/xml/locales_config.xml",
    r'''<?xml version="1.0" encoding="utf-8"?>
<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
    <locale android:name="vi" />
    <locale android:name="en" />
    <locale android:name="zh-CN" />
    <locale android:name="zh-TW" />
</locale-config>
''',
)

manifest_rel = "app/src/main/AndroidManifest.xml"
manifest = read(manifest_rel)
if 'android:localeConfig="@xml/locales_config"' not in manifest:
    marker = '        android:label="@string/app_name"\n'
    if marker not in manifest:
        raise RuntimeError("android:label marker not found")
    manifest = manifest.replace(marker, marker + '        android:localeConfig="@xml/locales_config"\n', 1)
    write(manifest_rel, manifest)

# TTS panel: replace the user-facing hard-coded Chinese with string resources.
tts_rel = "app/src/main/java/com/morealm/app/ui/reader/TtsPanel.kt"
tts = read(tts_rel)
if "import androidx.compose.ui.res.stringResource" not in tts:
    tts = tts.replace("import androidx.compose.ui.draw.clip\n", "import androidx.compose.ui.draw.clip\nimport androidx.compose.ui.res.stringResource\n", 1)
if "import com.morealm.app.R" not in tts:
    tts = tts.replace("import com.morealm.app.domain.entity.TtsVoice\n", "import com.morealm.app.R\nimport com.morealm.app.domain.entity.TtsVoice\n", 1)

replacements = [
    ('Text("${currentParagraph + 1} / $totalParagraphs 段",', 'Text(stringResource(R.string.tts_paragraph_progress, currentParagraph + 1, totalParagraphs),'),
    ('Icon(Icons.Default.SkipPrevious, "上一章",', 'Icon(Icons.Default.SkipPrevious, stringResource(R.string.tts_prev_chapter),'),
    ('Icon(Icons.Default.FastRewind, "上一段",', 'Icon(Icons.Default.FastRewind, stringResource(R.string.tts_prev_paragraph),'),
    ('contentDescription = if (isPlaying) "暂停" else "播放",', 'contentDescription = if (isPlaying) stringResource(R.string.tts_pause) else stringResource(R.string.tts_play),'),
    ('Icon(Icons.Default.FastForward, "下一段",', 'Icon(Icons.Default.FastForward, stringResource(R.string.tts_next_paragraph),'),
    ('Icon(Icons.Default.SkipNext, "下一章",', 'Icon(Icons.Default.SkipNext, stringResource(R.string.tts_next_chapter),'),
    ('Text("语速", style = MaterialTheme.typography.labelMedium,', 'Text(stringResource(R.string.tts_speed), style = MaterialTheme.typography.labelMedium,'),
    ('Text("语音", style = MaterialTheme.typography.labelMedium,', 'Text(stringResource(R.string.tts_voice), style = MaterialTheme.typography.labelMedium,'),
    ('val displayName = if (selectedVoice.isBlank()) "默认"', 'val displayName = if (selectedVoice.isBlank()) stringResource(R.string.tts_default_voice)'),
    ('Text("默认", fontWeight = if (selectedVoice.isBlank()) FontWeight.Bold else FontWeight.Normal)', 'Text(stringResource(R.string.tts_default_voice), fontWeight = if (selectedVoice.isBlank()) FontWeight.Bold else FontWeight.Normal)'),
    ('?: systemEngineState.selectedPackage.ifBlank { "跟随系统默认" }', '?: systemEngineState.selectedPackage.ifBlank { stringResource(R.string.tts_follow_system_default) }'),
    ('                        "系统引擎",', '                        stringResource(R.string.tts_system_engine_label),'),
    ('listOf("system" to "系统", "edge" to "Edge").forEach { (id, label) ->', 'listOf("system" to stringResource(R.string.tts_system_engine), "edge" to stringResource(R.string.tts_edge_engine)).forEach { (id, label) ->'),
    ('                                "定时关闭",', '                                stringResource(R.string.tts_sleep_timer),'),
    ('listOf(0 to "不定时", 15 to "15分钟", 30 to "30分钟",\n                                   60 to "1小时", 90 to "1.5小时").forEach { (min, label) ->', 'listOf(\n                                0 to stringResource(R.string.tts_no_timer),\n                                15 to stringResource(R.string.tts_15_minutes),\n                                30 to stringResource(R.string.tts_30_minutes),\n                                60 to stringResource(R.string.tts_1_hour),\n                                90 to stringResource(R.string.tts_1_5_hours),\n                            ).forEach { (min, label) ->'),
    ('Text("${sleepMinutes}分后停",', 'Text(stringResource(R.string.tts_stop_after_minutes, sleepMinutes),'),
    ('Icon(Icons.Default.Stop, "停止",', 'Icon(Icons.Default.Stop, stringResource(R.string.tts_stop),'),
]

missing = []
for old, new in replacements:
    if old in tts:
        tts = tts.replace(old, new, 1)
    elif new not in tts:
        missing.append(old)

if missing:
    raise RuntimeError("TtsPanel patterns changed upstream: " + " | ".join(missing))
write(tts_rel, tts)

print("Vietnamese build patch applied successfully")
