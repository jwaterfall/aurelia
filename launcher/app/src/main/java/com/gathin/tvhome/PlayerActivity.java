package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import com.gathin.tvhome.jellyfin.Account;
import com.gathin.tvhome.jellyfin.Api;
import com.gathin.tvhome.jellyfin.DeviceProfile;
import com.gathin.tvhome.jellyfin.Library;
import com.gathin.tvhome.jellyfin.Playback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Plays a Jellyfin item in the launcher, from where it was left off, and reports progress to the server as it
 * goes. Remote: OK or play/pause pauses, Left and Right skip back and forward (further while held), Back hides
 * the controls and then leaves. Anything the server cannot get to play here can still go to the Jellyfin app.
 */
@OptIn(markerClass = UnstableApi.class)
public final class PlayerActivity extends Activity {

    private static final String EXTRA_ITEM = "item";
    private static final String TAG = "Player";
    private static final long SKIP_BACK_MS = 10_000;
    private static final long SKIP_FORWARD_MS = 10_000;
    /** A held key skips this far per repeat instead. */
    private static final long SKIP_HELD_MS = 30_000;
    private static final long CHROME_MS = 4_000;
    private static final long FADE_MS = 220;
    private static final long TICK_MS = 500;
    private static final long REPORT_MS = 10_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PathInterpolator ease = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private Account account;
    private ExoPlayer player;
    private Playback.Stream stream;
    private String itemId;
    private boolean reportedStop;
    private long lastReport;

    private View chrome;
    private ProgressBar buffering;
    private TextView tag;
    private TextView title;
    private TextView subtitle;
    private ImageView state;
    private TextView elapsed;
    private TextView remaining;
    private ProgressBar seek;
    private View error;
    private TextView errorBody;

    public static void start(Context context, String itemId) {
        context.startActivity(new Intent(context, PlayerActivity.class).putExtra(EXTRA_ITEM, itemId));
    }

    private final Runnable hideChrome = () -> showChrome(false);

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (player == null) return;
            updateTimes();
            long now = System.currentTimeMillis();
            if (stream != null && now - lastReport >= REPORT_MS) {
                lastReport = now;
                Playback.progress(account, stream, player.getCurrentPosition(), !player.getPlayWhenReady());
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        account = Account.get(this);
        itemId = getIntent().getStringExtra(EXTRA_ITEM);
        chrome = findViewById(R.id.chrome);
        buffering = findViewById(R.id.buffering);
        tag = findViewById(R.id.playerTag);
        title = findViewById(R.id.playerTitle);
        subtitle = findViewById(R.id.playerSubtitle);
        state = findViewById(R.id.playerState);
        elapsed = findViewById(R.id.playerElapsed);
        remaining = findViewById(R.id.playerRemaining);
        seek = findViewById(R.id.playerSeek);
        error = findViewById(R.id.playerError);
        errorBody = findViewById(R.id.playerErrorBody);
        findViewById(R.id.playerOpenApp).setOnClickListener(v -> {
            Library.open(this, itemId);
            finish();
        });
        findViewById(R.id.playerClose).setOnClickListener(v -> finish());

        if (itemId == null || !account.isSignedIn()) {
            finish();
            return;
        }
        Context app = getApplicationContext();
        Api.async(() -> Playback.open(account, itemId, DeviceProfile.build(app)), new Api.Callback<Playback.Stream>() {
            @Override
            public void onResult(Playback.Stream s) {
                if (isFinishing()) return;
                play(s);
            }

            @Override
            public void onError(Exception e) {
                Log.w(TAG, "Could not start " + itemId, e);
                if (!isFinishing()) fail(e.getMessage());
            }
        });
    }

    private void play(Playback.Stream s) {
        stream = s;
        tag.setText(methodLabel(s));
        title.setText(s.title);
        subtitle.setText(s.subtitle);
        subtitle.setVisibility(s.subtitle.isEmpty() ? View.GONE : View.VISIBLE);

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("TvHome/" + BuildConfig.VERSION_NAME)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(Collections.singletonMap("Authorization", Library.auth(account)));
        player = new ExoPlayer.Builder(this, new DefaultRenderersFactory(this).setEnableDecoderFallback(true))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, http)))
                .setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .setSeekBackIncrementMs(SKIP_BACK_MS)
                .setSeekForwardIncrementMs(SKIP_FORWARD_MS)
                .build();
        ((PlayerView) findViewById(R.id.video)).setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                buffering.setVisibility(playbackState == Player.STATE_BUFFERING ? View.VISIBLE : View.INVISIBLE);
                if (playbackState == Player.STATE_ENDED) {
                    reportStop(stream.durationMs > 0 ? stream.durationMs : player.getCurrentPosition());
                    finish();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                state.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
                // Paused: the controls stay, so it is clear why nothing moves.
                if (isPlaying) scheduleHide();
                else showChrome(true);
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException e) {
                Log.w(TAG, "Playback failed for " + itemId + " (" + s.method + ")", e);
                fail(e.getErrorCodeName());
            }
        });

        MediaItem.Builder item = new MediaItem.Builder().setUri(s.url).setMediaId(s.itemId);
        if (s.method != Playback.Method.DirectPlay) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        List<MediaItem.SubtitleConfiguration> subs = new ArrayList<>();
        for (Playback.Sidecar c : s.sidecars) {
            String mime = subtitleMime(c.format);
            if (mime == null) continue;
            subs.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(c.url))
                    .setMimeType(mime)
                    .setLanguage(c.language.isEmpty() ? null : c.language)
                    .setLabel(c.label)
                    .setId(String.valueOf(c.index))
                    .setSelectionFlags(c.index == s.subtitleIndex ? C.SELECTION_FLAG_DEFAULT : 0)
                    .build());
        }
        item.setSubtitleConfigurations(subs);
        player.setMediaItem(item.build(), s.startMs);
        player.setPlayWhenReady(true);
        player.prepare();

        Playback.started(account, s, s.startMs, false);
        lastReport = System.currentTimeMillis();
        handler.post(tick);
        showChrome(true);
    }

    private String methodLabel(Playback.Stream s) {
        switch (s.method) {
            case DirectPlay: return getString(R.string.player_direct_play);
            case DirectStream: return getString(R.string.player_direct_stream);
            default:
                String t = getString(R.string.player_transcode);
                return s.reasons.isEmpty() ? t : t + " • " + s.reasons;
        }
    }

    private static String subtitleMime(String format) {
        switch (format) {
            case "vtt":
            case "webvtt": return MimeTypes.TEXT_VTT;
            case "srt":
            case "subrip": return MimeTypes.APPLICATION_SUBRIP;
            case "ass":
            case "ssa": return MimeTypes.TEXT_SSA;
            default: return null;
        }
    }

    private void fail(String detail) {
        if (player != null) {
            reportStop(player.getCurrentPosition());
            player.release();
            player = null;
        }
        handler.removeCallbacksAndMessages(null);
        buffering.setVisibility(View.INVISIBLE);
        chrome.setVisibility(View.GONE);
        errorBody.setText(detail == null ? "" : detail);
        errorBody.setVisibility(detail == null || detail.isEmpty() ? View.GONE : View.VISIBLE);
        error.setVisibility(View.VISIBLE);
        findViewById(R.id.playerOpenApp).requestFocus();
    }

    // ---- controls --------------------------------------------------------------------------------------

    private void showChrome(boolean show) {
        handler.removeCallbacks(hideChrome);
        if (show) updateTimes();
        chrome.animate().alpha(show ? 1f : 0f).setDuration(FADE_MS).setInterpolator(ease).start();
        if (show && player != null && player.isPlaying()) scheduleHide();
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideChrome);
        handler.postDelayed(hideChrome, CHROME_MS);
    }

    private boolean chromeShowing() {
        return chrome.getAlpha() > 0f;
    }

    private void updateTimes() {
        if (player == null) return;
        long duration = player.getDuration() != C.TIME_UNSET ? player.getDuration() : stream.durationMs;
        long position = player.getCurrentPosition();
        elapsed.setText(clock(position));
        remaining.setText(getString(R.string.player_remaining, clock(Math.max(0, duration - position))));
        if (duration > 0) {
            seek.setProgress((int) (1000 * position / duration));
            seek.setSecondaryProgress((int) (1000 * player.getBufferedPosition() / duration));
        }
    }

    /** "4:05", or "1:02:09" from an hour. */
    static String clock(long ms) {
        long s = ms / 1000;
        long h = s / 3600;
        long m = s / 60 % 60;
        long sec = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (player == null || error.getVisibility() == View.VISIBLE) return super.onKeyDown(keyCode, event);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (event.getRepeatCount() == 0) togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                player.play();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                player.pause();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                skip(-(event.getRepeatCount() > 0 ? SKIP_HELD_MS : SKIP_BACK_MS));
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                skip(event.getRepeatCount() > 0 ? SKIP_HELD_MS : SKIP_FORWARD_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_INFO:
                showChrome(true);
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                finish();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void togglePause() {
        if (player.getPlayWhenReady()) player.pause();
        else player.play();
        Playback.progress(account, stream, player.getCurrentPosition(), !player.getPlayWhenReady());
        lastReport = System.currentTimeMillis();
    }

    private void skip(long byMs) {
        long duration = player.getDuration();
        long to = Math.max(0, player.getCurrentPosition() + byMs);
        if (duration != C.TIME_UNSET) to = Math.min(to, Math.max(0, duration - 1000));
        player.seekTo(to);
        showChrome(true);
    }

    @Override
    public void onBackPressed() {
        if (player != null && chromeShowing() && player.isPlaying()) {
            showChrome(false);
            return;
        }
        super.onBackPressed();
    }

    // ---- lifecycle -------------------------------------------------------------------------------------

    @Override
    protected void onPause() {
        super.onPause();
        // Leaving: report now, before Home asks the server for Continue Watching again.
        if (isFinishing() && player != null) reportStop(player.getCurrentPosition());
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Sent to the background (Home, the screen saver): stop rather than play on behind it.
        if (player != null) reportStop(player.getCurrentPosition());
        release();
        if (!isFinishing()) finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        release();
    }

    private void reportStop(long positionMs) {
        if (stream == null || reportedStop) return;
        reportedStop = true;
        Playback.stopped(account, stream, positionMs);
    }

    private void release() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
