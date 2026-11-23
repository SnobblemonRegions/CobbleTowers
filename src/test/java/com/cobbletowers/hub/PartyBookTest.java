package com.cobbletowers.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.hub.PartyBook.Click;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The hub's parties (owner, 20 Nov 2026): sneak + right-click to invite and accept, the leader removes, the warp pad merges. */
class PartyBookTest {

    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID(), e = UUID.randomUUID();
    private final PartyBook book = new PartyBook();

    @Test
    @DisplayName("clicking someone invites them; their click back makes a party led by the one who asked")
    void inviteAndAccept() {
        assertEquals(Click.INVITED, book.click(a, b, 0).click());
        assertTrue(book.invited(b, a, 1));
        assertTrue(book.partyOf(a).isEmpty(), "an invite alone makes no party");
        assertEquals(Click.REINVITED, book.click(a, b, 5).click());
        PartyBook.Result back = book.click(b, a, 10);
        assertEquals(Click.JOINED, back.click());
        assertEquals(a, back.party().leader());
        assertEquals(List.of(a, b), back.party().members());
        assertFalse(book.invited(b, a, 11), "the invite is used up");
    }

    @Test
    @DisplayName("an invite lapses after a minute, and then a click back is a new invite")
    void lapse() {
        book.click(a, b, 0);
        assertEquals(1, book.expire(PartyBook.INVITE_MILLIS).size());
        assertEquals(Click.INVITED, book.click(b, a, PartyBook.INVITE_MILLIS + 1).click());
        assertTrue(book.partyOf(a).isEmpty());
    }

    @Test
    @DisplayName("only the leader invites more or removes; a removal that leaves one player ends the party")
    void leaderRules() {
        book.click(a, b, 0);
        book.click(b, a, 1);
        assertEquals(Click.NOT_LEADER, book.click(b, c, 2).click(), "a member cannot invite");
        assertEquals(Click.NOT_LEADER, book.click(b, a, 3).click(), "a member cannot remove the leader");
        assertEquals(Click.INVITED, book.click(a, c, 4).click());
        assertEquals(Click.JOINED, book.click(c, a, 5).click());
        assertEquals(3, book.partyOf(a).orElseThrow().size());
        assertEquals(Click.REMOVED, book.click(a, c, 6).click());
        assertTrue(book.partyOf(c).isEmpty());
        assertEquals(Click.REMOVED, book.click(a, b, 7).click());
        assertTrue(book.partyOf(a).isEmpty(), "a party of one is no party");
    }

    @Test
    @DisplayName("leaving passes the lead on; nobody joins two parties; a party holds four")
    void leaveAndLimits() {
        book.click(e, a, 0);
        book.click(a, b, 0);
        book.click(b, a, 1);
        book.click(c, d, 2);
        book.click(d, c, 3);
        assertEquals(Click.THEY_ARE_IN_A_PARTY, book.click(a, c, 4).click());
        assertFalse(book.invited(a, e, 6), "joining a party drops the invites waiting for you");
        assertEquals(Click.INVITED, book.click(a, e, 6).click(), "so clicking e back invites e into a's party instead");
        book.leave(a);
        assertTrue(book.partyOf(b).isEmpty(), "b was left alone, so that party ended");
        UUID f = UUID.randomUUID();
        book.click(c, a, 7);
        book.click(a, c, 8);
        book.click(c, b, 9);
        book.click(b, c, 10);
        assertEquals(4, book.partyOf(c).orElseThrow().size());
        assertEquals(Click.FULL, book.click(c, f, 11).click());
        book.leave(c);
        assertEquals(d, book.partyOf(a).orElseThrow().leader(), "the next member leads");
    }

    @Test
    @DisplayName("the warp pad: two strangers start a party; a party standing there takes in the rest; another party is left alone")
    void pad() {
        assertEquals(List.of(), book.gather(List.of(a), 0), "one player alone is no party");
        assertEquals(List.of(a, b), book.gather(List.of(a, b), 0));
        assertEquals(a, book.partyOf(b).orElseThrow().leader());
        book.click(d, e, 1);
        book.click(e, d, 2);
        assertEquals(List.of(c), book.gather(List.of(d, a, b, c), 3), "the party with more of its members there takes c");
        assertEquals(3, book.partyOf(a).orElseThrow().size());
        assertEquals(2, book.partyOf(d).orElseThrow().size());
    }
}
