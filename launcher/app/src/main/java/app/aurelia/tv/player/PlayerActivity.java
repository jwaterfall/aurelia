package app.aurelia.tv.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioCapabilities;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import app.aurelia.tv.BuildConfig;
import app.aurelia.tv.R;
import app.aurelia.tv.jellyfin.Account;
import app.aurelia.tv.jellyfin.Api;
import app.aurelia.tv.jellyfin.Catalog;
import app.aurelia.tv.jellyfin.DeviceProfile;
import app.aurelia.tv.jellyfin.Library;
import app.aurelia.tv.jellyfin.Playback;
import app.aurelia.tv.ui.GlassMenu;
import app.aurelia.tv.ui.ImageDisk;
import app.aurelia.tv.ui.Images;
import app.aurelia.tv.ui.Logos;
import app.aurelia.tv.ui.PulseIndicator;
import app.aurelia.tv.ui.SubtitleText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Plays a Jellyfin item in the launcher, from where it was left off, and reports progress to the server as it
 * goes.
 *
 * Remote, controls hidden: OK pauses, Left and Right skip (further while held), Up or Down shows the controls.
 * When a skip button or the next episode is showing in the corner, OK takes it. Controls showing: the seek bar
 * has focus (Left and Right scrub, OK pauses), and Down reaches the round buttons under it: play, back, forward
 * and the next episode on the left; subtitles, audio (with volume levelling), speed and quality on the right,
 * each opening a menu above it. Back closes what is showing, then leaves. While scrubbing, the frame at the
 * scrub point shows over the bar when the server has preview frames for the item ({@link TrickplayPreview}).
 *
 * Changing something the player cannot change by itself (another audio track of a converted stream, subtitles
 * the server burns in, the quality) opens the stream again where it was.
 */
@OptIn(markerClass = UnstableApi.class)
public final class PlayerActivity extends Activity {

    private static final String EXTRA_ITEM = "item";
    private static final String EXTRA_FROM_START = "fromStart";
    private static final String TAG = "Player";
    private static final String PREFS = "player";
    private static final String PREF_LEVEL = "levelVolume";
    /** How text subtitles look, SubtitleText.SHADOW, GLASS or CLASSIC. */
    private static final String PREF_SUBTITLE_STYLE = "subtitleStyle";
    /** How long the logo is waited for before the name shows instead, for good. */
    private static final long LOGO_WAIT_MS = 1500;
    /** How long the opening screen waits, once playback is ready, for a first frame that may never come. */
    private static final long CURTAIN_WAIT_MS = 2000;

    private static final long SKIP_MS = 10_000;
    private static final long CHROME_MS = 4_000;
    private static final long FADE_MS = 220;
    private static final long TICK_MS = 500;
    private static final long REPORT_MS = 10_000;
    /** A scrub lands this long after the last key press, so a run of presses is one seek. */
    private static final long SCRUB_SETTLE_MS = 600;
    /** Without credits marked, the next episode is offered this long before the end. */
    private static final long UP_NEXT_BEFORE_END_MS = 25_000;
    private static final int UP_NEXT_SECONDS = 10;
    /** Waits before each try to pick up again after the network drops: about 40 seconds in all. */
    private static final long[] RECONNECT_MS = {1_000, 2_000, 4_000, 8_000, 8_000, 8_000, 8_000};
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
    private static final int[] BITRATES_MBPS = {60, 40, 20, 15, 10, 8, 6, 4, 3, 2};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PathInterpolator ease = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private final VolumeLeveler leveler = new VolumeLeveler();
    /** Tries so far to pick up again after the network dropped, and where it was then (-1 when it has not). */
    private int reconnects;
    private long reconnectAt = -1;
    private Account account;
    private SharedPreferences prefs;
    private ExoPlayer player;
    private Playback.Stream stream;
    private String itemId;
    /** Bumped for every stream opened, so an answer for an earlier one is dropped. */
    private int generation;
    private boolean reportedStop;
    private long lastReport;
    private boolean tracksApplied;
    /** Sidecar subtitles are attached to what is playing. */
    private boolean hasSidecars;

    private float speed = 1f;
    /** Choices carried to the next episode: null keeps the server's own choice. "" is subtitles off. */
    private String subtitleLanguage;
    private String audioLanguage;

    private Playback.Next next;
    private List<Playback.Segment> segments = Collections.emptyList();
    private Playback.Segment skipping;
    /** The next episode is showing in the corner, counting down; or the user turned it away for this item. */
    private boolean upNextShowing;
    private boolean upNextDismissed;
    private long upNextDeadline;

    private long scrubTo = -1;
    /** The controls are showing, or on their way in. */
    private boolean chromeShown;
    /** The stream was opened again once already to get the chosen tracks; not again. */
    private boolean retriedTracks;

    private View chrome;
    private View controls;
    private View track;
    private PulseIndicator buffering;
    /** Text subtitles drawn here; subtitles that are pictures, in media3's view. */
    private SubtitleText subs;
    private SubtitleView pictureSubs;
    /** The opening screen, and which item it is up for. */
    private View curtain;
    private ImageView curtainArt, curtainLogo;
    private TextView curtainTitle;
    private String curtainFor;
    /** Lifts the opening screen when playback is ready but no frame comes (a stream with sound only). */
    private final Runnable liftCurtain = () -> lowerCurtain(true);
    private TextView title;
    private TextView subtitle;
    private TextView ends;
    private TextView time;
    private ImageView logo;
    private ProgressBar seek;
    private View thumb;
    private TrickplayPreview preview;
    private ImageView playButton;
    private View nextButton;
    private TextView skip;
    private View upNext;
    private TextView upNextDetail;
    private ImageView upNextStill;
    private GlassMenu menu;
    private PlayerView video;
    private View error;
    private TextView errorBody;

    public static void start(Context context, String itemId) {
        start(context, itemId, false);
    }

    /** {@code fromStart}: from the beginning rather than where it was left off. */
    public static void start(Context context, String itemId, boolean fromStart) {
        context.startActivity(new Intent(context, PlayerActivity.class).putExtra(EXTRA_ITEM, itemId)
                .putExtra(EXTRA_FROM_START, fromStart));
    }

    private final Runnable hideChrome = () -> showChrome(false);

    private final Runnable commitScrub = () -> {
        if (player != null && scrubTo >= 0) player.seekTo(scrubTo);
        scrubTo = -1;
        preview.hide();
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (player == null) return;
            updateTimes();
            updateCorner();
            long now = System.currentTimeMillis();
            if (stream != null && now - lastReport >= REPORT_MS) report();
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Home sets up the image cache, but the player can be the first screen to open (after an install, say).
        ImageDisk.init(this);
        setContentView(R.layout.activity_player);
        account = Account.get(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        itemId = getIntent().getStringExtra(EXTRA_ITEM);

        video = findViewById(R.id.video);
        chrome = findViewById(R.id.chrome);
        controls = findViewById(R.id.controls);
        track = findViewById(R.id.playerTrack);
        buffering = findViewById(R.id.buffering);
        subs = findViewById(R.id.playerSubs);
        pictureSubs = findViewById(R.id.playerPictureSubs);
        curtain = findViewById(R.id.playerCurtain);
        curtainArt = findViewById(R.id.curtainArt);
        curtainLogo = findViewById(R.id.curtainLogo);
        curtainTitle = findViewById(R.id.curtainTitle);
        title = findViewById(R.id.playerTitle);
        subtitle = findViewById(R.id.playerSubtitle);
        ends = findViewById(R.id.playerEnds);
        time = findViewById(R.id.playerTime);
        logo = findViewById(R.id.playerLogo);
        seek = findViewById(R.id.playerSeek);
        thumb = findViewById(R.id.playerThumb);
        preview = new TrickplayPreview(findViewById(R.id.playerPreview), track);
        playButton = findViewById(R.id.buttonPlay);
        nextButton = findViewById(R.id.buttonNext);
        skip = findViewById(R.id.playerSkip);
        upNext = findViewById(R.id.playerUpNext);
        upNextDetail = findViewById(R.id.upNextDetail);
        upNextStill = findViewById(R.id.upNextStill);
        upNextStill.setClipToOutline(true);
        menu = findViewById(R.id.playerMenu);
        // Video draws on its own surface, which a snapshot cannot frost: its menus sit on a dark fill.
        menu.setFrostBehind(false);
        menu.setOnBack(() -> {
            menu.hide();
            scheduleHide();
        });
        error = findViewById(R.id.playerError);
        errorBody = findViewById(R.id.playerErrorBody);

        track.setOnFocusChangeListener((v, focused) -> {
            float scale = focused ? 1f : 0.6f;
            thumb.animate().scaleX(scale).scaleY(scale).setDuration(FADE_MS).setInterpolator(ease).start();
        });
        playButton.setOnClickListener(v -> togglePause());
        findViewById(R.id.buttonRewind).setOnClickListener(v -> scrub(-SKIP_MS));
        findViewById(R.id.buttonForward).setOnClickListener(v -> scrub(SKIP_MS));
        nextButton.setOnClickListener(v -> playNext());
        findViewById(R.id.buttonSubtitles).setOnClickListener(this::showSubtitles);
        findViewById(R.id.buttonAudio).setOnClickListener(this::showAudio);
        findViewById(R.id.buttonSpeed).setOnClickListener(this::showSpeed);
        findViewById(R.id.buttonQuality).setOnClickListener(this::showQuality);
        findViewById(R.id.buttonZoom).setOnClickListener(this::showZoom);
        findViewById(R.id.buttonChapters).setOnClickListener(this::showChapters);
        skip.setOnClickListener(v -> skipSegment());
        upNext.setOnClickListener(v -> playNext());
        findViewById(R.id.playerOpenApp).setOnClickListener(v -> {
            Library.open(this, itemId);
            finish();
        });
        findViewById(R.id.playerClose).setOnClickListener(v -> finish());
        styleSubtitles();

        if (itemId == null || !account.isSignedIn()) {
            finish();
            return;
        }
        Playback.Options first = firstOptions();
        if (getIntent().getBooleanExtra(EXTRA_FROM_START, false)) first.startMs = 0;
        raiseCurtain(itemId);
        open(itemId, first);
    }

    /**
     * The opening screen for {@code id}: black at once, then its backdrop, dimmed, and its logo (or its
     * name), through Home's image cache, so what Home has shown is there straight away.
     */
    private void raiseCurtain(String id) {
        handler.removeCallbacks(liftCurtain);
        curtainFor = id;
        curtain.animate().cancel();
        curtain.setAlpha(1f);
        curtain.setVisibility(View.VISIBLE);
        curtainArt.setImageDrawable(null);
        curtainLogo.setVisibility(View.GONE);
        curtainTitle.setVisibility(View.INVISIBLE);
        Api.async(() -> Catalog.item(account, id), new Api.Callback<Catalog.Item>() {
            @Override
            public void onResult(Catalog.Item item) {
                if (item == null || !id.equals(curtainFor) || isFinishing()) return;
                String auth = Library.auth(account);
                Images.load(curtainArt, Catalog.backdrop(account, item, 1280), auth);
                curtainTitle.setText(item.seriesName.isEmpty() ? item.name : item.seriesName);
                logoOrName(curtainLogo, curtainTitle, Catalog.logo(account, item, 160), () -> id.equals(curtainFor));
            }

            @Override
            public void onError(Exception e) {
            }
        });
    }

    /** The opening screen goes: faded with the first frame, at once for an error. */
    private void lowerCurtain(boolean fade) {
        if (curtain.getVisibility() != View.VISIBLE) return;
        curtainFor = null;
        curtain.animate().cancel();
        if (!fade) {
            curtain.setVisibility(View.GONE);
            return;
        }
        curtain.animate().alpha(0f).setDuration(400).setInterpolator(ease)
                .withEndAction(() -> curtain.setVisibility(View.GONE)).start();
    }

    /** The show's logo in place of its title; the title when there is none, or it is slow to come. */
    private void loadLogo(Playback.Stream s) {
        logoOrName(logo, title, s.logoUrl, () -> stream != null && stream.itemId.equals(s.itemId));
    }

    /**
     * {@code logoView} or {@code nameView}, never one and then the other: neither shows until the logo
     * arrives (through Home's image cache, cropped as on Home) and fades in; if there is none, or it takes longer
     * than {@link #LOGO_WAIT_MS}, the name fades in instead and stays. Nothing changes once {@code wanted} is false.
     */
    private void logoOrName(ImageView logoView, TextView nameView, String url, java.util.function.BooleanSupplier wanted) {
        logoView.setVisibility(View.GONE);
        nameView.setVisibility(View.INVISIBLE);
        boolean[] settled = {false};
        Runnable name = () -> {
            if (settled[0] || !wanted.getAsBoolean()) return;
            settled[0] = true;
            fadeIn(nameView);
        };
        if (url.isEmpty()) {
            name.run();
            return;
        }
        handler.postDelayed(name, LOGO_WAIT_MS);
        Images.load(logoView, url, Library.auth(account), new Images.Loaded() {
            @Override
            public void onLoaded(android.graphics.Bitmap picture) {
                if (settled[0] || !wanted.getAsBoolean()) return;
                settled[0] = true;
                handler.removeCallbacks(name);
                logoView.setImageBitmap(Logos.trimmed(url, picture));
                nameView.setVisibility(View.GONE);
                fadeIn(logoView);
            }

            @Override
            public void onFailed() {
                handler.removeCallbacks(name);
                name.run();
            }
        });
    }

    private void fadeIn(View v) {
        v.animate().cancel();
        v.setAlpha(0f);
        v.setVisibility(View.VISIBLE);
        v.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(ease).start();
    }

    /** Levelling needs audio the TV decodes, so with it on, Dolby and DTS are decoded here from the start. */
    private Playback.Options firstOptions() {
        Playback.Options o = new Playback.Options();
        o.compatibleAudio = prefs.getBoolean(PREF_LEVEL, false);
        return o;
    }

    // ---- opening a stream ------------------------------------------------------------------------------

    /** Opens {@code id} with {@code options}, replacing whatever was playing. */
    private void open(String id, Playback.Options options) {
        int mine = ++generation;
        itemId = id;
        buffering.show(true);
        Context app = getApplicationContext();
        Api.async(() -> Playback.open(account, id, DeviceProfile.build(app, !options.noSidecars), options),
                new Api.Callback<Playback.Stream>() {
                    @Override
                    public void onResult(Playback.Stream s) {
                        if (mine != generation || isFinishing()) return;
                        play(s);
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.w(TAG, "Could not start " + id, e);
                        if (mine != generation || isFinishing()) return;
                        // Still no network while picking up again: wait a little longer.
                        if (reconnectAt >= 0 && e instanceof java.io.IOException && reconnect()) return;
                        fail(e.getMessage());
                    }
                });
    }

    /**
     * The network dropped (Wi-Fi blinked, the server restarted): waits, then opens the stream again where it was,
     * a few times over, the pulse showing meanwhile. False once it has tried long enough, to give up.
     */
    private boolean reconnect() {
        if (stream == null || reconnects >= RECONNECT_MS.length) return false;
        if (reconnectAt < 0) reconnectAt = player != null ? player.getCurrentPosition() : stream.startMs;
        long at = reconnectAt;
        Log.i(TAG, "Lost the stream; trying again (" + (reconnects + 1) + ")");
        buffering.show(true);
        handler.postDelayed(() -> reopen(o -> o.startMs = at), RECONNECT_MS[reconnects++]);
        return true;
    }

    /** A failure of the connection rather than of the video: worth trying again. */
    static boolean isNetworkFailure(PlaybackException e) {
        switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED:
            case PlaybackException.ERROR_CODE_TIMEOUT:
                return true;
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS:
                // The server answering, but not well (restarting, overloaded); not a file that is not there.
                Throwable cause = e.getCause();
                return cause instanceof HttpDataSource.InvalidResponseCodeException
                        && ((HttpDataSource.InvalidResponseCodeException) cause).responseCode >= 500;
            default:
                return false;
        }
    }

    /** The stream again, from where it is now, with {@code change} made to how it was opened. */
    private void reopen(OptionsChange change) {
        if (stream == null) return;
        Playback.Options o = new Playback.Options();
        o.startMs = player != null ? player.getCurrentPosition() : stream.startMs;
        o.audioIndex = stream.audioIndex;
        o.subtitleIndex = stream.subtitleIndex;
        o.maxBitrate = stream.maxBitrate;
        o.compatibleAudio = stream.compatibleAudio;
        o.noSidecars = stream.noSidecars;
        change.apply(o);
        stopPlayer();
        open(stream.itemId, o);
    }

    private interface OptionsChange {
        void apply(Playback.Options o);
    }

    private void play(Playback.Stream s) {
        boolean sameItem = stream != null && stream.itemId.equals(s.itemId);
        stream = s;
        reportedStop = false;
        tracksApplied = false;
        if (!sameItem) {
            next = null;
            segments = Collections.emptyList();
            upNextDismissed = false;
            hideCorner();
            loadExtras(s);
            loadLogo(s);
        }
        preview.setSource(s.trickplay, account.server(), Library.auth(account));
        title.setText(s.title);
        subtitle.setText(s.subtitle);
        subtitle.setVisibility(s.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        findViewById(R.id.buttonSubtitles).setVisibility(s.subtitles.isEmpty() ? View.GONE : View.VISIBLE);
        findViewById(R.id.buttonChapters).setVisibility(s.chapterStarts.size() > 1 ? View.VISIBLE : View.GONE);

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("Aurelia/" + BuildConfig.VERSION_NAME)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(Collections.singletonMap("Authorization", Library.auth(account)));
        player = new ExoPlayer.Builder(this, renderers(s.compatibleAudio))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, http)))
                .setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .build();
        leveler.apply(player.getAudioSessionId(), prefs.getBoolean(PREF_LEVEL, false));
        video.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onCues(@NonNull CueGroup cueGroup) {
                showCues(cueGroup);
            }

            @Override
            public void onRenderedFirstFrame() {
                lowerCurtain(true);
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                buffering.show(playbackState == Player.STATE_BUFFERING);
                if (playbackState == Player.STATE_READY) handler.postDelayed(liftCurtain, CURTAIN_WAIT_MS);
                if (playbackState == Player.STATE_ENDED) ended();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                playButton.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
                // Paused: the controls stay, so it is clear why nothing moves.
                if (isPlaying) {
                    scheduleHide();
                    reconnects = 0;
                    reconnectAt = -1;
                } else if (player.getPlaybackState() == Player.STATE_READY) {
                    showChrome(true);
                }
            }

            @Override
            public void onTracksChanged(@NonNull Tracks tracks) {
                if (!tracksApplied && !tracks.getGroups().isEmpty()) {
                    tracksApplied = true;
                    applyTrackChoices();
                }
            }

            @Override
            public void onAudioSessionIdChanged(int audioSessionId) {
                leveler.apply(audioSessionId, prefs.getBoolean(PREF_LEVEL, false));
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException e) {
                Log.w(TAG, "Playback failed for " + itemId + " (" + stream.method + ")", e);
                // Audio the TV turned down (Dolby it cannot pass through after all): once more, decoded here.
                if (isAudioFailure(e) && !stream.compatibleAudio) {
                    reopen(o -> o.compatibleAudio = true);
                    return;
                }
                if (isNetworkFailure(e) && reconnect()) return;
                // A subtitle file the server could not make (it answers 404 for some videos): once more, with
                // subtitles off and any chosen later burned into the picture, rather than not at all.
                if (hasSidecars && isSourceFailure(e) && !stream.noSidecars) {
                    reopen(o -> {
                        o.noSidecars = true;
                        o.subtitleIndex = Playback.OFF;
                    });
                    return;
                }
                fail(e.getErrorCodeName());
            }
        });

        MediaItem.Builder item = new MediaItem.Builder().setUri(s.url).setMediaId(s.itemId);
        if (s.method != Playback.Method.DirectPlay) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        List<MediaItem.SubtitleConfiguration> subs = new ArrayList<>();
        for (Playback.Track t : s.subtitles) {
            String mime = "External".equals(t.delivery) && !t.url.isEmpty() ? subtitleMime(t.codec) : null;
            if (mime == null) continue;
            subs.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(t.url))
                    .setMimeType(mime)
                    .setLanguage(t.language.isEmpty() ? null : t.language)
                    .setLabel(t.title)
                    .setId(TrackMatcher.sidecarId(t.index))
                    .build());
        }
        item.setSubtitleConfigurations(subs);
        hasSidecars = !subs.isEmpty();
        // The TV switches as the stream opens, so the moment it blanks is lost in the wait to start.
        FrameRateMatch.apply(getWindow(), s.frameRate);
        player.setMediaItem(item.build(), s.startMs);
        player.setPlaybackSpeed(speed);
        player.setPlayWhenReady(true);
        player.prepare();

        Log.i(TAG, "Playing " + s.itemId + " (" + s.method + (s.reasons.isEmpty() ? "" : ": " + s.reasons)
                + ", " + s.frameRate + "fps)");
        Playback.started(account, s, s.startMs, false);
        lastReport = System.currentTimeMillis();
        handler.removeCallbacks(tick);
        handler.post(tick);
        if (!sameItem) showChrome(true);
    }

    /**
     * The decoders: the TV's own, then FFmpeg for audio it has none for (Dolby and DTS on a Chromecast). With
     * {@code decodeAudio}, nothing passes through to an amplifier, so all audio reaches the leveller and can change
     * speed.
     */
    private DefaultRenderersFactory renderers(boolean decodeAudio) {
        DefaultRenderersFactory factory = new DefaultRenderersFactory(this) {
            @Override
            protected AudioSink buildAudioSink(@NonNull Context context, boolean floatOutput,
                                               boolean playbackParams) {
                if (!decodeAudio) return super.buildAudioSink(context, floatOutput, playbackParams);
                return new DefaultAudioSink.Builder(context)
                        .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                        .setEnableFloatOutput(floatOutput)
                        .setEnableAudioTrackPlaybackParams(playbackParams)
                        .build();
            }
        };
        return factory.setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
    }

    /** The next episode's picture for its card, long before the card shows; the card goes without if none. */
    private void loadNextStill(Playback.Next n) {
        upNextStill.setImageDrawable(null);
        upNextStill.setVisibility(View.GONE);
        if (n == null || n.imageUrl.isEmpty()) return;
        String auth = Library.auth(account);
        Api.async(() -> Api.getImage(n.imageUrl, auth), new Api.Callback<android.graphics.Bitmap>() {
            @Override
            public void onResult(android.graphics.Bitmap picture) {
                if (picture == null || next != n) return;
                upNextStill.setImageBitmap(picture);
                upNextStill.setVisibility(View.VISIBLE);
            }

            @Override
            public void onError(Exception e) {
            }
        });
    }

    /** The next episode and the parts to skip, which arrive while it starts playing. */
    private void loadExtras(Playback.Stream s) {
        Api.async(() -> Playback.next(account, s), new Api.Callback<Playback.Next>() {
            @Override
            public void onResult(Playback.Next n) {
                if (stream == null || !stream.itemId.equals(s.itemId)) return;
                next = n;
                nextButton.setVisibility(n != null ? View.VISIBLE : View.GONE);
                loadNextStill(n);
            }

            @Override
            public void onError(Exception e) {
            }
        });
        Api.async(() -> Playback.segments(account, s), new Api.Callback<List<Playback.Segment>>() {
            @Override
            public void onResult(List<Playback.Segment> list) {
                if (stream != null && stream.itemId.equals(s.itemId)) segments = list;
            }

            @Override
            public void onError(Exception e) {
            }
        });
    }

    /**
     * Once the player knows the tracks: the audio and subtitles chosen (by the server, or by the user for an
     * earlier episode), picked out of them. Choices it cannot make itself open the stream again.
     */
    private void applyTrackChoices() {
        Playback.Stream s = stream;
        int audio = s.audioIndex;
        if (audioLanguage != null) {
            for (Playback.Track t : s.audio) {
                if (t.language.equals(audioLanguage)) {
                    audio = t.index;
                    break;
                }
            }
        }
        int sub = s.subtitleIndex;
        if (subtitleLanguage != null) {
            sub = Playback.OFF;
            for (Playback.Track t : s.subtitles) {
                if (!subtitleLanguage.isEmpty() && t.language.equals(subtitleLanguage)) {
                    sub = t.index;
                    break;
                }
            }
        }
        boolean audioOk = audio < 0 || TrackMatcher.selectAudio(player, s, audio);
        boolean subOk = TrackMatcher.selectSubtitle(player, s, sub);
        if ((!audioOk || !subOk) && !retriedTracks) {
            retriedTracks = true;
            int a = audio;
            int t = sub;
            reopen(o -> {
                o.audioIndex = a;
                o.subtitleIndex = t;
            });
            return;
        }
        // Opened again and still not there: what the server sent is what plays.
        if (audioOk) s.audioIndex = audio;
        if (subOk) s.subtitleIndex = sub;
    }

    private static boolean isAudioFailure(PlaybackException e) {
        return e instanceof ExoPlaybackException
                && ((ExoPlaybackException) e).type == ExoPlaybackException.TYPE_RENDERER
                && ((ExoPlaybackException) e).rendererFormat != null
                && MimeTypes.isAudio(((ExoPlaybackException) e).rendererFormat.sampleMimeType);
    }

    private static boolean isSourceFailure(PlaybackException e) {
        return e instanceof ExoPlaybackException && ((ExoPlaybackException) e).type == ExoPlaybackException.TYPE_SOURCE;
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

    /**
     * Text subtitles in the launcher's typeface, in the look chosen in the subtitles menu, rather than
     * Android's captions; PlayerView's own subtitles are hidden, since its cues come here (onCues).
     */
    private void styleSubtitles() {
        SubtitleView own = video.getSubtitleView();
        if (own != null) own.setVisibility(View.GONE);
        subs.setStyle(prefs.getInt(PREF_SUBTITLE_STYLE, SubtitleText.SHADOW));
    }

    private void showCues(CueGroup group) {
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder();
        List<Cue> pictures = new ArrayList<>();
        for (Cue cue : group.cues) {
            if (cue.bitmap != null) {
                pictures.add(cue);
            } else if (cue.text != null && cue.text.length() > 0) {
                if (text.length() > 0) text.append('\n');
                text.append(cue.text);
            }
        }
        subs.setCue(text);
        pictureSubs.setCues(pictures);
    }

    private void chooseSubtitleStyle(int style) {
        scheduleHide();
        prefs.edit().putInt(PREF_SUBTITLE_STYLE, style).apply();
        subs.setStyle(style);
    }

    private void fail(String detail) {
        stopPlayer();
        handler.removeCallbacksAndMessages(null);
        menu.hide();
        hideCorner();
        buffering.show(false);
        chrome.setVisibility(View.GONE);
        errorBody.setText(detail == null ? "" : detail);
        errorBody.setVisibility(detail == null || detail.isEmpty() ? View.GONE : View.VISIBLE);
        error.setVisibility(View.VISIBLE);
        lowerCurtain(false);
        // The Jellyfin app, when there is one, may play what this cannot.
        View openApp = findViewById(R.id.playerOpenApp);
        openApp.setVisibility(Library.hasApp(this) ? View.VISIBLE : View.GONE);
        if (openApp.getVisibility() == View.VISIBLE) openApp.requestFocus();
        else findViewById(R.id.playerClose).requestFocus();
    }

    // ---- the end, and what comes next ------------------------------------------------------------------

    private void ended() {
        reportStop(stream.durationMs > 0 ? stream.durationMs : player.getCurrentPosition());
        if (next != null && !upNextDismissed) playNext();
        else finish();
    }

    private void playNext() {
        if (next == null) return;
        Playback.Next n = next;
        retriedTracks = false;
        menu.hide();
        hideCorner();
        stopPlayer();
        Playback.Options o = firstOptions();
        o.maxBitrate = stream.maxBitrate;
        o.compatibleAudio |= stream.compatibleAudio;
        raiseCurtain(n.itemId);
        open(n.itemId, o);
    }

    /** The skip button or the next episode, in the corner, for the part playing now. */
    private void updateCorner() {
        if (stream == null || menu.isShowing()) return;
        long pos = player.getCurrentPosition();
        Playback.Segment now = null;
        for (Playback.Segment seg : segments) if (seg.contains(pos)) now = seg;

        long duration = stream.durationMs > 0 ? stream.durationMs : player.getDuration();
        boolean creditsMarked = false;
        for (Playback.Segment seg : segments) if (seg.kind == Playback.Segment.Kind.Credits) creditsMarked = true;
        boolean inCredits = now != null && now.kind == Playback.Segment.Kind.Credits
                || !creditsMarked && duration > 0 && duration - pos < UP_NEXT_BEFORE_END_MS;

        if (next != null && inCredits && !upNextDismissed) {
            if (!upNextShowing) {
                upNextShowing = true;
                upNextDeadline = System.currentTimeMillis() + UP_NEXT_SECONDS * 1000L;
                setSkip(null);
                showCornerView(upNext, true);
            }
            long left = Math.max(0, (upNextDeadline - System.currentTimeMillis() + 999) / 1000);
            upNextDetail.setText(getString(R.string.player_up_next, next.title, (int) left));
            if (left == 0 && player.isPlaying()) playNext();
            return;
        }
        if (upNextShowing && !inCredits) {
            upNextShowing = false;
            showCornerView(upNext, false);
        }
        if (!upNextShowing) setSkip(now != null && (now.kind != Playback.Segment.Kind.Credits || next == null) ? now : null);
    }

    private void setSkip(Playback.Segment seg) {
        if (seg == skipping) return;
        skipping = seg;
        if (seg != null) {
            switch (seg.kind) {
                case Intro: skip.setText(R.string.player_skip_intro); break;
                case Recap: skip.setText(R.string.player_skip_recap); break;
                case Preview: skip.setText(R.string.player_skip_preview); break;
                default: skip.setText(R.string.player_skip_credits); break;
            }
        }
        showCornerView(skip, seg != null);
    }

    private void skipSegment() {
        if (skipping == null || player == null) return;
        player.seekTo(skipping.endMs);
        setSkip(null);
    }

    private void showCornerView(View v, boolean show) {
        v.animate().cancel();
        if (show) {
            v.setVisibility(View.VISIBLE);
            v.setTranslationY(getResources().getDimension(R.dimen.space_md) + cornerLift());
            v.animate().alpha(1f).translationY(cornerLift()).setDuration(FADE_MS).setInterpolator(ease).start();
            // With the controls hidden it takes focus, so OK takes it; with them showing it waits its turn.
            if (!chromeShowing()) v.requestFocus();
        } else if (v.getVisibility() == View.VISIBLE) {
            boolean hadFocus = v.hasFocus();
            v.animate().alpha(0f).setDuration(FADE_MS).setInterpolator(ease)
                    .withEndAction(() -> v.setVisibility(View.GONE)).start();
            if (hadFocus && chromeShowing()) track.requestFocus();
        }
    }

    private void hideCorner() {
        upNextShowing = false;
        skipping = null;
        for (View v : new View[] {skip, upNext}) {
            v.animate().cancel();
            v.setAlpha(0f);
            v.setVisibility(View.GONE);
        }
    }

    /** With the controls showing, the corner sits clear above them, not resting on the seek bar. */
    private float cornerLift() {
        return chromeShowing() ? -(controls.getHeight() - getResources().getDimension(R.dimen.space_3xl)
                + getResources().getDimension(R.dimen.space_xl)) : 0f;
    }

    // ---- menus -----------------------------------------------------------------------------------------

    private void showMenu(View from, GlassMenu.Builder items) {
        handler.removeCallbacks(hideChrome);
        menu.show(from, items, (ViewGroup) chrome);
    }

    private void showSubtitles(View from) {
        if (stream == null) return;
        GlassMenu.Builder m = new GlassMenu.Builder()
                .add(GlassMenu.Item.heading(getString(R.string.player_subtitles)))
                .add(GlassMenu.Item.option(getString(R.string.player_off), null, stream.subtitleIndex == Playback.OFF,
                        () -> chooseSubtitle(Playback.OFF)));
        for (Playback.Track t : stream.subtitles) {
            String detail = "Encode".equals(t.delivery) && stream.method == Playback.Method.DirectPlay
                    ? getString(R.string.player_burned_in) : null;
            m.add(GlassMenu.Item.option(t.title, detail, t.index == stream.subtitleIndex, () -> chooseSubtitle(t.index)));
        }
        int style = prefs.getInt(PREF_SUBTITLE_STYLE, SubtitleText.SHADOW);
        // Below the languages, past a line: how they look.
        m.add(GlassMenu.Item.divider())
                .add(GlassMenu.Item.heading(getString(R.string.player_subtitle_style)))
                .add(GlassMenu.Item.option(getString(R.string.player_subtitle_shadow), null, style == SubtitleText.SHADOW,
                        () -> chooseSubtitleStyle(SubtitleText.SHADOW)))
                .add(GlassMenu.Item.option(getString(R.string.player_subtitle_glass), null, style == SubtitleText.GLASS,
                        () -> chooseSubtitleStyle(SubtitleText.GLASS)))
                .add(GlassMenu.Item.option(getString(R.string.player_subtitle_classic), null, style == SubtitleText.CLASSIC,
                        () -> chooseSubtitleStyle(SubtitleText.CLASSIC)));
        showMenu(from, m);
    }

    private void chooseSubtitle(int index) {
        scheduleHide();
        Playback.Track t = stream.subtitle(index);
        subtitleLanguage = t == null ? "" : t.language.isEmpty() ? null : t.language;
        if (TrackMatcher.selectSubtitle(player, stream, index)) {
            stream.subtitleIndex = index;
            report();
        } else {
            retriedTracks = false;
            reopen(o -> o.subtitleIndex = index);
        }
    }

    private void showAudio(View from) {
        if (stream == null) return;
        GlassMenu.Builder m = new GlassMenu.Builder().add(GlassMenu.Item.heading(getString(R.string.player_audio)));
        if (stream.audio.size() > 1) {
            for (Playback.Track t : stream.audio) {
                m.add(GlassMenu.Item.option(t.title, null, t.index == stream.audioIndex, () -> chooseAudio(t.index)));
            }
        }
        // A switch: ticked while on, and choosing it turns it the other way.
        boolean level = prefs.getBoolean(PREF_LEVEL, false);
        if (stream.audio.size() > 1) m.add(GlassMenu.Item.heading(getString(R.string.player_sound)));
        m.add(GlassMenu.Item.option(getString(R.string.player_level_volume), null, level, () -> setLevelling(!level)));
        showMenu(from, m);
    }

    private void chooseAudio(int index) {
        scheduleHide();
        Playback.Track t = stream.audioTrack(index);
        audioLanguage = t == null || t.language.isEmpty() ? null : t.language;
        if (TrackMatcher.selectAudio(player, stream, index)) {
            stream.audioIndex = index;
            report();
        } else {
            retriedTracks = false;
            reopen(o -> o.audioIndex = index);
        }
    }

    /**
     * Levelling works on audio the TV decodes, so turning it on reopens Dolby or DTS that was being passed
     * through to an amplifier, to be decoded here instead. Turning it off leaves the stream as it is.
     */
    private void setLevelling(boolean on) {
        scheduleHide();
        prefs.edit().putBoolean(PREF_LEVEL, on).apply();
        if (on && !stream.compatibleAudio && TrackMatcher.isPassthroughAudio(player)) {
            reopen(o -> o.compatibleAudio = true);
            return;
        }
        leveler.apply(player.getAudioSessionId(), on);
    }

    private String speedLabel(float s) {
        return s == 1f ? getString(R.string.player_speed_normal) : speedText(s) + "×";
    }

    private void showSpeed(View from) {
        GlassMenu.Builder m = new GlassMenu.Builder().add(GlassMenu.Item.heading(getString(R.string.player_speed)));
        for (float s : SPEEDS) {
            m.add(GlassMenu.Item.option(speedLabel(s), null, s == speed, () -> chooseSpeed(s)));
        }
        showMenu(from, m);
    }

    /** Dolby passed through to an amplifier plays at one speed only, so it is decoded here for any other. */
    private void chooseSpeed(float s) {
        scheduleHide();
        speed = s;
        if (s != 1f && !stream.compatibleAudio && TrackMatcher.isPassthroughAudio(player)) {
            reopen(o -> o.compatibleAudio = true);
            return;
        }
        player.setPlaybackSpeed(s);
    }

    private static String speedText(float s) {
        return s == (int) s ? String.valueOf((int) s) : String.valueOf(s);
    }

    private void showQuality(View from) {
        if (stream == null) return;
        GlassMenu.Builder m = new GlassMenu.Builder()
                .add(GlassMenu.Item.heading(getString(R.string.player_quality)))
                .add(GlassMenu.Item.option(getString(R.string.player_original), null,
                        stream.maxBitrate >= DeviceProfile.MAX_BITRATE, () -> chooseQuality(DeviceProfile.MAX_BITRATE)));
        for (int mbps : BITRATES_MBPS) {
            int bps = mbps * 1_000_000;
            if (stream.sourceBitrate > 0 && bps >= stream.sourceBitrate) continue;
            m.add(GlassMenu.Item.option(getString(R.string.player_quality_value, resolutionFor(mbps), String.valueOf(mbps)),
                    null, bps == stream.maxBitrate, () -> chooseQuality(bps)));
        }
        showMenu(from, m);
    }

    /** What the server makes of a bitrate, roughly. */
    private static String resolutionFor(int mbps) {
        if (mbps >= 40) return "4K";
        if (mbps >= 10) return "1080p";
        if (mbps >= 4) return "720p";
        return "480p";
    }

    private void chooseQuality(int bps) {
        scheduleHide();
        if (bps == stream.maxBitrate) return;
        reopen(o -> o.maxBitrate = bps);
    }

    private void showChapters(View from) {
        if (stream == null || player == null) return;
        long pos = player.getCurrentPosition();
        int current = 0;
        for (int i = 0; i < stream.chapterStarts.size(); i++) if (stream.chapterStarts.get(i) <= pos) current = i;
        GlassMenu.Builder m = new GlassMenu.Builder().add(GlassMenu.Item.heading(getString(R.string.player_chapters)));
        for (int i = 0; i < stream.chapterStarts.size(); i++) {
            long start = stream.chapterStarts.get(i);
            String name = stream.chapterNames.get(i);
            m.add(GlassMenu.Item.option(name.isEmpty() ? clock(start) : name, clock(start), i == current, () -> {
                scheduleHide();
                player.seekTo(start);
            }));
        }
        showMenu(from, m);
    }

    private static final int[] ZOOMS = {AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL};
    private static final int[] ZOOM_NAMES = {R.string.player_zoom_fit, R.string.player_zoom_crop,
            R.string.player_zoom_stretch};

    private void showZoom(View from) {
        GlassMenu.Builder m = new GlassMenu.Builder().add(GlassMenu.Item.heading(getString(R.string.player_zoom)));
        for (int i = 0; i < ZOOMS.length; i++) {
            int mode = ZOOMS[i];
            m.add(GlassMenu.Item.option(getString(ZOOM_NAMES[i]), null, video.getResizeMode() == mode, () -> {
                scheduleHide();
                video.setResizeMode(mode);
            }));
        }
        showMenu(from, m);
    }

    // ---- controls --------------------------------------------------------------------------------------

    private void showChrome(boolean show) {
        showChrome(show, playButton);
    }

    /**
     * Shows or hides the controls. Coming into view, {@code focus} takes focus: play/pause, so OK pauses rather
     * than pressing whatever had focus last time, or the seek bar when scrubbing brought them up.
     */
    private void showChrome(boolean show, View focus) {
        handler.removeCallbacks(hideChrome);
        boolean appearing = show && !chromeShown;
        chromeShown = show;
        if (show) {
            updateTimes();
            if (appearing && !menu.isShowing()) focus.requestFocus();
        } else {
            preview.hide();
            if (chrome.hasFocus()) {
                // Nothing visible keeps focus; a skip button in the corner takes it if one is showing.
                if (skip.getVisibility() == View.VISIBLE) skip.requestFocus();
                else if (upNext.getVisibility() == View.VISIBLE) upNext.requestFocus();
            }
        }
        chrome.animate().alpha(show ? 1f : 0f).setDuration(FADE_MS).setInterpolator(ease).start();
        // Subtitles rise above the controls while they show.
        pictureSubs.setBottomPaddingFraction(show ? 0.3f : SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION);
        float lift = show ? -(video.getHeight() * 0.3f - getResources().getDimension(R.dimen.subtitle_bottom)) : 0f;
        subs.animate().translationY(Math.min(0f, lift)).setDuration(FADE_MS).setInterpolator(ease).start();
        for (View v : new View[] {skip, upNext}) {
            if (v.getVisibility() == View.VISIBLE) {
                v.animate().translationY(cornerLift()).setDuration(FADE_MS).setInterpolator(ease).start();
            }
        }
        if (show && player != null && player.isPlaying()) scheduleHide();
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideChrome);
        if (!menu.isShowing()) handler.postDelayed(hideChrome, CHROME_MS);
    }

    private boolean chromeShowing() {
        return chromeShown;
    }

    private void updateTimes() {
        if (player == null || stream == null) return;
        long duration = player.getDuration() != C.TIME_UNSET ? player.getDuration() : stream.durationMs;
        long position = scrubTo >= 0 ? scrubTo : player.getCurrentPosition();
        // Where it is on the left; on the right how long is left and when that will be, all in one colour.
        time.setText(clock(position));
        long left = Math.max(0, duration - position);
        ends.setText(getString(R.string.player_remaining, clock(left)) + "  ·  "
                + getString(R.string.player_ends_at, DateFormat.getTimeFormat(this)
                        .format(new Date(System.currentTimeMillis() + (long) (left / speed)))));
        if (duration > 0) {
            int at = (int) (1000 * position / duration);
            seek.setProgress(at);
            seek.setSecondaryProgress((int) (1000 * player.getBufferedPosition() / duration));
            View bar = (View) seek.getParent();
            thumb.setTranslationX(at / 1000f * bar.getWidth() - thumb.getWidth() / 2f);
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

    /** How far one press moves while a key is held: further the longer it is held. */
    static long scrubStep(int repeat) {
        if (repeat < 6) return SKIP_MS;
        if (repeat < 20) return 30_000;
        return 60_000;
    }

    /** Scrubs, previewing the frame there when the server has frames; the seek waits for the last press. */
    private void scrub(long byMs) {
        long duration = player.getDuration();
        long from = scrubTo >= 0 ? scrubTo : player.getCurrentPosition();
        long to = Math.max(0, from + byMs);
        if (duration != C.TIME_UNSET) to = Math.min(to, Math.max(0, duration - 1000));
        scrubTo = to;
        updateTimes();
        long length = duration != C.TIME_UNSET ? duration : stream.durationMs;
        if (length > 0) preview.show(to, (float) to / length);
        handler.removeCallbacks(commitScrub);
        handler.postDelayed(commitScrub, SCRUB_SETTLE_MS);
    }

    private void togglePause() {
        if (player.getPlayWhenReady()) player.pause();
        else player.play();
        report();
    }

    private void report() {
        if (player == null || stream == null) return;
        lastReport = System.currentTimeMillis();
        Playback.progress(account, stream, player.getCurrentPosition(), !player.getPlayWhenReady());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (player == null || error.getVisibility() == View.VISIBLE || menu.isShowing()
                || event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event);
        }
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (key) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (down && event.getRepeatCount() == 0) togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (down) player.play();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (down) player.pause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                if (down) finish();
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (down) playNext();
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) {
                    showChrome(true, track);
                    scrub((key == KeyEvent.KEYCODE_MEDIA_REWIND ? -1 : 1) * scrubStep(event.getRepeatCount()));
                }
                return true;
            default:
                break;
        }
        if (!chromeShowing()) {
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (!down || event.getRepeatCount() > 0) return true;
                    if (upNext.getVisibility() == View.VISIBLE) playNext();
                    else if (skip.getVisibility() == View.VISIBLE) skipSegment();
                    else togglePause();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) {
                        showChrome(true, track);
                        scrub((key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1) * scrubStep(event.getRepeatCount()));
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_INFO:
                    if (down) showChrome(true);
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        // Controls showing: any key keeps them up a while longer.
        if (down) scheduleHide();
        if (track.isFocused()) {
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (down && event.getRepeatCount() == 0) togglePause();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) scrub((key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1) * scrubStep(event.getRepeatCount()));
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (menu.isShowing()) {
            menu.hide();
            scheduleHide();
            return;
        }
        if (player != null && upNext.getVisibility() == View.VISIBLE) {
            // Turned away: no countdown, and the credits play out to the end.
            upNextDismissed = true;
            upNextShowing = false;
            showCornerView(upNext, false);
            return;
        }
        if (player != null && chromeShowing()) {
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
        stopPlayer();
        handler.removeCallbacksAndMessages(null);
        if (!isFinishing()) finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayer();
        handler.removeCallbacksAndMessages(null);
    }

    private void reportStop(long positionMs) {
        if (stream == null || reportedStop) return;
        reportedStop = true;
        Playback.stopped(account, stream, positionMs);
    }

    /** Reports where it got to and lets the player go; the stream stays, for opening again. */
    private void stopPlayer() {
        subs.setCue(null);
        pictureSubs.setCues(Collections.emptyList());
        handler.removeCallbacks(commitScrub);
        scrubTo = -1;
        preview.hide();
        if (player != null) {
            reportStop(player.getCurrentPosition());
            leveler.release();
            video.setPlayer(null);
            player.release();
            player = null;
        }
    }
}
