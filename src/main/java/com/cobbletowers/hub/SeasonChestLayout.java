package com.cobbletowers.hub;

/**
 * Where the season track's tiers sit in the Season Chest's screen and which floor marks are lit (owner, 23 Nov 2026). Pure, so it is tested
 * without the game.
 *
 * <p>The screen is a 9 x 6 chest: the top five rows are the path, the bottom row the controls. The path winds like a snake, left to right on
 * the first row, right to left on the next, so each tier sits next to the one before it; 45 tiers to a page.
 */
final class SeasonChestLayout {

    static final int COLUMNS = 9, PATH_ROWS = 5, PER_PAGE = COLUMNS * PATH_ROWS;
    /** The bottom row: page back, the season's summary, claim everything, page on. */
    static final int BACK_SLOT = 45, INFO_SLOT = 48, CLAIM_ALL_SLOT = 50, NEXT_SLOT = 53;

    private SeasonChestLayout() {}

    /** The chest slot of the {@code i}-th tier on a page (0-based). */
    static int slotOf(int i) {
        int row = i / COLUMNS, col = i % COLUMNS;
        return row * COLUMNS + (row % 2 == 0 ? col : COLUMNS - 1 - col);
    }

    /** Which tier on a page a path slot holds (0-based), or -1 for a slot off the path. */
    static int indexAt(int slot) {
        return slot < 0 || slot >= PER_PAGE ? -1 : slotOf(slot);          // turning each other row round undoes itself
    }

    static int pages(int tiers) {
        return Math.max(1, (tiers + PER_PAGE - 1) / PER_PAGE);
    }

    /** The page that holds a tier (0-based), so the screen opens where the player is. */
    static int pageOf(int tierIndex, int tiers) {
        return Math.max(0, Math.min(pages(tiers) - 1, tierIndex / PER_PAGE));
    }

    /**
     * Whether floor mark {@code k} of {@code marks} is lit: the marks share the whole track out evenly, so the ring fills as the season does
     * and the last mark lights with the last tier.
     */
    static boolean lit(int k, int marks, int reached, int tiers) {
        if (tiers <= 0 || marks <= 0) return false;
        int needs = (int) Math.ceil((k + 1) * (double) tiers / marks);
        return reached >= needs;
    }
}
