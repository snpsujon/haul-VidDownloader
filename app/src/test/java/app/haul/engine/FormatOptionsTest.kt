package app.haul.engine

import app.haul.data.AudioFormat
import app.haul.data.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatOptionsTest {
    private val info = MediaInfo.parse(javaClass.classLoader!!.getResource("video.json")!!.readText(), "https://vimeo.com/1")

    @Test fun mp4ChoicesPreferAvcPlusM4a() {
        val choices = FormatOptions.videoChoices(info, "mp4")
        assertEquals(listOf(2160, 1080, 720, 360), choices.map { it.height })
        val hd = choices.first { it.height == 1080 }
        assertTrue(hd.selector.startsWith("137+140/"))
        assertEquals(412_000_000L + 26_000_000L, hd.sizeBytes)
        assertEquals("AVC + AAC", hd.codecs)
        assertEquals("2160p · 4K", choices.first().label)
        // 360p only exists as a combined format.
        assertTrue(choices.first { it.height == 360 }.selector.startsWith("18/"))
    }

    @Test fun webmChoicesPreferVp9PlusOpus() {
        val hd = FormatOptions.videoChoices(info, "webm").first { it.height == 1080 }
        assertTrue(hd.selector.startsWith("248+251/"))
        assertEquals("VP9 + OPUS", hd.codecs)
    }

    @Test fun recommendedAndDefaultHeights() {
        val choices = FormatOptions.videoChoices(info, "mp4")
        assertEquals(1080, FormatOptions.recommendedHeight(choices))
        assertEquals(720, FormatOptions.defaultHeight(choices, 720))
        assertEquals(720, FormatOptions.defaultHeight(choices, 900))
        assertEquals(360, FormatOptions.defaultHeight(choices, 240))
    }

    @Test fun audioChoices() {
        val a = FormatOptions.audioChoices(info, 320)
        assertEquals(listOf(AudioFormat.MP3, AudioFormat.M4A, AudioFormat.OPUS), a.map { it.format })
        assertEquals(2487L * 320 * 1000 / 8, a[0].sizeBytes)
        assertEquals("FASTEST", a[1].badge)
        assertTrue(a[1].selector.startsWith("140/"))
        assertTrue(a[2].selector.startsWith("251/"))
    }

    @Test fun customRequests() {
        val v = info.formats.first { it.id == "137" }
        val a = info.formats.first { it.id == "140" }
        val r = FormatOptions.customRequest(info, v, a)!!
        assertEquals("137+140", r.selector)
        assertEquals("mp4", r.container)
        assertEquals("1080P MP4", r.formatLabel)
        assertEquals(438_000_000L, r.estimatedBytes)

        val webmV = info.formats.first { it.id == "248" }
        assertEquals("mkv", FormatOptions.mergeContainer(webmV, a))
        val audioOnly = FormatOptions.customRequest(info, null, a)!!
        assertEquals(MediaType.AUDIO, audioOnly.mediaType)
        assertNull(FormatOptions.customRequest(info, null, null))
    }

    @Test fun presets() {
        val v = FormatOptions.presetVideoRequest(1080, "mp4", 600)
        assertEquals("1080P MP4", v.formatLabel)
        assertTrue(v.selector.contains("height<=1080"))
        assertEquals(600L * 2800 * 1000 / 8, v.estimatedBytes)
        val a = FormatOptions.presetAudioRequest(AudioFormat.OPUS, 320, embedCover = true, durationSec = 60)
        assertEquals("OPUS", a.formatLabel)
        // Opus files can't carry embedded cover art.
        assertEquals(false, a.embedCover)
        assertNotNull(a.codecs)
    }
    @Test fun audioLanguages() {
        val json = """
            {"title": "Dubbed", "duration": 100, "formats": [
              {"format_id": "137", "ext": "mp4", "vcodec": "avc1.640028", "acodec": "none", "height": 1080, "tbr": 2000},
              {"format_id": "140-0", "ext": "m4a", "vcodec": "none", "acodec": "mp4a.40.2", "abr": 129,
               "language": "hi", "language_preference": -1, "format_note": "Hindi, medium"},
              {"format_id": "140-1", "ext": "m4a", "vcodec": "none", "acodec": "mp4a.40.2", "abr": 129,
               "language": "en", "language_preference": 10, "format_note": "English original (default), medium"}
            ]}
        """
        val dubbed = MediaInfo.parse(json, "https://youtube.com/watch?v=x")
        val tracks = FormatOptions.audioTracks(dubbed)
        assertEquals(listOf("en", "hi"), tracks.map { it.language })
        assertTrue(tracks[0].original)

        val hindi = FormatOptions.videoChoices(dubbed, "mp4", "hi").first()
        assertTrue(hindi.selector.startsWith("137+140-0/137+bestaudio[language=hi]/"))
        val m4a = FormatOptions.audioChoices(dubbed, 320, "hi").first { it.format == AudioFormat.M4A }
        assertTrue(m4a.selector.startsWith("140-0/"))
        // A single-language video offers no picker.
        assertTrue(FormatOptions.audioTracks(info).isEmpty())
    }
}
