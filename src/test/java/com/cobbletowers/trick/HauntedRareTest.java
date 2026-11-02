package com.cobbletowers.trick;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HauntedRareTest {

    @Test
    void marksArePaddedToTheThrowCount() {
        assertEquals("---", HauntedRare.marksOf(null));
        assertEquals("m--", HauntedRare.marksOf(new StringBuilder("m")));
        assertEquals("mmh", HauntedRare.marksOf(new StringBuilder("mmh")));
    }
}
