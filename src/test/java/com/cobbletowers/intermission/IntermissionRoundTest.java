package com.cobbletowers.intermission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** An intermission: who is ready, the cash-out vote, and the countdown. */
class IntermissionRoundTest {

    private static final UUID A = id(1);
    private static final UUID B = id(2);
    private static final UUID C = id(3);

    private static UUID id(int n) {
        return UUID.fromString("00000000-0000-0000-0000-00000000000" + n);
    }

    @Test
    @DisplayName("everyone in the electorate must be ready; one short is not")
    void allReady() {
        IntermissionRound round = new IntermissionRound();
        round.setReady(A, true);

        assertFalse(round.allReady(List.of(A, B)));
        round.setReady(B, true);
        assertTrue(round.allReady(List.of(A, B)));
    }

    @Test
    @DisplayName("an empty electorate is never ready: there is nobody to move")
    void emptyElectorate() {
        assertFalse(new IntermissionRound().allReady(List.of()));
        assertFalse(new IntermissionRound().cashOutPasses(List.of()));
    }

    @Test
    @DisplayName("a player who left the electorate stops being waited for")
    void leaverIsNotAwaited() {
        IntermissionRound round = new IntermissionRound();
        round.setReady(A, true);

        assertTrue(round.allReady(List.of(A)), "B dropped out of the run, so only A is asked");
    }

    @Test
    @DisplayName("cashing out needs a strict majority: one of two does not, two of three does")
    void majority() {
        IntermissionRound round = new IntermissionRound();
        round.voteCashOut(A, true);
        assertFalse(round.cashOutPasses(List.of(A, B)), "a tie falls back to playing on");

        round.voteCashOut(B, true);
        assertTrue(round.cashOutPasses(List.of(A, B)));
        assertTrue(round.cashOutPasses(List.of(A, B, C)));
    }

    @Test
    @DisplayName("exactly half voting to cash out is a tie, not a pass; no votes and a majority are not ties")
    void tie() {
        IntermissionRound round = new IntermissionRound();
        assertFalse(round.cashOutTied(List.of(A, B)), "no votes is not a tie");
        round.voteCashOut(A, true);
        assertTrue(round.cashOutTied(List.of(A, B)));
        assertFalse(round.cashOutPasses(List.of(A, B)));
        assertFalse(round.cashOutTied(List.of(A, B, C)), "one of three is a minority");
        round.voteCashOut(B, true);
        assertFalse(round.cashOutTied(List.of(A, B)), "both voting is a majority");
        assertFalse(round.cashOutTied(List.of()));
    }

    @Test
    @DisplayName("a solo player's own vote is a majority")
    void solo() {
        IntermissionRound round = new IntermissionRound();
        round.voteCashOut(A, true);

        assertTrue(round.cashOutPasses(List.of(A)));
    }

    @Test
    @DisplayName("not voting counts the same as voting to stay")
    void silenceIsStaying() {
        IntermissionRound round = new IntermissionRound();
        round.voteCashOut(A, true);
        round.voteCashOut(B, false);

        assertFalse(round.cashOutPasses(List.of(A, B, C)));
        assertFalse(round.votedCashOut(C));
    }

    @Test
    @DisplayName("the countdown runs to a deadline and any change to readiness or the vote cancels it")
    void countdown() {
        IntermissionRound round = new IntermissionRound();
        round.setReady(A, true);
        round.beginCountdown(1000, 5000);

        assertEquals(5, round.secondsLeft(1000));
        assertFalse(round.countdownDue(5999));
        assertTrue(round.countdownDue(6000));

        round.setReady(A, false);
        assertFalse(round.counting(), "un-readying cancels");

        round.beginCountdown(0, 5000);
        round.voteCashOut(B, true);
        assertFalse(round.counting(), "a changed vote cancels");
        assertEquals(-1, round.secondsLeft(0));
    }

    @Test
    @DisplayName("a walk has a deadline, shows its seconds, and is its own thing: readiness does not cancel it")
    void walk() {
        IntermissionRound round = new IntermissionRound();
        assertFalse(round.walking());
        round.beginWalk(1000, 90_000);
        assertTrue(round.walking());
        assertEquals(90, round.walkSecondsLeft(1000));
        assertEquals(1, round.walkSecondsLeft(90_999));
        assertFalse(round.walkDue(90_999));
        assertTrue(round.walkDue(91_000));

        round.setReady(A, false);
        round.voteCashOut(B, true);
        assertTrue(round.walking(), "the gate is already open: a changed mind does not close it");

        round.cancelWalk();
        assertFalse(round.walking());
        assertFalse(round.walkDue(Long.MAX_VALUE));
    }

    @Test
    @DisplayName("a round waiting on its next arena says so, survives the walk being cancelled, and checks about once a second")
    void awaitingArena() {
        IntermissionRound round = new IntermissionRound();
        assertFalse(round.awaitingArena());
        round.awaitArena(true);
        round.cancelWalk();
        assertTrue(round.awaitingArena(), "the walk ends before the arena is up; the wait must outlast it");
        assertTrue(round.arenaCheckDue(10_000));
        assertFalse(round.arenaCheckDue(10_500));
        assertTrue(round.arenaCheckDue(11_000));
        round.awaitArena(false);
        assertFalse(round.awaitingArena());
    }
}
