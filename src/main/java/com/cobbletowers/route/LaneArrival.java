package com.cobbletowers.route;

import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * The look of a lane's arrival (P58): after the stair, the party stands in the arrival hall of the next floor, and a few blocks behind the spot
 * where it lands are dressed for the kind of stop the lane led through, so each route feels like a different way up. Cosmetic only: the props stand
 * clear of the way to the arena door and no block carries state of its own (no chests, furnaces or lecterns). Server thread only.
 */
public final class LaneArrival {

    private LaneArrival() {}

    /**
     * One block of a lane's dressing, placed relative to the arrival spot: {@code back} blocks behind the party (away from the arena door),
     * {@code side} blocks across (the hall is the same either side), {@code up} blocks above the floor the party stands on.
     */
    public record Prop(int back, int side, int up, BlockState block) {}

    /** The props for a kind of stop (a {@link RouteGraph.Kind} name); a kind with no dressing of its own gets {@link #generic()}. */
    public static List<Prop> propsFor(String kind) {
        return switch (RouteGraph.Kind.parse(kind == null ? "" : kind.toUpperCase(Locale.ROOT))) {
            case BATTLE -> List.of(
                    new Prop(4, -1, 0, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState()),
                    new Prop(4, 1, 0, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState()),
                    new Prop(4, -1, 1, Blocks.JACK_O_LANTERN.defaultBlockState()),
                    new Prop(4, 1, 1, Blocks.JACK_O_LANTERN.defaultBlockState()));
            case FORGE -> List.of(
                    new Prop(4, -1, 0, Blocks.MAGMA_BLOCK.defaultBlockState()),
                    new Prop(4, 0, 0, Blocks.ANVIL.defaultBlockState()),
                    new Prop(4, 1, 0, Blocks.MAGMA_BLOCK.defaultBlockState()),
                    new Prop(5, 0, 0, Blocks.LAVA_CAULDRON.defaultBlockState()));
            case LIBRARY -> List.of(
                    new Prop(4, -1, 0, Blocks.BOOKSHELF.defaultBlockState()),
                    new Prop(4, -1, 1, Blocks.BOOKSHELF.defaultBlockState()),
                    new Prop(4, 1, 0, Blocks.BOOKSHELF.defaultBlockState()),
                    new Prop(4, 1, 1, Blocks.BOOKSHELF.defaultBlockState()),
                    new Prop(4, 0, 0, Blocks.PURPLE_CARPET.defaultBlockState()));
            case REST -> List.of(
                    new Prop(4, -1, 0, Blocks.HAY_BLOCK.defaultBlockState()),
                    new Prop(4, 1, 0, Blocks.HAY_BLOCK.defaultBlockState()),
                    new Prop(4, 0, 0, Blocks.PURPLE_CARPET.defaultBlockState()));
            case SHRINE -> List.of(
                    new Prop(4, -1, 0, Blocks.AMETHYST_BLOCK.defaultBlockState()),
                    new Prop(4, 1, 0, Blocks.AMETHYST_BLOCK.defaultBlockState()),
                    new Prop(4, -1, 1, litCandle()),
                    new Prop(4, 1, 1, litCandle()),
                    new Prop(4, 0, 0, Blocks.SOUL_LANTERN.defaultBlockState()));
            case GAMBLER -> List.of(
                    new Prop(4, -1, 0, Blocks.GOLD_BLOCK.defaultBlockState()),
                    new Prop(4, 1, 0, Blocks.GOLD_BLOCK.defaultBlockState()),
                    new Prop(4, -1, 1, litCandle()),
                    new Prop(4, 1, 1, litCandle()),
                    new Prop(4, 0, 0, Blocks.RED_CARPET.defaultBlockState()));
            case VAULT -> List.of(
                    new Prop(4, -1, 0, Blocks.GILDED_BLACKSTONE.defaultBlockState()),
                    new Prop(4, -1, 1, Blocks.GILDED_BLACKSTONE.defaultBlockState()),
                    new Prop(4, 1, 0, Blocks.GILDED_BLACKSTONE.defaultBlockState()),
                    new Prop(4, 1, 1, Blocks.GILDED_BLACKSTONE.defaultBlockState()),
                    new Prop(5, 0, 0, Blocks.GOLD_BLOCK.defaultBlockState()));
            case POOL -> List.of(
                    new Prop(4, -1, 0, Blocks.PRISMARINE_BRICKS.defaultBlockState()),
                    new Prop(4, 1, 0, Blocks.PRISMARINE_BRICKS.defaultBlockState()),
                    new Prop(4, -1, 1, Blocks.SEA_LANTERN.defaultBlockState()),
                    new Prop(4, 1, 1, Blocks.SEA_LANTERN.defaultBlockState()),
                    new Prop(4, 0, 0, Blocks.LIGHT_BLUE_CARPET.defaultBlockState()));
            case TRICK_OR_TREAT -> List.of(
                    new Prop(4, -2, 0, Blocks.PUMPKIN.defaultBlockState()),
                    new Prop(4, -1, 0, Blocks.JACK_O_LANTERN.defaultBlockState()),
                    new Prop(4, 1, 0, Blocks.JACK_O_LANTERN.defaultBlockState()),
                    new Prop(4, 2, 0, Blocks.PUMPKIN.defaultBlockState()),
                    new Prop(4, 0, 0, Blocks.ORANGE_CARPET.defaultBlockState()));
            default -> generic();
        };
    }

    private static BlockState litCandle() {
        return Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.LIT, true);
    }

    /** A quiet purple dressing for any stop without a look of its own. */
    public static List<Prop> generic() {
        return List.of(
                new Prop(4, -1, 0, Blocks.AMETHYST_BLOCK.defaultBlockState()),
                new Prop(4, 1, 0, Blocks.AMETHYST_BLOCK.defaultBlockState()),
                new Prop(4, 0, 0, Blocks.PURPLE_CARPET.defaultBlockState()));
    }

    /** Where a prop stands for a party that arrives facing {@code yaw}: behind them is west when they face east (yaw 270) and east when they face west. */
    public static BlockPos where(BlockPos arrival, float yaw, Prop prop) {
        int behind = Math.round(yaw) % 360 == 270 ? -1 : 1;
        return arrival.offset(behind * prop.back(), prop.up(), prop.side());
    }

    /** Dresses the arrival at {@code arrival} for {@code kind}. Only air is replaced, so the hall's own blocks are never damaged. */
    public static void dress(ServerLevel level, BlockPos arrival, float yaw, String kind) {
        for (Prop prop : propsFor(kind)) {
            BlockPos pos = where(arrival, yaw, prop);
            if (!level.isLoaded(pos) || !level.getBlockState(pos).isAir()) continue;
            level.setBlock(pos, prop.block(), Block.UPDATE_CLIENTS);
        }
    }
}
