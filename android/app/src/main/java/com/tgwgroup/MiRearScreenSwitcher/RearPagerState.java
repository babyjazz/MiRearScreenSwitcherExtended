package com.tgwgroup.MiRearScreenSwitcher;

/**
 * Pure page-selection logic for the rear pager. No Android imports so it stays
 * unit-testable under plain JUnit.
 *
 * Pages are fixed: MUSIC (0), NOTIFICATION (1), CHARGING (2). Auto-switch
 * priority is NOTIFICATION > CHARGING > MEDIA; when no feature is active the
 * last user page wins.
 */
public final class RearPagerState {
    public static final int MUSIC = 0;
    public static final int NOTIFICATION = 1;
    public static final int CHARGING = 2;
    public static final int PAGES = 3;

    private int lastUserPage = MUSIC;
    private int savedPage = MUSIC;

    public int getLastUserPage() {
        return lastUserPage;
    }

    /** Record which page the user swiped to manually. Out-of-range clamps. */
    public void markUserPage(int index) {
        if (index < 0) {
            lastUserPage = MUSIC;
        } else if (index >= PAGES) {
            lastUserPage = PAGES - 1;
        } else {
            lastUserPage = index;
        }
    }

    /**
     * Which page should be frontmost given current feature activity.
     * Priority: notification, then charging, then media; otherwise the last
     * user page.
     */
    public int decidePage(boolean notificationActive, boolean chargingActive, boolean mediaActive) {
        if (notificationActive) return NOTIFICATION;
        if (chargingActive) return CHARGING;
        if (mediaActive) return MUSIC;
        return lastUserPage;
    }

    /** Remember the page to restore after the rear sleeps. */
    public int saveForSleep() {
        savedPage = lastUserPage;
        return savedPage;
    }

    /** Page to restore after wake. */
    public int restoreAfterWake() {
        return savedPage;
    }
}
