package app.aurelia.tv.jellyfin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class SegmentsTest {

    @Test
    public void anIntroChapterRunsToTheNextChapter() {
        List<Playback.Segment> s = Playback.fromChapters(Arrays.asList(0L, 62_000L), Arrays.asList("Intro", "Scene 1"),
                1_300_000L);
        assertEquals(1, s.size());
        assertEquals(Playback.Segment.Kind.Intro, s.get(0).kind);
        assertEquals(0L, s.get(0).startMs);
        assertEquals(62_000L, s.get(0).endMs);
    }

    @Test
    public void creditsAtTheEndRunToTheEnd() {
        List<Playback.Segment> s = Playback.fromChapters(Arrays.asList(0L, 5_400_000L), Arrays.asList("Chapter 1", "End Credits"),
                5_700_000L);
        assertEquals(1, s.size());
        assertEquals(Playback.Segment.Kind.Credits, s.get(0).kind);
        assertEquals(5_700_000L, s.get(0).endMs);
    }

    @Test
    public void numberedChaptersAreNotSkippable() {
        assertTrue(Playback.fromChapters(Arrays.asList(0L, 60_000L, 120_000L),
                Arrays.asList("Chapter 01", "Chapter 02", "Opening Night"), 200_000L).size() <= 1);
        assertTrue(Playback.fromChapters(Arrays.asList(0L, 60_000L), Arrays.asList("Chapter 01", "Chapter 02"),
                200_000L).isEmpty());
    }

    @Test
    public void aRecapIsItsOwnKind() {
        assertEquals(Playback.Segment.Kind.Recap, Playback.fromChapters(Arrays.asList(0L, 30_000L),
                Arrays.asList("Previously on", "Act One"), 100_000L).get(0).kind);
    }
}
