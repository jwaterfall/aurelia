package com.gathin.tvhome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BannerShapeTest {
    @Test
    public void wideBannersAreKept() {
        assertTrue(InstalledApps.isBannerShaped(320, 180));
        assertTrue(InstalledApps.isBannerShaped(1920, 1080));
    }

    @Test
    public void squareAndTallPicturesAreNotBanners() {
        assertFalse(InstalledApps.isBannerShaped(64, 64));
        assertFalse(InstalledApps.isBannerShaped(256, 256));
        assertFalse(InstalledApps.isBannerShaped(100, 160));
    }

    @Test
    public void drawablesWithoutAnIntrinsicSizeStillFillTheTile() {
        assertTrue(InstalledApps.isBannerShaped(-1, -1));
        assertTrue(InstalledApps.isBannerShaped(0, 0));
    }
}
