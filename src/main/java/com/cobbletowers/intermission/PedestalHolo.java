package com.cobbletowers.intermission;

import com.cobbletowers.network.IntermissionStatePayload;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** What a pedestal's three text pieces say (P55, "Arcade Cabinet"): the name, the big payout, and the detail block. */
final class PedestalHolo {

    /** The text in three pieces, and the colours the card is drawn with. */
    record Holo(Component name, Component big, Component body) {}

    private static final Pattern PAYOUT = Pattern.compile("\\+\\d+%");
    static final int MAX_DETAIL_CHARS = 30;
    static final int MAX_NAME_CHARS = 22;
    private static final int CYAN = 0x6AE4FF;
    private static final int RED = 0xFF6B6B;
    private static final int MAGENTA = 0xFF7BFF;
    private static final int DIM = 0x707078;
    private static final int GREEN = 0x6DFF9A;

    private PedestalHolo() {}

    static Holo build(IntermissionStatePayload.Card card, int votes, int voters, boolean settled, boolean chosen) {
        PedestalStyle.Style look = PedestalStyle.styleFor(card.risk());
        boolean dim = settled && !chosen;
        int accent = dim ? DIM : look.accent();

        Component name = text("▶ " + clip(titleOf(card).toUpperCase(Locale.ROOT), MAX_NAME_CHARS), accent, true, false);
        Component big = text(payoutOf(card), dim ? DIM : (card.risk() < 0 ? CYAN : 0xFFF35A), true, false);

        MutableComponent body = Component.empty();
        body.append(text(payoutOf(card).startsWith("+") ? "PAYOUT" : "SPECIAL ROOM", dim ? DIM : CYAN, false, false));
        body.append(line(card.risk() < 0 ? " " : "RISK " + "▮".repeat(card.risk() + 1) + "▯".repeat(2 - card.risk()), accent, false, false));
        body.append(line(pips(votes, voters), dim ? DIM : 0xFFFFFF, false, false));
        body.append(line(detailOf(card), dim ? DIM : RED, false, false));
        if (!settled) {
            body.append(line("Right-click: PICK", MAGENTA, false, true));
        } else {
            body.append(chosen ? line("✔ CHOSEN", GREEN, true, false) : line("not chosen", DIM, false, false));
        }
        return new Holo(name, big, body);
    }

    /** The card's payout bonus such as "+15%", or "EVENT" for a room and a dash for a modifier that pays nothing. */
    static String payoutOf(IntermissionStatePayload.Card card) {
        for (String line : card.lines()) {
            Matcher found = PAYOUT.matcher(line);
            if (found.find()) return found.group();
        }
        return card.risk() < 0 ? "EVENT" : "—";
    }

    /** A room's name runs on ("Forge: every player pays 125 Tower Tokens"): the part before the colon is its title. */
    static String titleOf(IntermissionStatePayload.Card card) {
        String raw = card.displayName();
        int colon = raw.indexOf(':');
        return card.risk() < 0 && colon > 0 ? raw.substring(0, colon).strip() : raw;
    }

    /** The card's downside (a "[-]" line) or, for a room, what follows its title; short enough for one row. */
    static String detailOf(IntermissionStatePayload.Card card) {
        if (card.risk() < 0) {
            String raw = card.displayName();
            int colon = raw.indexOf(':');
            if (colon > 0 && colon < raw.length() - 1) return clip(raw.substring(colon + 1).strip(), MAX_DETAIL_CHARS);
        }
        String detail = "";
        for (String line : card.lines()) {
            if (line.startsWith("[-]")) {
                detail = line.substring(3).strip();
                break;
            }
        }
        // No downside line: the first line that is not the upside, then the first line of all.
        for (String line : card.lines()) {
            if (!detail.isEmpty()) break;
            if (!line.startsWith("[+]")) detail = line.strip();
        }
        if (detail.isEmpty() && !card.lines().isEmpty()) detail = card.lines().get(0).strip();
        return clip(detail, MAX_DETAIL_CHARS);
    }

    static String clip(String text, int max) {
        String flat = text.replace('\n', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max - 1).stripTrailing() + "…";
    }

    static String pips(int votes, int voters) {
        int total = Math.max(1, Math.min(voters, 6));
        int cast = Math.max(0, Math.min(votes, total));
        return "●".repeat(cast) + "○".repeat(total - cast);
    }

    private static MutableComponent line(String text, int rgb, boolean bold, boolean italic) {
        return Component.literal("\n").append(text(text, rgb, bold, italic));
    }

    private static MutableComponent text(String text, int rgb, boolean bold, boolean italic) {
        return Component.literal(text).setStyle(Style.EMPTY.withColor(rgb).withBold(bold).withItalic(italic));
    }
}
