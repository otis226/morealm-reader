#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch(rel: str, replacements: list[tuple[str, str]]) -> None:
    p = ROOT / rel
    s = p.read_text(encoding="utf-8")
    changed = 0
    for old, new in replacements:
        if old in s:
            s = s.replace(old, new, 1)
            changed += 1
        elif new in s:
            print(f"already patched: {rel}: {new[:70]!r}")
        else:
            raise RuntimeError(f"pattern not found in {rel}: {old[:120]!r}")
    p.write_text(s, encoding="utf-8")
    print(f"patched {rel}: {changed}/{len(replacements)}")


# ---------------------------------------------------------------------------
# 1) System TTS: stop forcing Chinese and expose all installed voices.
# ---------------------------------------------------------------------------
patch(
    "app/src/main/java/com/morealm/app/domain/tts/SystemTtsEngine.kt",
    [
        (
            "tts?.setLanguage(Locale.CHINESE) ?: TextToSpeech.LANG_NOT_SUPPORTED",
            "tts?.setLanguage(Locale.getDefault()) ?: TextToSpeech.LANG_NOT_SUPPORTED",
        ),
        (
            'com.morealm.app.core.log.AppLog.warn("TTS", "setLanguage(CHINESE) threw: ${e.message}")',
            'com.morealm.app.core.log.AppLog.warn("TTS", "setLanguage(default) threw: ${e.message}")',
        ),
        (
            'com.morealm.app.core.log.AppLog.info("TTS", "SystemTtsEngine.setLanguage → langStatus=$langStatus")',
            'com.morealm.app.core.log.AppLog.info("TTS", "SystemTtsEngine.setLanguage(default=${Locale.getDefault()}) → langStatus=$langStatus")',
        ),
        (
            'InitResult.Failed("中文语音数据缺失，请到系统「设置 → 语言与输入 → 文字转语音」安装语音包")',
            'InitResult.Failed("Thiếu dữ liệu giọng đọc. Hãy cài thêm voice trong cài đặt TTS của Android")',
        ),
        (
            'InitResult.Failed("当前 TTS 引擎不支持中文，请更换或安装一个支持中文的引擎")',
            'InitResult.Failed("Engine TTS hiện tại không hỗ trợ ngôn ngữ hệ thống. Hãy đổi engine hoặc cài thêm voice")',
        ),
        (
            'engine.language = Locale.CHINESE\n            return',
            'engine.language = Locale.getDefault()\n            return',
        ),
        (
            '"falling back to setLanguage(CHINESE)",',
            '"falling back to setLanguage(default)",',
        ),
        (
            'engine.language = Locale.CHINESE\n        }',
            'engine.language = Locale.getDefault()\n        }',
        ),
        (
            '"SystemTtsEngine.speak: voice unhealthy (null=${v == null}, notInstalled=$notInstalled), resetting to CHINESE",',
            '"SystemTtsEngine.speak: voice unhealthy (null=${v == null}, notInstalled=$notInstalled), resetting to default locale",',
        ),
    ],
)

# ---------------------------------------------------------------------------
# 2) TTS host: do not mix System and Edge voice preferences.
#    Prefer Vietnamese voices, and use Edge per-paragraph SSML (supported shape).
# ---------------------------------------------------------------------------
host = ROOT / "app/src/main/java/com/morealm/app/service/TtsEngineHost.kt"
s = host.read_text(encoding="utf-8")

old_start = '''            engineId = prefs.ttsEngine.first()
            voiceName = savedVoiceForEngine(engineId)
            applyVoiceToEngine()
            val voices = loadVoicesForEngine(engineId)
            TtsEventBus.updatePlayback {
                copy(
                    speed = speed,
                    engine = engineId,
                    voiceName = voiceName,
                    voices = voices,
                )
            }'''
new_start = '''            engineId = prefs.ttsEngine.first()
            val voices = loadVoicesForEngine(engineId)
            val savedVoice = savedVoiceForEngine(engineId)
            voiceName = resolveVoiceOrEmpty(savedVoice, voices)
            if (engineId == "edge" && voiceName.isBlank()) {
                voiceName = voices.firstOrNull { it.id == "vi-VN-HoaiMyNeural" }?.id
                    ?: voices.firstOrNull { it.language.startsWith("vi", ignoreCase = true) }?.id
                    ?: "vi-VN-HoaiMyNeural"
            }
            applyVoiceToEngine()
            TtsEventBus.updatePlayback {
                copy(
                    speed = speed,
                    engine = engineId,
                    voiceName = voiceName,
                    voices = voices,
                )
            }'''
if old_start not in s:
    raise RuntimeError("start() TTS voice block not found")
s = s.replace(old_start, new_start, 1)

old_set = '''                voiceName = savedVoiceForEngine(resolvedEngine)
                applyVoiceToEngine()
                val voices = loadVoicesForEngine(resolvedEngine)
                TtsEventBus.updatePlayback {
                    copy(engine = resolvedEngine, voiceName = voiceName, voices = voices)
                }'''
new_set = '''                val voices = loadVoicesForEngine(resolvedEngine)
                val savedVoice = savedVoiceForEngine(resolvedEngine)
                voiceName = resolveVoiceOrEmpty(savedVoice, voices)
                if (resolvedEngine == "edge" && voiceName.isBlank()) {
                    voiceName = voices.firstOrNull { it.id == "vi-VN-HoaiMyNeural" }?.id
                        ?: voices.firstOrNull { it.language.startsWith("vi", ignoreCase = true) }?.id
                        ?: "vi-VN-HoaiMyNeural"
                }
                applyVoiceToEngine()
                saveVoiceForEngine(resolvedEngine, voiceName)
                TtsEventBus.updatePlayback {
                    copy(engine = resolvedEngine, voiceName = voiceName, voices = voices)
                }'''
if old_set not in s:
    raise RuntimeError("setEngine() voice block not found")
s = s.replace(old_set, new_set, 1)

old_edge_route = '''        if (engine is EdgeTtsEngine) {
            runEdgeChapterPlayback(engine)
            return
        }
'''
if old_edge_route not in s:
    raise RuntimeError("Edge chapter route not found")
s = s.replace(
    old_edge_route,
    '''        // Edge is intentionally kept on the per-paragraph path. Microsoft now only
        // accepts Edge-compatible SSML (single voice + single prosody); MoRealm's old
        // chapter SSML inserted custom <break> tags and can be rejected by the service.
''',
    1,
)

old_edge_voices = '''                runCatching { edgeTtsEngine.fetchRemoteVoices() }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?: EdgeTtsEngine.VOICES'''
new_edge_voices = '''                (runCatching { edgeTtsEngine.fetchRemoteVoices() }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?: EdgeTtsEngine.VOICES)
                    .sortedWith(compareBy<TtsVoice>(
                        { when {
                            it.language.startsWith("vi", ignoreCase = true) -> 0
                            it.language.startsWith("en", ignoreCase = true) -> 1
                            it.language.startsWith("zh", ignoreCase = true) -> 3
                            else -> 2
                        } },
                        { it.language },
                        { it.id },
                    ))'''
if old_edge_voices not in s:
    raise RuntimeError("Edge voice loader block not found")
s = s.replace(old_edge_voices, new_edge_voices, 1)

old_system_loader = '                    SystemTtsEngine.InitResult.Success -> sys.getChineseVoices()'
new_system_loader = '''                    SystemTtsEngine.InitResult.Success -> sys.getVoices()
                        .sortedWith(compareBy<TtsVoice>(
                            { when {
                                it.language.contains("Việt", ignoreCase = true) ||
                                    it.language.startsWith("vi", ignoreCase = true) -> 0
                                it.language.contains("Anh", ignoreCase = true) ||
                                    it.language.contains("English", ignoreCase = true) ||
                                    it.language.startsWith("en", ignoreCase = true) -> 1
                                else -> 2
                            } },
                            { it.language },
                            { it.id },
                        ))'''
if old_system_loader not in s:
    raise RuntimeError("System voice loader block not found")
s = s.replace(old_system_loader, new_system_loader, 1)

old_saved = '''    private suspend fun savedVoiceForEngine(engine: String): String {
        val saved = if (engine == "edge") prefs.ttsEdgeVoice.first() else prefs.ttsSystemVoice.first()
        return saved.ifBlank { prefs.ttsVoice.first() }
    }'''
new_saved = '''    private suspend fun savedVoiceForEngine(engine: String): String {
        // Never reuse the legacy shared ttsVoice across engines. A System voice id such as
        // cmn-cn-x-ccd-local is not a valid Microsoft Edge ShortName and made Edge silent.
        return when {
            engine == "edge" -> prefs.ttsEdgeVoice.first()
            engine == "system" -> prefs.ttsSystemVoice.first()
            else -> ""
        }
    }'''
if old_saved not in s:
    raise RuntimeError("savedVoiceForEngine block not found")
s = s.replace(old_saved, new_saved, 1)

host.write_text(s, encoding="utf-8")
print("patched TtsEngineHost.kt")

# ---------------------------------------------------------------------------
# 3) Edge TTS: Vietnamese fallback voices + current Edge-compatible protocol.
# ---------------------------------------------------------------------------
edge = ROOT / "app/src/main/java/com/morealm/app/domain/tts/EdgeTtsEngine.kt"
s = edge.read_text(encoding="utf-8")

old_ssml = '''        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' " +
            "xml:lang='zh-CN'>" +
            "<voice name='$voice'>" +
            "<prosody rate='$rate' pitch='$pitch'>$escaped</prosody>" +
            "</voice>" +
            "</speak>"'''
new_ssml = '''        // Match current edge-tts: Microsoft validates the Edge-generated SSML shape.
        // xml:lang stays en-US; the selected voice ShortName determines the actual language.
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' " +
            "xml:lang='en-US'>" +
            "<voice name='$voice'>" +
            "<prosody pitch='$pitch' rate='$rate' volume='+0%'>$escaped</prosody>" +
            "</voice>" +
            "</speak>"'''
if old_ssml not in s:
    raise RuntimeError("buildSsml block not found")
s = s.replace(old_ssml, new_ssml, 1)

old_config = '''                val config = "Content-Type:application/json; charset=utf-8\\r\\n" +
                    "Path:speech.config\\r\\n\\r\\n" +'''
new_config = '''                val config = "X-Timestamp:${rfc1123Now()}\\r\\n" +
                    "Content-Type:application/json; charset=utf-8\\r\\n" +
                    "Path:speech.config\\r\\n\\r\\n" +'''
if old_config not in s:
    raise RuntimeError("speech.config block not found")
s = s.replace(old_config, new_config, 1)

old_date = '''        val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())'''
new_date = '''        // Javascript-style timestamp used by Microsoft Edge / rany2 edge-tts.
        val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())'''
if old_date not in s:
    raise RuntimeError("timestamp formatter block not found")
s = s.replace(old_date, new_date, 1)

old_sort = '''            // 排序：zh- 系列优先，其余按 locale 字母序，相同 locale 内按 id 字母序
            list.sortedWith(
                compareBy(
                    { if (it.language.startsWith("zh", ignoreCase = true)) 0 else 1 },
                    { it.language },
                    { it.id },
                )
            )'''
new_sort = '''            // Vietnamese first for this fork, English next; keep Chinese available later.
            list.sortedWith(
                compareBy(
                    { when {
                        it.language.startsWith("vi", ignoreCase = true) -> 0
                        it.language.startsWith("en", ignoreCase = true) -> 1
                        it.language.startsWith("zh", ignoreCase = true) -> 3
                        else -> 2
                    } },
                    { it.language },
                    { it.id },
                )
            )'''
if old_sort not in s:
    raise RuntimeError("remote voice sorting block not found")
s = s.replace(old_sort, new_sort, 1)

s = s.replace('"Female" -> "女声"', '"Female" -> "Nữ"', 1)
s = s.replace('"Male" -> "男声"', '"Male" -> "Nam"', 1)

old_hardcoded = '''        val HARDCODED_VOICES = listOf(
            TtsVoice("zh-CN-XiaoxiaoNeural", "晓晓 (女声·温暖)", "zh-CN", "edge"),'''
new_hardcoded = '''        val HARDCODED_VOICES = listOf(
            // Always available as local fallback even when voices/list is blocked.
            TtsVoice("vi-VN-HoaiMyNeural", "Hoài My (Nữ · Tiếng Việt)", "vi-VN", "edge"),
            TtsVoice("vi-VN-NamMinhNeural", "Nam Minh (Nam · Tiếng Việt)", "vi-VN", "edge"),
            TtsVoice("en-US-EmmaMultilingualNeural", "Emma (Nữ · English US)", "en-US", "edge"),
            TtsVoice("en-US-AndrewMultilingualNeural", "Andrew (Nam · English US)", "en-US", "edge"),
            TtsVoice("zh-CN-XiaoxiaoNeural", "晓晓 (女声·温暖)", "zh-CN", "edge"),'''
if old_hardcoded not in s:
    raise RuntimeError("HARDCODED_VOICES insertion point not found")
s = s.replace(old_hardcoded, new_hardcoded, 1)

edge.write_text(s, encoding="utf-8")
print("patched EdgeTtsEngine.kt")

# Voice menu: show enough voices after Vietnamese/English prioritization.
panel = ROOT / "app/src/main/java/com/morealm/app/ui/reader/TtsPanel.kt"
s = panel.read_text(encoding="utf-8")
if "voices.take(30).forEach" in s:
    s = s.replace("voices.take(30).forEach", "voices.take(80).forEach", 1)
panel.write_text(s, encoding="utf-8")
print("patched TtsPanel voice menu")

print("Vietnamese TTS functional fixes applied")
