package com.cobbletowers.network;

import com.cobbletowers.CobbleTowers;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Everything the play screen shows: towers on offer, the player's lobby, their party levels and the last server
 * message.
 * @param open true only when the player asked for the screen; a lobby change updates an open screen but never opens
 *     one
 */
public record PlayStatePayload(List<Tower> towers, Lobby lobby, List<Integer> partyLevels, String message,
                               boolean open) implements CustomPacketPayload {

    public record Tower(ResourceLocation id, String displayName) {
        static final StreamCodec<RegistryFriendlyByteBuf, Tower> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, Tower::id,
                ByteBufCodecs.STRING_UTF8, Tower::displayName,
                Tower::new);
    }

    /** {@code ready} is the rental team's ready-up (P33); always false outside a rental lobby. */
    public record Member(String name, boolean accepted, boolean ready) {
        static final StreamCodec<RegistryFriendlyByteBuf, Member> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Member::name,
                ByteBufCodecs.BOOL, Member::accepted,
                ByteBufCodecs.BOOL, Member::ready,
                Member::new);
    }

    /**
     * The Ascension (P30) the lobby will start at and the deepest the team may choose.
     * @param offered whether the tower ascends; when false the picker is hidden
     */
    /**
     * The Threat band the host picked (balance phase 2): {@code chosen} 0-6 (0 for none), {@code advice} the band AscensionLib advises for the team
     * (1-6), {@code offered} whether the server has Threat on and this is not a trial, and {@code bands} what each band is (AscensionLib's answer; empty when it
     * cannot say, and then a screen shows the choice without the detail). Advice only; every band can be picked.
     */
    public record Threat(int chosen, int advice, boolean offered, List<com.cobbletowers.ascension.ThreatBandInfo.Band> bands) {
        static final StreamCodec<RegistryFriendlyByteBuf, Threat> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Threat::chosen,
                ByteBufCodecs.VAR_INT, Threat::advice,
                ByteBufCodecs.BOOL, Threat::offered,
                com.cobbletowers.ascension.ThreatBandInfo.Band.STREAM_CODEC.apply(ByteBufCodecs.list(com.cobbletowers.ascension.ThreatBandInfo.MAX_BANDS)), Threat::bands,
                Threat::new);

        public static Threat none() {
            return new Threat(0, 1, false, List.of());
        }
    }

    public record Depth(int chosen, int max, boolean offered) {
        static final StreamCodec<RegistryFriendlyByteBuf, Depth> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Depth::chosen,
                ByteBufCodecs.VAR_INT, Depth::max,
                ByteBufCodecs.BOOL, Depth::offered,
                Depth::new);

        public static Depth none() {
            return new Depth(0, 0, false);
        }
    }

    /**
     * The rental ready-up (P33): whether the host confirmed the mode, whether this player has a finished draft,
     * whether they are ready, and whether the host is.
     */
    public record Readiness(boolean confirmed, boolean drafted, boolean mine, boolean host) {
        static final StreamCodec<RegistryFriendlyByteBuf, Readiness> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Readiness::confirmed,
                ByteBufCodecs.BOOL, Readiness::drafted,
                ByteBufCodecs.BOOL, Readiness::mine,
                ByteBufCodecs.BOOL, Readiness::host,
                Readiness::new);

        public static Readiness none() {
            return new Readiness(false, false, false, false);
        }
    }

    public record Modes(List<String> ids, List<String> names, String chosen, boolean rental, Readiness readiness) {
        static final StreamCodec<RegistryFriendlyByteBuf, Modes> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), Modes::ids,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), Modes::names,
                ByteBufCodecs.STRING_UTF8, Modes::chosen,
                ByteBufCodecs.BOOL, Modes::rental,
                Readiness.STREAM_CODEC, Modes::readiness,
                Modes::new);

        public static Modes none() {
            return new Modes(List.of(), List.of(), "", false, Readiness.none());
        }
    }

    /**
     * What the host can choose before starting: how deep (P30) and which mode (P32). Nested to stay under the codec's
     * field limit.
     */
    public record Options(Depth depth, Modes modes, List<Roster> rosters, Threat threat) {
        static final StreamCodec<RegistryFriendlyByteBuf, Options> STREAM_CODEC = StreamCodec.composite(
                Depth.STREAM_CODEC, Options::depth,
                Modes.STREAM_CODEC, Options::modes,
                Roster.STREAM_CODEC.apply(ByteBufCodecs.list(8)), Options::rosters,
                Threat.STREAM_CODEC, Options::threat,
                Options::new);

        public Options(Depth depth, Modes modes) {
            this(depth, modes, List.of(), Threat.none());
        }

        public Options(Depth depth, Modes modes, List<Roster> rosters) {
            this(depth, modes, rosters, Threat.none());
        }

        public static Options none() {
            return new Options(Depth.none(), Modes.none());
        }
    }

    /**
     * The Pokemon a teammate is bringing (P51), shown to the other players on the team and never to someone still only invited.
     * @param name the player's name, as in {@link Member}
     */
    public record Roster(String name, List<TowerHallStatePayload.Mon> team) {
        static final StreamCodec<RegistryFriendlyByteBuf, Roster> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Roster::name,
                TowerHallStatePayload.Mon.CODEC.apply(ByteBufCodecs.list(6)), Roster::team,
                Roster::new);
    }

    /**
     * The player's lobby.
     * @param role 0 none, 1 host, 2 invited, 3 on the team
     * @param selected the lobby's tower id, empty if none
     * @param countdown whole seconds until the run starts, or -1
     */
    public record Lobby(int role, String selected, String hostName, List<Member> members, int countdown, Options options) {
        static final StreamCodec<RegistryFriendlyByteBuf, Lobby> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Lobby::role,
                ByteBufCodecs.STRING_UTF8, Lobby::selected,
                ByteBufCodecs.STRING_UTF8, Lobby::hostName,
                Member.STREAM_CODEC.apply(ByteBufCodecs.list()), Lobby::members,
                ByteBufCodecs.VAR_INT, Lobby::countdown,
                Options.STREAM_CODEC, Lobby::options,
                Lobby::new);

        public static Lobby none() {
            return new Lobby(0, "", "", List.of(), -1, Options.none());
        }
    }

    public static final CustomPacketPayload.Type<PlayStatePayload> TYPE = new CustomPacketPayload.Type<>(
            CobbleTowers.id("play_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayStatePayload> STREAM_CODEC = StreamCodec.composite(
            Tower.STREAM_CODEC.apply(ByteBufCodecs.list()), PlayStatePayload::towers,
            Lobby.STREAM_CODEC, PlayStatePayload::lobby,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), PlayStatePayload::partyLevels,
            ByteBufCodecs.STRING_UTF8, PlayStatePayload::message,
            ByteBufCodecs.BOOL, PlayStatePayload::open,
            PlayStatePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
