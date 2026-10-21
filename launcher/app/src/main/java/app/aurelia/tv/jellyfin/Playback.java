package app.aurelia.tv.jellyfin;

import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Playing an item from the server: what to stream (PlaybackInfo, with the {@link DeviceProfile}), what comes next,
 * where the intro and credits are, and telling the server how far playback got, which is what keeps Continue
 * Watching and watched marks right on every client.
 */
public final class Playback {

    /** How the server is sending it, as Jellyfin names it in its playback reports. */
    public enum Method { DirectPlay, DirectStream, Transcode }

    /** No subtitles. */
    public static final int OFF = -1;
    /** Let the server choose, from the user's language settings. */
    public static final int DEFAULT = -2;

    /** An audio or subtitle track of the item, as the server numbers it. */
    public static final class Track {
        public final int index;
        public final String title;
        public final String language;
        public final String codec;
        /** "Embed", "External", "Encode" (burned into the video) or "Hls"; empty for audio. */
        public final String delivery;
        /** A separate file next to the video, rather than inside it. */
        public final boolean external;
        /** The subtitle file's address, when it is sent alongside the video. */
        public final String url;

        Track(int index, String title, String language, String codec, String delivery, boolean external, String url) {
            this.index = index;
            this.title = title;
            this.language = language;
            this.codec = codec;
            this.delivery = delivery;
            this.external = external;
            this.url = url;
        }
    }

    /** What to ask for when opening, beyond "from where it was left off". */
    public static final class Options {
        /** Start here instead of the saved position; negative for the saved position. */
        public long startMs = -1;
        public int audioIndex = DEFAULT;
        public int subtitleIndex = DEFAULT;
        public int maxBitrate = DeviceProfile.MAX_BITRATE;
        /** Only audio the TV decodes itself (no Dolby or DTS passthrough), which any playback speed works with. */
        public boolean compatibleAudio;
        /** Text subtitles burned into the picture, not sent as files (the server could not make the files). */
        public boolean noSidecars;
    }

    /** Everything needed to play one item and report on it. */
    public static final class Stream {
        public final String itemId;
        public final String type;
        public final String seriesId;
        public final String title;
        public final String subtitle;
        public final long startMs;
        public final long durationMs;
        public final String url;
        public final Method method;
        /** Why the server is converting, as it says it ("VideoCodecNotSupported"); empty when it is not. */
        public final String reasons;
        public final String mediaSourceId;
        public final String playSessionId;
        /** The file's own bitrate, for which quality choices are lower. */
        public final int sourceBitrate;
        public final int maxBitrate;
        public final boolean compatibleAudio;
        /** Opened with {@link Options#noSidecars}. */
        public final boolean noSidecars;
        public final List<Track> audio;
        public final List<Track> subtitles;
        /** The show's or film's logo, empty when it has none. */
        public final String logoUrl;
        /** Chapters: where each starts (ms), and its name. */
        public final List<Long> chapterStarts;
        public final List<String> chapterNames;
        /** What is playing now; the player changes them as the user chooses. */
        public int audioIndex;
        public int subtitleIndex;

        Stream(String itemId, String type, String seriesId, String title, String subtitle, long startMs,
               long durationMs, String url, Method method, String reasons, String mediaSourceId, String playSessionId,
               int sourceBitrate, Options options, List<Track> audio, List<Track> subtitles, int audioIndex,
               int subtitleIndex, String logoUrl, List<Long> chapterStarts, List<String> chapterNames) {
            this.itemId = itemId;
            this.type = type;
            this.seriesId = seriesId;
            this.title = title;
            this.subtitle = subtitle;
            this.startMs = startMs;
            this.durationMs = durationMs;
            this.url = url;
            this.method = method;
            this.reasons = reasons;
            this.mediaSourceId = mediaSourceId;
            this.playSessionId = playSessionId;
            this.sourceBitrate = sourceBitrate;
            this.maxBitrate = options.maxBitrate;
            this.compatibleAudio = options.compatibleAudio;
            this.noSidecars = options.noSidecars;
            this.audio = audio;
            this.subtitles = subtitles;
            this.audioIndex = audioIndex;
            this.subtitleIndex = subtitleIndex;
            this.logoUrl = logoUrl;
            this.chapterStarts = chapterStarts;
            this.chapterNames = chapterNames;
        }

        public boolean isEpisode() {
            return "Episode".equals(type);
        }

        public Track subtitle(int index) {
            for (Track t : subtitles) if (t.index == index) return t;
            return null;
        }

        public Track audioTrack(int index) {
            for (Track t : audio) if (t.index == index) return t;
            return null;
        }
    }

    /** The episode after this one. */
    public static final class Next {
        public final String itemId;
        public final String title;

        Next(String itemId, String title) {
            this.itemId = itemId;
            this.title = title;
        }
    }

    /** A part of the item to offer skipping: an intro, a recap, the credits. */
    public static final class Segment {
        public enum Kind { Intro, Recap, Credits, Preview }

        public final Kind kind;
        public final long startMs;
        public final long endMs;

        Segment(Kind kind, long startMs, long endMs) {
            this.kind = kind;
            this.startMs = startMs;
            this.endMs = endMs;
        }

        public boolean contains(long ms) {
            return ms >= startMs && ms < endMs;
        }
    }

    private static final String TAG = "Jellyfin.Playback";
    private static final long TICKS_PER_MS = 10_000L;
    /** Reports go out one at a time and in order, so "stopped" never overtakes the last "progress". */
    private static final ExecutorService reports = Executors.newSingleThreadExecutor();

    private Playback() { }

    /**
     * Asks the server how to play {@code itemId} on this TV. {@code profile} is the {@link DeviceProfile} to match
     * the options' {@link Options#compatibleAudio}. Blocks; call it in the background.
     */
    public static Stream open(Account account, String itemId, JSONObject profile, Options options) throws IOException {
        String server = account.server();
        String user = account.userId();
        String auth = Library.auth(account);
        try {
            JSONObject item = Api.getJson(server + "/Users/" + user + "/Items/" + itemId + "?fields=Chapters", auth);
            JSONObject userData = item.optJSONObject("UserData");
            long startTicks = options.startMs >= 0 ? options.startMs * TICKS_PER_MS
                    : userData != null ? userData.optLong("PlaybackPositionTicks", 0) : 0;

            JSONObject body = new JSONObject()
                    .put("UserId", user)
                    .put("StartTimeTicks", startTicks)
                    .put("IsPlayback", true)
                    .put("AutoOpenLiveStream", true)
                    .put("MaxStreamingBitrate", options.maxBitrate)
                    .put("EnableDirectPlay", true)
                    .put("EnableDirectStream", true)
                    .put("EnableTranscoding", true)
                    .put("AllowVideoStreamCopy", true)
                    .put("AllowAudioStreamCopy", true)
                    .put("DeviceProfile", profile);
            if (options.audioIndex >= 0) body.put("AudioStreamIndex", options.audioIndex);
            if (options.subtitleIndex != DEFAULT) body.put("SubtitleStreamIndex", options.subtitleIndex);
            JSONObject info = Api.postJson(server + "/Items/" + itemId + "/PlaybackInfo?userId=" + user, auth, body);
            JSONArray sources = info.optJSONArray("MediaSources");
            if (sources == null || sources.length() == 0) {
                throw new IOException("No way to play " + itemId + ": " + info.optString("ErrorCode", "no sources"));
            }
            JSONObject source = sources.getJSONObject(0);
            String sourceId = source.optString("Id", itemId);
            String session = info.optString("PlaySessionId", "");

            String url;
            Method method;
            String reasons = "";
            if (source.optBoolean("SupportsDirectPlay", false)) {
                method = Method.DirectPlay;
                url = server + "/Videos/" + itemId + "/stream?static=true"
                        + "&mediaSourceId=" + Uri.encode(sourceId)
                        + "&playSessionId=" + Uri.encode(session)
                        + (source.has("ETag") ? "&tag=" + Uri.encode(source.optString("ETag")) : "");
            } else if (!source.optString("TranscodingUrl", "").isEmpty()) {
                String path = source.getString("TranscodingUrl");
                url = server + path;
                reasons = transcodeReasons(source, path);
                // Direct stream: repackaged into HLS, the video and audio copied as they are.
                method = source.optBoolean("SupportsDirectStream", false) ? Method.DirectStream : Method.Transcode;
            } else {
                throw new IOException("The server offered no stream for " + itemId);
            }

            List<Track> audio = new ArrayList<>();
            List<Track> subtitles = new ArrayList<>();
            tracks(server, source, audio, subtitles);
            int audioIndex = options.audioIndex >= 0 ? options.audioIndex : source.optInt("DefaultAudioStreamIndex", -1);
            int subtitleIndex = options.subtitleIndex != DEFAULT ? options.subtitleIndex
                    : source.optInt("DefaultSubtitleStreamIndex", OFF);
            if (subtitleIndex < 0) subtitleIndex = OFF;

            List<Long> chapterStarts = new ArrayList<>();
            List<String> chapterNames = new ArrayList<>();
            JSONArray chapters = item.optJSONArray("Chapters");
            for (int i = 0; chapters != null && i < chapters.length(); i++) {
                JSONObject c = chapters.getJSONObject(i);
                chapterStarts.add(c.optLong("StartPositionTicks", 0) / TICKS_PER_MS);
                chapterNames.add(c.optString("Name", ""));
            }

            return new Stream(itemId, item.optString("Type", ""), item.optString("SeriesId", ""), title(item),
                    subtitle(item), startTicks / TICKS_PER_MS, item.optLong("RunTimeTicks", 0) / TICKS_PER_MS, url,
                    method, reasons, sourceId, session, source.optInt("Bitrate", 0), options, audio, subtitles,
                    audioIndex, subtitleIndex, logoUrl(server, item), chapterStarts, chapterNames);
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from the server", e);
        }
    }

    private static String transcodeReasons(JSONObject source, String path) {
        JSONArray list = source.optJSONArray("TranscodeReasons");
        if (list != null && list.length() > 0) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < list.length(); i++) out.add(list.optString(i));
            return String.join(", ", out);
        }
        String r = Uri.parse("http://x" + path).getQueryParameter("TranscodeReasons");
        return r == null ? "" : r.replace(",", ", ");
    }

    private static void tracks(String server, JSONObject source, List<Track> audio, List<Track> subtitles) {
        JSONArray streams = source.optJSONArray("MediaStreams");
        if (streams == null) return;
        for (int i = 0; i < streams.length(); i++) {
            JSONObject s = streams.optJSONObject(i);
            if (s == null) continue;
            String type = s.optString("Type");
            if ("Audio".equals(type)) {
                audio.add(new Track(s.optInt("Index", -1), audioName(s), s.optString("Language", ""),
                        s.optString("Codec", ""), "", s.optBoolean("IsExternal", false), ""));
            } else if ("Subtitle".equals(type)) {
                String path = s.optString("DeliveryUrl", "");
                subtitles.add(new Track(s.optInt("Index", -1), subtitleName(s), s.optString("Language", ""),
                        subtitleFormat(s, path), s.optString("DeliveryMethod", ""), s.optBoolean("IsExternal", false),
                        path.isEmpty() ? "" : server + path));
            }
        }
    }

    /**
     * A subtitle track as a person would name it: its own title when it has one ("English SDH"), or else its
     * language, marked when it is forced (only the foreign parts).
     */
    static String subtitleName(JSONObject s) {
        String name = s.optString("Title", "").trim();
        if (name.isEmpty()) name = languageName(s);
        if (s.optBoolean("IsForced", false) && !name.toLowerCase(Locale.ROOT).contains("forced")) name += " (Forced)";
        return name;
    }

    /** An audio track: its language, and the channels when there are more than two ("English · 5.1"). */
    static String audioName(JSONObject s) {
        String name = s.optString("Title", "").trim();
        if (name.isEmpty()) name = languageName(s);
        String layout = s.optString("ChannelLayout", "");
        int channels = s.optInt("Channels", 0);
        if (layout.isEmpty() && channels > 2) layout = (channels - 1) + ".1";
        if (layout.matches("\\d\\.\\d.*") && !name.contains(layout)) name += " · " + layout.replaceAll("\\(.*", "");
        return name;
    }

    private static String languageName(JSONObject s) {
        String code = s.optString("Language", "");
        if (code.isEmpty() || code.equals("und")) return s.optString("DisplayTitle", "Unknown");
        String name = new Locale(code).getDisplayLanguage();
        return name.isEmpty() || name.equals(code) ? code : name;
    }

    /** The format a subtitle arrives in: the delivery file's extension when it is converted for sending. */
    private static String subtitleFormat(JSONObject s, String path) {
        String last = path.isEmpty() ? null : Uri.parse("http://x" + path).getLastPathSegment();
        String format = last == null || !last.contains(".") ? s.optString("Codec", "")
                : last.substring(last.lastIndexOf('.') + 1);
        return format.toLowerCase(Locale.ROOT);
    }

    /** An episode shows its series' logo; a film its own. */
    private static String logoUrl(String server, JSONObject item) {
        String id = item.optString("ParentLogoItemId", "");
        String tag = item.optString("ParentLogoImageTag", "");
        if (id.isEmpty()) {
            JSONObject tags = item.optJSONObject("ImageTags");
            tag = tags != null ? tags.optString("Logo", "") : "";
            id = tag.isEmpty() ? "" : item.optString("Id", "");
        }
        if (id.isEmpty()) return "";
        return server + "/Items/" + id + "/Images/Logo?maxHeight=112&quality=90" + (tag.isEmpty() ? "" : "&tag=" + tag);
    }

    private static String title(JSONObject item) {
        String series = item.optString("SeriesName", "");
        return "Episode".equals(item.optString("Type")) && !series.isEmpty() ? series : item.optString("Name", "");
    }

    private static String subtitle(JSONObject item) {
        if (!"Episode".equals(item.optString("Type"))) {
            int year = item.optInt("ProductionYear", 0);
            return year > 0 ? String.valueOf(year) : "";
        }
        return episodeName(item);
    }

    /** "S2:E5 • Name". */
    private static String episodeName(JSONObject item) {
        String s = "S" + item.optInt("ParentIndexNumber", 1) + ":E" + item.optInt("IndexNumber", 1);
        String name = item.optString("Name", "");
        return name.isEmpty() ? s : s + " • " + name;
    }

    // ---- what comes next, and what to skip -------------------------------------------------------------

    /** The episode after {@code s} in its series, or null for the last one (or a film). Blocks. */
    public static Next next(Account account, Stream s) throws IOException {
        if (!s.isEpisode() || s.seriesId.isEmpty()) return null;
        JSONObject page = Api.getJson(account.server() + "/Shows/" + s.seriesId + "/Episodes?userId="
                + account.userId() + "&startItemId=" + s.itemId + "&limit=2", Library.auth(account));
        JSONArray items = page.optJSONArray("Items");
        if (items == null) return null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null || it.optString("Id").equals(s.itemId)) continue;
            return new Next(it.optString("Id"), episodeName(it));
        }
        return null;
    }

    /**
     * The parts of {@code s} to offer skipping. From the server's media segments (which Intro Skipper and similar
     * plugins fill in), or else from chapters named like an intro or the credits. Blocks.
     */
    public static List<Segment> segments(Account account, Stream s) {
        List<Segment> out = new ArrayList<>();
        try {
            JSONObject page = Api.getJson(account.server() + "/MediaSegments/" + s.itemId, Library.auth(account));
            JSONArray items = page.optJSONArray("Items");
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                Segment.Kind kind = segmentKind(it.optString("Type"));
                long start = it.optLong("StartTicks", 0) / TICKS_PER_MS;
                long end = it.optLong("EndTicks", 0) / TICKS_PER_MS;
                if (kind != null && end > start) out.add(new Segment(kind, start, end));
            }
        } catch (IOException | JSONException e) {
            // A server before 10.10 has no segments; chapters may still say.
        }
        if (out.isEmpty()) out.addAll(fromChapters(s.chapterStarts, s.chapterNames, s.durationMs));
        return out;
    }

    private static Segment.Kind segmentKind(String type) {
        switch (type) {
            case "Intro": return Segment.Kind.Intro;
            case "Recap": return Segment.Kind.Recap;
            case "Outro": return Segment.Kind.Credits;
            case "Preview": return Segment.Kind.Preview;
            default: return null;
        }
    }

    private static final Pattern INTRO = Pattern.compile("(?i)^(intro|opening|op|opening credits|title sequence|theme)\\b.*");
    private static final Pattern RECAP = Pattern.compile("(?i)^(recap|previously)\\b.*");
    private static final Pattern CREDITS = Pattern.compile("(?i)^(credits|end credits|outro|ending|ed|closing credits)\\b.*");

    /** A chapter named like an intro, recap or the credits runs to the start of the next chapter (or the end). */
    static List<Segment> fromChapters(List<Long> starts, List<String> names, long durationMs) {
        List<Segment> out = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i).trim();
            Segment.Kind kind = INTRO.matcher(name).matches() ? Segment.Kind.Intro
                    : RECAP.matcher(name).matches() ? Segment.Kind.Recap
                    : CREDITS.matcher(name).matches() ? Segment.Kind.Credits : null;
            if (kind == null) continue;
            long start = starts.get(i);
            long end = i + 1 < starts.size() ? starts.get(i + 1) : durationMs;
            if (end > start) out.add(new Segment(kind, start, end));
        }
        return out;
    }

    // ---- reports ---------------------------------------------------------------------------------------

    public static void started(Account account, Stream s, long positionMs, boolean paused) {
        report(account, "/Sessions/Playing", s, positionMs, paused);
    }

    public static void progress(Account account, Stream s, long positionMs, boolean paused) {
        report(account, "/Sessions/Playing/Progress", s, positionMs, paused);
    }

    public static void stopped(Account account, Stream s, long positionMs) {
        report(account, "/Sessions/Playing/Stopped", s, positionMs, false);
    }

    private static void report(Account account, String path, Stream s, long positionMs, boolean paused) {
        String url = account.server() + path;
        String auth = Library.auth(account);
        JSONObject body;
        try {
            body = new JSONObject()
                    .put("ItemId", s.itemId)
                    .put("MediaSourceId", s.mediaSourceId)
                    .put("PlaySessionId", s.playSessionId)
                    .put("PositionTicks", Math.max(0, positionMs) * TICKS_PER_MS)
                    .put("IsPaused", paused)
                    .put("CanSeek", true)
                    .put("PlayMethod", s.method.name())
                    .put("AudioStreamIndex", s.audioIndex)
                    .put("SubtitleStreamIndex", s.subtitleIndex);
        } catch (JSONException e) {
            return;
        }
        reports.execute(() -> {
            try {
                Api.postJson(url, auth, body);
            } catch (IOException e) {
                Log.w(TAG, "Could not report " + path, e);
            }
        });
    }

    /**
     * Waits (briefly) for reports already sent, so a list fetched right after playback stops shows where it
     * stopped. Blocks; call it in the background.
     */
    static void awaitReports() {
        try {
            reports.submit(() -> { }).get(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }
}
