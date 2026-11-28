package com.cobbletowers.network;

import com.cobbletowers.CobbleTowers;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The relics the player's run holds now, for the small strip in the top left of the HUD (P59). Sent when they change, when the player joins
 * mid-run, and empty when the run is over for them (which takes the strip down).
 */
public record RelicShelfPayload(List<Relic> relics) implements CustomPacketPayload {

    /** @param kind battle, sustain, tempo, fortune, scouting or champion (the frame's colour) */
    public record Relic(ResourceLocation id, String name, String kind) {
        static final StreamCodec<RegistryFriendlyByteBuf, Relic> CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, Relic::id,
                ByteBufCodecs.stringUtf8(64), Relic::name,
                ByteBufCodecs.stringUtf8(16), Relic::kind,
                Relic::new);
    }

    public static final CustomPacketPayload.Type<RelicShelfPayload> TYPE = new CustomPacketPayload.Type<>(CobbleTowers.id("relic_shelf"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RelicShelfPayload> STREAM_CODEC = StreamCodec.composite(
            Relic.CODEC.apply(ByteBufCodecs.list(16)), RelicShelfPayload::relics,
            RelicShelfPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
