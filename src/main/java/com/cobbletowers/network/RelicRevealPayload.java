package com.cobbletowers.network;

import com.cobbletowers.CobbleTowers;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A relic the party just gained (P59 relic reveal), for the card that slides in on the HUD: its art is the bridge pack's
 * {@code relic_<path>.png}.
 * @param kind what sort of relic it is, for the card's colour: battle, sustain, tempo, fortune, scouting or champion
 * @param lines what it does, one line per effect ({@code [+] } a benefit, {@code [-] } a cost, as the draft cards write them)
 * @param floor the floor it was gained on, 1-based
 * @param held how many relics the run holds now, this one included
 */
public record RelicRevealPayload(ResourceLocation id, String name, String kind, List<String> lines, int floor, int held)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RelicRevealPayload> TYPE = new CustomPacketPayload.Type<>(CobbleTowers.id("relic_reveal"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RelicRevealPayload> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, RelicRevealPayload::id,
            ByteBufCodecs.stringUtf8(64), RelicRevealPayload::name,
            ByteBufCodecs.stringUtf8(16), RelicRevealPayload::kind,
            ByteBufCodecs.stringUtf8(200).apply(ByteBufCodecs.list(12)), RelicRevealPayload::lines,
            ByteBufCodecs.VAR_INT, RelicRevealPayload::floor,
            ByteBufCodecs.VAR_INT, RelicRevealPayload::held,
            RelicRevealPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
