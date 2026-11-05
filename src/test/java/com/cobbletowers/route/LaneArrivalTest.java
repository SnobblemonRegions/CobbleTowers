package com.cobbletowers.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The look of a lane's arrival (P58): every kind of stop has a dressing of its own, and the props stand where the party can see them. */
class LaneArrivalTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("every kind of stop has its own dressing, never the generic purple set")
    void everyKindHasItsOwnDressing() {
        List<LaneArrival.Prop> generic = LaneArrival.generic();
        for (RouteGraph.Kind kind : RouteGraph.Kind.values()) {
            List<LaneArrival.Prop> props = LaneArrival.propsFor(kind.name());
            assertFalse(props.isEmpty(), kind + " has no props");
            assertNotEquals(generic, props, kind + " fell back to the generic dressing");
        }
    }

    @Test
    @DisplayName("an unknown or missing kind is read as a battle, as RouteGraph.Kind.parse does")
    void unknownKindIsABattle() {
        List<LaneArrival.Prop> battle = LaneArrival.propsFor("battle");
        assertEquals(battle, LaneArrival.propsFor("no_such_stop"));
        assertEquals(battle, LaneArrival.propsFor(null));
        assertEquals(battle, LaneArrival.propsFor(""));
    }

    @Test
    @DisplayName("the kind is read without regard to case")
    void kindIgnoresCase() {
        assertEquals(LaneArrival.propsFor("SHRINE"), LaneArrival.propsFor("shrine"));
    }

    @Test
    @DisplayName("props stand behind the party and never on the way to the arena door")
    void propsStandBehindTheParty() {
        BlockPos arrival = new BlockPos(10, 64, 48);
        for (RouteGraph.Kind kind : RouteGraph.Kind.values()) {
            for (LaneArrival.Prop prop : LaneArrival.propsFor(kind.name())) {
                assertTrue(prop.back() >= 1, kind + " has a prop at or ahead of the arrival spot");
                // Facing east (yaw 270): behind is west. Facing west (yaw 90): behind is east.
                assertTrue(LaneArrival.where(arrival, 270f, prop).getX() < arrival.getX(), kind + " east-facing");
                assertTrue(LaneArrival.where(arrival, 90f, prop).getX() > arrival.getX(), kind + " west-facing");
            }
        }
    }

    @Test
    @DisplayName("a prop keeps its height and its side whichever way the hall faces")
    void heightAndSideAreKept() {
        BlockPos arrival = new BlockPos(0, 10, 0);
        LaneArrival.Prop prop = new LaneArrival.Prop(4, -1, 1, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        assertEquals(new BlockPos(-4, 11, -1), LaneArrival.where(arrival, 270f, prop));
        assertEquals(new BlockPos(4, 11, -1), LaneArrival.where(arrival, 90f, prop));
    }
}
