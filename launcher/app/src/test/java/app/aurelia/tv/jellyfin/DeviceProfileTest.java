package app.aurelia.tv.jellyfin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DeviceProfileTest {

    private static final List<String> STEREO = Arrays.asList("aac", "mp3", "opus", "flac");

    @Test
    public void directPlayListsWhatTheTvDecodes() throws Exception {
        JSONObject p = DeviceProfile.build(
                Arrays.asList(new DeviceProfile.VideoCodec("h264", 1920, 1080, false),
                        new DeviceProfile.VideoCodec("hevc", 3840, 2160, true)),
                Arrays.asList("aac", "eac3"), Arrays.asList("SDR", "HDR10"));
        JSONObject video = p.getJSONArray("DirectPlayProfiles").getJSONObject(0);
        assertEquals("h264,hevc", video.getString("VideoCodec"));
        assertEquals("aac,eac3", video.getString("AudioCodec"));
        assertTrue(video.getString("Container").contains("mkv"));
    }

    @Test
    public void eachCodecIsLimitedToWhatItsDecoderTakes() throws Exception {
        JSONObject p = DeviceProfile.build(
                Arrays.asList(new DeviceProfile.VideoCodec("h264", 1920, 1080, false),
                        new DeviceProfile.VideoCodec("hevc", 3840, 2160, true)),
                STEREO, Arrays.asList("SDR", "HDR10"));
        JSONArray codecs = p.getJSONArray("CodecProfiles");
        assertEquals("1080", value(codecs.getJSONObject(0), "Height"));
        assertEquals("8", value(codecs.getJSONObject(0), "VideoBitDepth"));
        assertEquals("SDR", value(codecs.getJSONObject(0), "VideoRangeType"));
        assertEquals("2160", value(codecs.getJSONObject(1), "Height"));
        assertEquals("10", value(codecs.getJSONObject(1), "VideoBitDepth"));
        assertEquals("SDR|HDR10", value(codecs.getJSONObject(1), "VideoRangeType"));
    }

    @Test
    public void convertsToH264AndKeepsOnlyAudioTheTvPlays() throws Exception {
        JSONObject p = DeviceProfile.build(
                Collections.singletonList(new DeviceProfile.VideoCodec("h264", 1920, 1080, false)),
                STEREO, Collections.singletonList("SDR"));
        JSONObject hls = p.getJSONArray("TranscodingProfiles").getJSONObject(0);
        assertEquals("hls", hls.getString("Protocol"));
        assertEquals("h264", hls.getString("VideoCodec"));
        assertEquals("aac,mp3", hls.getString("AudioCodec"));
    }

    @Test
    public void pictureSubtitlesAreBurnedInOnlyWhenConverting() throws Exception {
        JSONObject p = DeviceProfile.build(
                Collections.singletonList(new DeviceProfile.VideoCodec("h264", 1920, 1080, false)),
                STEREO, Collections.singletonList("SDR"));
        JSONArray subs = p.getJSONArray("SubtitleProfiles");
        boolean embedded = false;
        boolean burned = false;
        for (int i = 0; i < subs.length(); i++) {
            JSONObject s = subs.getJSONObject(i);
            if (!s.getString("Format").equals("pgssub")) continue;
            if (s.getString("Method").equals("Embed")) embedded = true;
            if (s.getString("Method").equals("Encode")) burned = true;
        }
        assertTrue(embedded);
        assertTrue(burned);
    }

    @Test
    public void withoutSidecarsTextSubtitlesAreBurnedIn() throws Exception {
        List<DeviceProfile.VideoCodec> video =
                Collections.singletonList(new DeviceProfile.VideoCodec("h264", 1920, 1080, false));
        assertTrue(methods(DeviceProfile.build(video, STEREO, Collections.singletonList("SDR"), true), "subrip")
                .contains("External"));
        List<String> without = methods(DeviceProfile.build(video, STEREO, Collections.singletonList("SDR"), false),
                "subrip");
        assertTrue(without.contains("Encode"));
        assertTrue(without.contains("Embed"));
        assertTrue(!without.contains("External"));
    }

    private static List<String> methods(JSONObject profile, String format) throws Exception {
        JSONArray subs = profile.getJSONArray("SubtitleProfiles");
        List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < subs.length(); i++) {
            JSONObject s = subs.getJSONObject(i);
            if (s.getString("Format").equals(format)) out.add(s.getString("Method"));
        }
        return out;
    }

    private static String value(JSONObject codec, String property) throws Exception {
        JSONArray conditions = codec.getJSONArray("Conditions");
        for (int i = 0; i < conditions.length(); i++) {
            JSONObject c = conditions.getJSONObject(i);
            if (c.getString("Property").equals(property)) return c.getString("Value");
        }
        return null;
    }
}
