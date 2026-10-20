package com.gathin.tvhome.jellyfin;

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

/**
 * Playing an item from the server: what to stream (PlaybackInfo, with the {@link DeviceProfile}), and telling the
 * server how far playback got, which is what keeps Continue Watching and watched marks right on every client.
 */
public final class Playback {

    /** How the server is sending it, as Jellyfin names it in its playback reports. */
    public enum Method { DirectPlay, DirectStream, Transcode }

    /** A subtitle file the server serves next to the video. */
    public static final class Sidecar {
        public final int index;
        public final String url;
        /** "vtt", "srt" or "ass". */
        public final String format;
        public final String language;
        public final String label;

        Sidecar(int index, String url, String format, String language, String label) {
            this.index = index;
            this.url = url;
            this.format = format;
            this.language = language;
            this.label = label;
        }
    }

    /** Everything needed to play one item and report on it. */
    public static final class Stream {
        public final String itemId;
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
        public final int audioIndex;
        public final int subtitleIndex;
        public final List<Sidecar> sidecars;

        Stream(String itemId, String title, String subtitle, long startMs, long durationMs, String url,
               Method method, String reasons, String mediaSourceId, String playSessionId, int audioIndex,
               int subtitleIndex, List<Sidecar> sidecars) {
            this.itemId = itemId;
            this.title = title;
            this.subtitle = subtitle;
            this.startMs = startMs;
            this.durationMs = durationMs;
            this.url = url;
            this.method = method;
            this.reasons = reasons;
            this.mediaSourceId = mediaSourceId;
            this.playSessionId = playSessionId;
            this.audioIndex = audioIndex;
            this.subtitleIndex = subtitleIndex;
            this.sidecars = sidecars;
        }
    }

    private static final String TAG = "Jellyfin.Playback";
    private static final long TICKS_PER_MS = 10_000L;
    /** Reports go out one at a time and in order, so "stopped" never overtakes the last "progress". */
    private static final ExecutorService reports = Executors.newSingleThreadExecutor();

    private Playback() { }

    /**
     * Asks the server how to play {@code itemId} on this TV, from where the user left off. Blocks; call it in the
     * background.
     */
    public static Stream open(Account account, String itemId, JSONObject profile) throws IOException {
        String server = account.server();
        String user = account.userId();
        String auth = Library.auth(account);
        try {
            JSONObject item = Api.getJson(server + "/Users/" + user + "/Items/" + itemId, auth);
            JSONObject userData = item.optJSONObject("UserData");
            long startTicks = userData != null ? userData.optLong("PlaybackPositionTicks", 0) : 0;

            JSONObject body = new JSONObject()
                    .put("UserId", user)
                    .put("StartTimeTicks", startTicks)
                    .put("IsPlayback", true)
                    .put("AutoOpenLiveStream", true)
                    .put("MaxStreamingBitrate", DeviceProfile.MAX_BITRATE)
                    .put("EnableDirectPlay", true)
                    .put("EnableDirectStream", true)
                    .put("EnableTranscoding", true)
                    .put("AllowVideoStreamCopy", true)
                    .put("AllowAudioStreamCopy", true)
                    .put("DeviceProfile", profile);
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

            return new Stream(itemId, title(item), subtitle(item), startTicks / TICKS_PER_MS,
                    item.optLong("RunTimeTicks", 0) / TICKS_PER_MS, url, method, reasons, sourceId, session,
                    source.optInt("DefaultAudioStreamIndex", -1), source.optInt("DefaultSubtitleStreamIndex", -1),
                    sidecars(server, source));
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

    private static List<Sidecar> sidecars(String server, JSONObject source) {
        List<Sidecar> out = new ArrayList<>();
        JSONArray streams = source.optJSONArray("MediaStreams");
        if (streams == null) return out;
        for (int i = 0; i < streams.length(); i++) {
            JSONObject s = streams.optJSONObject(i);
            if (s == null || !"Subtitle".equals(s.optString("Type"))) continue;
            if (!"External".equals(s.optString("DeliveryMethod"))) continue;
            String path = s.optString("DeliveryUrl", "");
            if (path.isEmpty()) continue;
            String format = Uri.parse("http://x" + path).getLastPathSegment();
            format = format == null || !format.contains(".") ? s.optString("Codec", "")
                    : format.substring(format.lastIndexOf('.') + 1);
            out.add(new Sidecar(s.optInt("Index", -1), server + path, format.toLowerCase(Locale.ROOT),
                    s.optString("Language", ""), s.optString("DisplayTitle", "")));
        }
        return out;
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
        String s = "S" + item.optInt("ParentIndexNumber", 1) + ":E" + item.optInt("IndexNumber", 1);
        String name = item.optString("Name", "");
        return name.isEmpty() ? s : s + " • " + name;
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
