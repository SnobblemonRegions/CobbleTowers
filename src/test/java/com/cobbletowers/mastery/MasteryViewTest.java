package com.cobbletowers.mastery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.mastery.LeaderboardRules.Board;
import com.cobbletowers.mastery.LeaderboardRules.Entry;
import com.cobbletowers.mastery.LeaderboardRules.Member;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MasteryViewTest {

    @Test
    @DisplayName("durations read as minutes:seconds, with hours only when there are some")
    void durations() {
        assertEquals("0:00", MasteryView.duration(0));
        assertEquals("0:59", MasteryView.duration(59_999));
        assertEquals("12:34", MasteryView.duration(754_000));
        assertEquals("1:02:03", MasteryView.duration(3_723_000));
        assertEquals("0:00", MasteryView.duration(-5));
    }

    @Test
    @DisplayName("each board words its value its own way")
    void values() {
        assertEquals("Ascension 7", MasteryView.value(Board.ASCENSION, 7));
        assertEquals("score 40", MasteryView.value(Board.DIFFICULTY, 40));
        assertEquals("9:00", MasteryView.value(Board.SPEED, 540_000));
        assertEquals("1 cycle", MasteryView.value(Board.CLEARS, 1));
        assertEquals("12 cycles", MasteryView.value(Board.CLEARS, 12));
    }

    @Test
    @DisplayName("a row names the team, the value and the revisions the result was earned against")
    void rows() {
        Entry entry = new Entry(List.of(new Member(UUID.randomUUID(), "Ash"), new Member(UUID.randomUUID(), "Misty")),
                540_000, UUID.randomUUID(), 0, 15, 3, 7, "digest", 1L);
        String row = MasteryView.row(Board.SPEED, 2, entry);
        assertTrue(row.startsWith("#2 Ash, Misty  9:00"), row);
        assertTrue(row.contains("ruleset r3") && row.contains("tower r7"), row);
        assertTrue(row.contains("score 15"), row);
    }

    @Test
    @DisplayName("progress and perks are said plainly")
    void progress() {
        assertEquals("level 0 (Unranked), 1 to Bronze", MasteryView.progressLine(0));
        assertEquals("level 4 (Bronze), 1 to Silver", MasteryView.progressLine(4));
        assertEquals("level 30 (Champion, the top)", MasteryView.progressLine(30));
        assertEquals("none yet", MasteryView.perksLine(MasteryPerks.at(2)));
        assertEquals("vendor prices -3%", MasteryView.perksLine(MasteryPerks.at(5)));
        assertEquals("vendor prices -10%, +15% Tower Tokens, +10% Raid Points", MasteryView.perksLine(MasteryPerks.at(30)));
    }
}
