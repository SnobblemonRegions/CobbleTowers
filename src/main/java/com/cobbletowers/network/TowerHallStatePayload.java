package com.cobbletowers.network;

import com.cobbletowers.CobbleTowers;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Read-only Hall snapshot. A separate channel leaves the existing lobby protocol unchanged. */
public record TowerHallStatePayload(List<Destination> towers, List<Trial> trials,
                                    PlayStatePayload play, String runStatus, boolean canResume,
                                    Extras extras) implements CustomPacketPayload {

    /** The native screens do not use the extras (P51), so they keep building the snapshot without them. */
    public TowerHallStatePayload(List<Destination> towers, List<Trial> trials, PlayStatePayload play, String runStatus,
                                 boolean canResume) {
        this(towers, trials, play, runStatus, canResume, Extras.NONE);
    }

    /** One Pokemon of the player's party, as the web Hall draws it. {@code species} is the sprite name. */
    /** @param hp health in percent, 0 to 100 */
    public record Mon(String name, String species, int level, boolean fainted, int hp, List<String> types) {
        static final StreamCodec<RegistryFriendlyByteBuf, Mon> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), Mon::name,
                ByteBufCodecs.stringUtf8(64), Mon::species,
                ByteBufCodecs.VAR_INT, Mon::level,
                ByteBufCodecs.BOOL, Mon::fainted,
                ByteBufCodecs.VAR_INT, Mon::hp,
                ByteBufCodecs.stringUtf8(16).apply(ByteBufCodecs.list(4)), Mon::types, Mon::new);
    }

    /** A player's mastery standing in one tower: the rank, the level, the gap to the next rank (-1 at the top). */
    public record Standing(ResourceLocation tower, String rank, int level, int toNext, int ascension) {
        static final StreamCodec<RegistryFriendlyByteBuf, Standing> CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, Standing::tower,
                ByteBufCodecs.stringUtf8(64), Standing::rank,
                ByteBufCodecs.VAR_INT, Standing::level,
                ByteBufCodecs.VAR_INT, Standing::toNext,
                ByteBufCodecs.VAR_INT, Standing::ascension, Standing::new);
    }

    /** What the Frontier Desk Hall (P51) shows beyond the native snapshot. */
    /** @param focusTower the tower the Hall opens on (a hub host or portal asked for it), or empty for the usual pick */
    public record Extras(String runTower, List<Mon> party, List<Standing> standings, String modeName, List<String> modeProblems,
                         String focusTower) {
        public static final Extras NONE = new Extras("", List.of(), List.of());
        public Extras(String runTower, List<Mon> party, List<Standing> standings) {
            this(runTower, party, standings, "", List.of(), "");
        }
        public Extras(String runTower, List<Mon> party, List<Standing> standings, String modeName, List<String> modeProblems) {
            this(runTower, party, standings, modeName, modeProblems, "");
        }
        static final StreamCodec<RegistryFriendlyByteBuf, Extras> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), Extras::runTower,
                Mon.CODEC.apply(ByteBufCodecs.list(6)), Extras::party,
                Standing.CODEC.apply(ByteBufCodecs.list(256)), Extras::standings,
                ByteBufCodecs.stringUtf8(64), Extras::modeName,
                ByteBufCodecs.stringUtf8(200).apply(ByteBufCodecs.list(12)), Extras::modeProblems,
                ByteBufCodecs.stringUtf8(64), Extras::focusTower, Extras::new);
    }

    /** @param endsAt epoch millis an event tower closes (P54); 0 for a tower that never does */
    public record Destination(ResourceLocation id, String name, int floors, int milestones, boolean ascends, long endsAt) {
        static final StreamCodec<RegistryFriendlyByteBuf, Destination> CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, Destination::id,
                ByteBufCodecs.stringUtf8(256), Destination::name,
                ByteBufCodecs.VAR_INT, Destination::floors,
                ByteBufCodecs.VAR_INT, Destination::milestones,
                ByteBufCodecs.BOOL, Destination::ascends,
                ByteBufCodecs.VAR_LONG, Destination::endsAt, Destination::new);

        public Destination(ResourceLocation id, String name, int floors, int milestones, boolean ascends) {
            this(id, name, floors, milestones, ascends, 0L);
        }
    }

    public record Trial(String kind, String revision, String title, ResourceLocation tower, List<String> rules) {
        static final StreamCodec<RegistryFriendlyByteBuf, Trial> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(16), Trial::kind,
                ByteBufCodecs.stringUtf8(256), Trial::revision,
                ByteBufCodecs.stringUtf8(256), Trial::title,
                ResourceLocation.STREAM_CODEC, Trial::tower,
                ByteBufCodecs.stringUtf8(1024).apply(ByteBufCodecs.list(32)), Trial::rules, Trial::new);
    }

    public static final Type<TowerHallStatePayload> TYPE = new Type<>(
            CobbleTowers.id("tower_hall_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TowerHallStatePayload> STREAM_CODEC = StreamCodec.composite(
            Destination.CODEC.apply(ByteBufCodecs.list(256)), TowerHallStatePayload::towers,
            Trial.CODEC.apply(ByteBufCodecs.list(2)), TowerHallStatePayload::trials,
            PlayStatePayload.STREAM_CODEC, TowerHallStatePayload::play,
            ByteBufCodecs.stringUtf8(1024), TowerHallStatePayload::runStatus,
            ByteBufCodecs.BOOL, TowerHallStatePayload::canResume,
            Extras.CODEC, TowerHallStatePayload::extras, TowerHallStatePayload::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
