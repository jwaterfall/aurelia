package app.aurelia.tv.jellyfin;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class JellyfinTest {
    @Test
    public void typedAddressesBecomeBaseAddresses() {
        assertEquals("http://192.168.1.10:8096", Api.normalizeAddress("192.168.1.10"));
        assertEquals("http://192.168.1.10:8096", Api.normalizeAddress(" 192.168.1.10:8096/ "));
        assertEquals("http://jellyfin.local:8920", Api.normalizeAddress("http://jellyfin.local:8920"));
        assertEquals("https://media.example.com", Api.normalizeAddress("https://media.example.com/"));
        assertEquals("http://nas:8096/jellyfin", Api.normalizeAddress("nas/jellyfin"));
        assertEquals("", Api.normalizeAddress("  "));
    }

    @Test
    public void timeLeftReadsNaturally() {
        long minute = 600_000_000L;
        assertEquals("", Library.remaining(30 * 10_000_000L));
        assertEquals("49m left", Library.remaining(49 * minute));
        assertEquals("1h left", Library.remaining(60 * minute));
        assertEquals("1h 4m left", Library.remaining(64 * minute));
        assertEquals("43m", Library.length(43 * minute));
    }
}
