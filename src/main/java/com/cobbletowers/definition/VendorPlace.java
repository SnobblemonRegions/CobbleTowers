package com.cobbletowers.definition;

import java.util.Locale;

/** Where a vendor service is on sale (P59): the atrium between floors, the preparation room before floor 1, or both. */
public enum VendorPlace {
    /** The vendor stand between floors, at an intermission. Every service is sold here unless its file says otherwise. */
    ATRIUM,
    /** The stand in the preparation room, before floor 1: run supplies only (owner, 2026-11-13). */
    PREP;

    public static VendorPlace parse(String raw) {
        try {
            return valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("'sold_in' entries must be atrium or prep, got '" + raw + "'");
        }
    }
}
