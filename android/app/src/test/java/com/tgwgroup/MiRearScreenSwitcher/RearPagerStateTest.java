package com.tgwgroup.MiRearScreenSwitcher;

import org.junit.Test;
import static org.junit.Assert.*;

public class RearPagerStateTest {
    @Test public void priorityOrder_NotiAboveChargingAboveMedia() {
        RearPagerState s = new RearPagerState();
        assertTrue(s.decidePage(true, false, false) == RearPagerState.NOTIFICATION);
        assertTrue(s.decidePage(false, true, false) == RearPagerState.CHARGING);
        assertTrue(s.decidePage(false, false, true) == RearPagerState.MUSIC);
        assertTrue(s.decidePage(true, true, true) == RearPagerState.NOTIFICATION);
        assertTrue(s.decidePage(false, true, true) == RearPagerState.CHARGING);
    }

    @Test public void noFeatureKeepsLastUserPage() {
        RearPagerState s = new RearPagerState();
        s.markUserPage(RearPagerState.CHARGING);
        assertEquals(RearPagerState.CHARGING, s.decidePage(false, false, false));
    }

    @Test public void markUserPageClamps() {
        RearPagerState s = new RearPagerState();
        s.markUserPage(99);
        assertEquals(RearPagerState.PAGES - 1, s.decidePage(false, false, false));
        s.markUserPage(-1);
        assertEquals(RearPagerState.MUSIC, s.decidePage(false, false, false));
    }

    @Test public void saveAndRestoreAfterWake() {
        RearPagerState s = new RearPagerState();
        s.markUserPage(RearPagerState.NOTIFICATION);
        s.saveForSleep();
        s.markUserPage(RearPagerState.CHARGING);
        assertEquals(RearPagerState.NOTIFICATION, s.restoreAfterWake());
    }
}
