package app.aurelia.tv.player;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import app.aurelia.tv.jellyfin.Playback;

import java.util.ArrayList;
import java.util.List;

/**
 * Matches the server's audio and subtitle tracks to the player's. The server numbers every stream in the file;
 * the player sees the ones inside the file in the same order, and the subtitle files sent alongside under the
 * ids given them here. A track the player does not have (another audio track of a converted stream, subtitles
 * the server burns into the picture) needs the stream opened again with it chosen.
 */
@OptIn(markerClass = UnstableApi.class)
final class TrackMatcher {

    private static final String SIDECAR = "jf-sub-";

    private TrackMatcher() { }

    /** The id a subtitle file sent alongside the video goes by in the player. */
    static String sidecarId(int index) {
        return SIDECAR + index;
    }

    /** Plays audio track {@code index}; false when the stream must be opened again for it. */
    static boolean selectAudio(ExoPlayer player, Playback.Stream s, int index) {
        if (s.method != Playback.Method.DirectPlay) return index == s.audioIndex;
        int ordinal = 0;
        boolean found = false;
        for (Playback.Track t : s.audio) {
            if (t.external) continue;
            if (t.index == index) {
                found = true;
                break;
            }
            ordinal++;
        }
        List<androidx.media3.common.Tracks.Group> groups = groups(player, C.TRACK_TYPE_AUDIO, false);
        if (!found || ordinal >= groups.size()) return false;
        TrackSelectionParameters p = player.getTrackSelectionParameters().buildUpon()
                .setOverrideForType(new TrackSelectionOverride(groups.get(ordinal).getMediaTrackGroup(), 0))
                .build();
        player.setTrackSelectionParameters(p);
        return true;
    }

    /** Shows subtitle track {@code index}, or none for {@link Playback#OFF}; false when the stream must be opened again. */
    static boolean selectSubtitle(ExoPlayer player, Playback.Stream s, int index) {
        TrackSelectionParameters.Builder p = player.getTrackSelectionParameters().buildUpon();
        if (index == Playback.OFF) {
            player.setTrackSelectionParameters(p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            return true;
        }
        Playback.Track t = s.subtitle(index);
        if (t == null) return false;
        if ("Encode".equals(t.delivery)) {
            // Burned into the picture: right only if the stream was opened with it.
            if (s.method == Playback.Method.DirectPlay || index != s.subtitleIndex) return false;
            player.setTrackSelectionParameters(p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            return true;
        }
        androidx.media3.common.Tracks.Group group = null;
        if ("External".equals(t.delivery)) {
            for (androidx.media3.common.Tracks.Group g : groups(player, C.TRACK_TYPE_TEXT, true)) {
                String id = g.getTrackFormat(0).id;
                if (id != null && id.endsWith(sidecarId(index))) group = g;
            }
        } else if (s.method == Playback.Method.DirectPlay) {
            int ordinal = 0;
            for (Playback.Track other : s.subtitles) {
                if (other.index == index) break;
                if (!other.external) ordinal++;
            }
            List<androidx.media3.common.Tracks.Group> embedded = groups(player, C.TRACK_TYPE_TEXT, false);
            if (ordinal < embedded.size()) group = embedded.get(ordinal);
        }
        if (group == null) return false;
        player.setTrackSelectionParameters(p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), 0)).build());
        return true;
    }

    /**
     * The player's groups of {@code type}: the subtitle files sent alongside ({@code sidecars}), or the tracks
     * inside the file, in the file's order.
     */
    private static List<androidx.media3.common.Tracks.Group> groups(ExoPlayer player, int type, boolean sidecars) {
        List<androidx.media3.common.Tracks.Group> out = new ArrayList<>();
        for (androidx.media3.common.Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != type || g.length == 0) continue;
            Format f = g.getTrackFormat(0);
            boolean sidecar = f.id != null && f.id.contains(SIDECAR);
            if (sidecar == sidecars) out.add(g);
        }
        return out;
    }

    /** The audio the player is sending out is Dolby or DTS, which a TV passes through rather than decodes. */
    static boolean isPassthroughAudio(ExoPlayer player) {
        Format f = player.getAudioFormat();
        if (f == null || f.sampleMimeType == null) return false;
        switch (f.sampleMimeType) {
            case androidx.media3.common.MimeTypes.AUDIO_AC3:
            case androidx.media3.common.MimeTypes.AUDIO_E_AC3:
            case androidx.media3.common.MimeTypes.AUDIO_E_AC3_JOC:
            case androidx.media3.common.MimeTypes.AUDIO_DTS:
            case androidx.media3.common.MimeTypes.AUDIO_DTS_HD:
            case androidx.media3.common.MimeTypes.AUDIO_TRUEHD:
                return true;
            default:
                return false;
        }
    }
}
