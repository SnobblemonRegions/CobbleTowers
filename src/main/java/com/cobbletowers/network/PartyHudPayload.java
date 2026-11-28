package com.cobbletowers.network;

import com.cobbletowers.CobbleTowers;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The party roster card on the HUD (owner, 20 Nov 2026): a row per member, invites still open, and, while the party is gathered at a
 * gate of the Tower Hub, that gate's footer. Sent when any of it changes; an empty member list takes the card down.
 *
 * @param members leader first; then members; then open invites
 * @param gate    the gate the party holds, or {@link Gate#NONE}
 */
public record PartyHudPayload(List<Member> members, Gate gate) implements CustomPacketPayload {

    /**
     * @param state hub (in the Tower Hub), gate (gathered at the party's gate), run (in a tower run), away (elsewhere or offline),
     *              invited (an invite the leader sent, not yet answered)
     */
    public record Member(UUID id, String name, boolean leader, String state) {
        static final StreamCodec<RegistryFriendlyByteBuf, Member> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Member::id,
                ByteBufCodecs.stringUtf8(16), Member::name,
                ByteBufCodecs.BOOL, Member::leader,
                ByteBufCodecs.stringUtf8(8), Member::state,
                Member::new);
    }

    /**
     * @param tower     the tower's id path (neutral, tideforge...), empty for none
     * @param name      its display name
     * @param color     its portal colour, 0xRRGGBB
     * @param here      members gathered at the gate
     * @param total     members in the party
     * @param threat    "III", "off" (no Threat band), or "" when the server has Threat switched off
     * @param ascension "2", or "" when the tower does not ascend
     */
    public record Gate(String tower, String name, int color, int here, int total, String threat, String ascension) {
        public static final Gate NONE = new Gate("", "", 0, 0, 0, "", "");

        // seven fields: one more than StreamCodec.composite takes
        static final StreamCodec<RegistryFriendlyByteBuf, Gate> CODEC = StreamCodec.of((buf, g) -> {
            ByteBufCodecs.stringUtf8(32).encode(buf, g.tower());
            ByteBufCodecs.stringUtf8(48).encode(buf, g.name());
            buf.writeInt(g.color());
            ByteBufCodecs.VAR_INT.encode(buf, g.here());
            ByteBufCodecs.VAR_INT.encode(buf, g.total());
            ByteBufCodecs.stringUtf8(8).encode(buf, g.threat());
            ByteBufCodecs.stringUtf8(8).encode(buf, g.ascension());
        }, buf -> new Gate(ByteBufCodecs.stringUtf8(32).decode(buf), ByteBufCodecs.stringUtf8(48).decode(buf), buf.readInt(),
                ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.stringUtf8(8).decode(buf),
                ByteBufCodecs.stringUtf8(8).decode(buf)));
    }

    public static final CustomPacketPayload.Type<PartyHudPayload> TYPE = new CustomPacketPayload.Type<>(CobbleTowers.id("party_hud"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PartyHudPayload> STREAM_CODEC = StreamCodec.composite(
            Member.CODEC.apply(ByteBufCodecs.list(12)), PartyHudPayload::members,
            Gate.CODEC, PartyHudPayload::gate,
            PartyHudPayload::new);

    public static final PartyHudPayload EMPTY = new PartyHudPayload(List.of(), Gate.NONE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
