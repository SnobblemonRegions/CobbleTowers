package com.cobbletowers.trick;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cobbletowers.trick.TrickOrTreatService.Click;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a click on the Jack-o'-Lantern Vault does (TODO #6): the pedestal vote only unlocks it, and the click on the chest itself opens it. */
class TrickClickTest {

    // mine, rareActive, opened, unlocked, voteOpen
    private static Click click(boolean mine, boolean rare, boolean opened, boolean unlocked, boolean vote) {
        return TrickOrTreatService.clickAction(mine, rare, opened, unlocked, vote);
    }

    @Test
    @DisplayName("once the party has voted to open it, a click on the chest opens it")
    void unlockedChestOpensOnAClick() {
        assertEquals(Click.OPEN_NOW, click(true, false, false, true, false));
    }

    @Test
    @DisplayName("before the party has voted, the chest does not open: a click only casts a vote while the vote is on, and otherwise the vault stays shut")
    void lockedChestDoesNotOpen() {
        assertEquals(Click.VOTE, click(true, false, false, false, true));
        assertEquals(Click.SHUT, click(true, false, false, false, false));
    }

    @Test
    @DisplayName("a refused vault (no unlock, no vote) stays shut however often it is clicked")
    void aRefusedVaultStaysShut() {
        for (int i = 0; i < 3; i++) assertEquals(Click.SHUT, click(true, false, false, false, false));
    }

    @Test
    @DisplayName("another team's vault is not yours, whatever state it is in")
    void foreignVault() {
        assertEquals(Click.FOREIGN, click(false, false, false, true, false));
        assertEquals(Click.FOREIGN, click(false, true, true, true, true));
    }

    @Test
    @DisplayName("an open vault says so, and a rare that is out turns a click into a throw before anything else")
    void openAndRare() {
        assertEquals(Click.ALREADY_OPEN, click(true, false, true, true, false));
        assertEquals(Click.THROW, click(true, true, true, false, false));
    }

    @Test
    @DisplayName("an unlocked chest wins over an open vote, so a late click is never counted as a vote")
    void unlockedBeatsAVote() {
        assertEquals(Click.OPEN_NOW, click(true, false, false, true, true));
    }
}
