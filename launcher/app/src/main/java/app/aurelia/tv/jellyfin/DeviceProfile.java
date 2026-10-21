package app.aurelia.tv.jellyfin;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.AudioCapabilities;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What this TV can play, told to the server with every PlaybackInfo request so it can choose: play the file as it
 * is, repackage it, or convert it. Worked out from the TV itself rather than a fixed list: the video decoders it
 * has (hardware only, apart from codecs light enough for software), the largest picture each decodes, whether
 * they take 10-bit, which HDR the screen shows, and which surround formats reach the speakers.
 */
public final class DeviceProfile {

    /** Fast enough for anything on a home network; the server lowers it only when converting. */
    public static final int MAX_BITRATE = 120_000_000;

    /** Surround formats the TV may only pass through to an amplifier rather than decode. */
    private static final List<String> PASSTHROUGH = Arrays.asList("ac3", "eac3", "dts", "truehd");

    /** Containers ExoPlayer reads. */
    private static final String CONTAINERS = "mp4,m4v,mov,mkv,webm,ts,mpegts,m2ts";

    private DeviceProfile() { }

    /** One video codec, as the TV decodes it. */
    static final class VideoCodec {
        final String name;
        final int maxWidth;
        final int maxHeight;
        final boolean tenBit;

        VideoCodec(String name, int maxWidth, int maxHeight, boolean tenBit) {
            this.name = name;
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
            this.tenBit = tenBit;
        }
    }

    /**
     * {@code compatibleAudio}: leave out the surround formats the TV only passes through to the speakers, so the
     * server converts them. Passed-through audio cannot change speed. {@code sidecars}: text subtitles may come
     * as files alongside a converted stream; without, they are burned into it.
     */
    public static JSONObject build(Context context, boolean compatibleAudio, boolean sidecars) {
        try {
            List<String> audio = audioCodecs(context);
            if (compatibleAudio) audio.removeAll(PASSTHROUGH);
            return build(videoCodecs(), audio, hdrRanges(context), sidecars);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The profile for a TV with these codecs; separate from the TV so it can be tested. */
    static JSONObject build(List<VideoCodec> video, List<String> audio, List<String> ranges) throws JSONException {
        return build(video, audio, ranges, true);
    }

    static JSONObject build(List<VideoCodec> video, List<String> audio, List<String> ranges, boolean sidecars)
            throws JSONException {
        String videoNames = join(names(video));
        String audioNames = join(audio);

        JSONArray directPlay = new JSONArray()
                .put(new JSONObject().put("Type", "Video").put("Container", CONTAINERS)
                        .put("VideoCodec", videoNames).put("AudioCodec", audioNames))
                .put(new JSONObject().put("Type", "Audio").put("Container", "mp3,aac,m4a,flac,ogg,opus,wav"));

        // Converting: H.264 for any TV, HEVC kept when the source is already HEVC (copied, not encoded).
        List<String> hlsVideo = new ArrayList<>();
        hlsVideo.add("h264");
        if (names(video).contains("hevc")) hlsVideo.add("hevc");
        List<String> hlsAudio = new ArrayList<>();
        for (String a : new String[] {"aac", "ac3", "eac3", "mp3"}) if (audio.contains(a)) hlsAudio.add(a);
        JSONArray transcoding = new JSONArray()
                .put(new JSONObject().put("Type", "Video").put("Container", "ts").put("Protocol", "hls")
                        .put("Context", "Streaming").put("VideoCodec", join(hlsVideo)).put("AudioCodec", join(hlsAudio))
                        .put("MaxAudioChannels", "6").put("MinSegments", 1).put("BreakOnNonKeyFrames", true))
                .put(new JSONObject().put("Type", "Audio").put("Container", "mp3").put("Protocol", "http")
                        .put("Context", "Streaming").put("AudioCodec", "mp3"));

        JSONArray codecs = new JSONArray();
        for (VideoCodec c : video) {
            JSONArray conditions = new JSONArray()
                    .put(condition("LessThanEqual", "Width", String.valueOf(c.maxWidth)))
                    .put(condition("LessThanEqual", "Height", String.valueOf(c.maxHeight)))
                    .put(condition("LessThanEqual", "VideoBitDepth", c.tenBit ? "10" : "8"));
            if (c.name.equals("hevc") || c.name.equals("av1") || c.name.equals("vp9")) {
                conditions.put(condition("EqualsAny", "VideoRangeType", String.join("|", ranges)));
            } else {
                conditions.put(condition("EqualsAny", "VideoRangeType", "SDR"));
            }
            codecs.put(new JSONObject().put("Type", "Video").put("Codec", c.name).put("Conditions", conditions));
        }

        // Subtitles ExoPlayer reads from inside the file; when converting, text goes alongside as WebVTT and
        // pictures (PGS, DVD) are burned into the video.
        JSONArray subtitles = new JSONArray();
        for (String f : new String[] {"srt", "subrip", "ass", "ssa", "vtt", "webvtt", "mov_text", "pgssub", "dvdsub"}) {
            subtitles.put(new JSONObject().put("Format", f).put("Method", "Embed"));
        }
        for (String f : new String[] {"vtt", "srt", "subrip", "ass", "ssa"}) {
            subtitles.put(new JSONObject().put("Format", f).put("Method", sidecars ? "External" : "Encode"));
        }
        for (String f : new String[] {"pgssub", "dvdsub", "dvbsub"}) {
            subtitles.put(new JSONObject().put("Format", f).put("Method", "Encode"));
        }

        return new JSONObject()
                .put("Name", "TvHome")
                .put("MaxStreamingBitrate", MAX_BITRATE)
                .put("MaxStaticBitrate", MAX_BITRATE)
                .put("MusicStreamingTranscodingBitrate", 320_000)
                .put("DirectPlayProfiles", directPlay)
                .put("TranscodingProfiles", transcoding)
                .put("ContainerProfiles", new JSONArray())
                .put("CodecProfiles", codecs)
                .put("SubtitleProfiles", subtitles);
    }

    private static JSONObject condition(String op, String property, String value) throws JSONException {
        return new JSONObject().put("Condition", op).put("Property", property).put("Value", value)
                .put("IsRequired", false);
    }

    // ---- what this TV has ------------------------------------------------------------------------------

    private static final Map<String, String> VIDEO = new LinkedHashMap<>();
    static {
        VIDEO.put("video/avc", "h264");
        VIDEO.put("video/hevc", "hevc");
        VIDEO.put("video/x-vnd.on2.vp9", "vp9");
        VIDEO.put("video/av01", "av1");
        VIDEO.put("video/mpeg2", "mpeg2video");
        VIDEO.put("video/mp4v-es", "mpeg4");
        VIDEO.put("video/x-vnd.on2.vp8", "vp8");
    }

    /** Codecs a TV CPU decodes fast enough without hardware; anything heavier needs a hardware decoder. */
    private static boolean lightEnoughForSoftware(String name) {
        return name.equals("mpeg4") || name.equals("vp8");
    }

    private static List<VideoCodec> videoCodecs() {
        Map<String, VideoCodec> best = new LinkedHashMap<>();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) continue;
            for (String mime : info.getSupportedTypes()) {
                String name = VIDEO.get(mime.toLowerCase(Locale.ROOT));
                if (name == null || name.isEmpty()) continue;
                if (!isHardware(info) && !lightEnoughForSoftware(name)) continue;
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
                MediaCodecInfo.VideoCapabilities v = caps.getVideoCapabilities();
                if (v == null) continue;
                VideoCodec c = new VideoCodec(name, v.getSupportedWidths().getUpper(),
                        v.getSupportedHeights().getUpper(), tenBit(name, caps));
                VideoCodec had = best.get(name);
                if (had == null || c.maxWidth * c.maxHeight > had.maxWidth * had.maxHeight || (c.tenBit && !had.tenBit)) {
                    best.put(name, c);
                }
            }
        }
        return new ArrayList<>(best.values());
    }

    private static boolean isHardware(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated();
        String n = info.getName().toLowerCase(Locale.ROOT);
        return !n.startsWith("omx.google.") && !n.startsWith("c2.android.");
    }

    private static boolean tenBit(String name, MediaCodecInfo.CodecCapabilities caps) {
        for (MediaCodecInfo.CodecProfileLevel p : caps.profileLevels) {
            switch (name) {
                case "hevc":
                    if (p.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                            || p.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10) return true;
                    break;
                case "vp9":
                    if (p.profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2
                            || p.profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR) return true;
                    break;
                case "av1":
                    if (p.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10) return true;
                    break;
                default:
                    break;
            }
        }
        return false;
    }

    /**
     * Audio the TV plays: the common formats it decodes itself, plus Dolby and DTS when it decodes them or the
     * HDMI connection carries them to a receiver or TV that does.
     */
    @OptIn(markerClass = UnstableApi.class)
    private static List<String> audioCodecs(Context context) {
        List<String> out = new ArrayList<>();
        for (String a : new String[] {"aac", "mp3", "mp2", "opus", "vorbis", "flac", "alac", "pcm_s16le", "pcm_s24le"}) {
            out.add(a);
        }
        AudioCapabilities caps = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null);
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        if (caps.supportsEncoding(C.ENCODING_AC3) || decodes(list, "audio/ac3")) out.add("ac3");
        if (caps.supportsEncoding(C.ENCODING_E_AC3) || caps.supportsEncoding(C.ENCODING_E_AC3_JOC)
                || decodes(list, "audio/eac3")) out.add("eac3");
        if (caps.supportsEncoding(C.ENCODING_DTS) || decodes(list, "audio/vnd.dts")) out.add("dts");
        if (caps.supportsEncoding(C.ENCODING_DOLBY_TRUEHD)) out.add("truehd");
        return out;
    }

    private static boolean decodes(MediaCodecList list, String mime) {
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (info.isEncoder()) continue;
            for (String t : info.getSupportedTypes()) if (t.equalsIgnoreCase(mime)) return true;
        }
        return false;
    }

    /**
     * The dynamic ranges the screen shows as they are. Anything else (HDR on an SDR screen) the server converts,
     * mapping its colours down rather than letting them wash out.
     */
    private static List<String> hdrRanges(Context context) {
        List<String> out = new ArrayList<>();
        out.add("SDR");
        Display display = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        Display.HdrCapabilities hdr = display.getHdrCapabilities();
        if (hdr == null) return out;
        for (int type : hdr.getSupportedHdrTypes()) {
            if (type == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                // Dolby Vision with an HDR10 layer plays as HDR10 on a screen without Dolby Vision.
                out.add("HDR10");
                out.add("DOVIWithHDR10");
            } else if (type == Display.HdrCapabilities.HDR_TYPE_HLG) {
                out.add("HLG");
                out.add("DOVIWithHLG");
            } else if (type == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS) {
                out.add("HDR10Plus");
            } else if (type == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) {
                out.add("DOVI");
            }
        }
        return out;
    }

    private static List<String> names(List<VideoCodec> video) {
        List<String> out = new ArrayList<>();
        for (VideoCodec c : video) out.add(c.name);
        return out;
    }

    private static String join(List<String> parts) {
        return String.join(",", parts);
    }
}
