package com.cobbletowers.network;

import com.cobbletowers.CobbleTowers;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * What the Haunted rare tray shows (P58): a click-through HUD strip while a treat holds a rare. {@code marks} has one letter per throw,
 * {@code h} landed, {@code m} missed, {@code -} not thrown yet. {@code open == false} takes the tray down.
 */
public record HauntedRareTrayPayload(boolean open, String name, int level, int chancePercent, String marks, String message) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<HauntedRareTrayPayload> TYPE = new CustomPacketPayload.Type<>(CobbleTowers.id("haunted_rare_tray"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HauntedRareTrayPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, HauntedRareTrayPayload::open,
            ByteBufCodecs.STRING_UTF8, HauntedRareTrayPayload::name,
            ByteBufCodecs.VAR_INT, HauntedRareTrayPayload::level,
            ByteBufCodecs.VAR_INT, HauntedRareTrayPayload::chancePercent,
            ByteBufCodecs.STRING_UTF8, HauntedRareTrayPayload::marks,
            ByteBufCodecs.STRING_UTF8, HauntedRareTrayPayload::message,
            HauntedRareTrayPayload::new);

    /** The tray taken down. */
    public static HauntedRareTrayPayload closed() {
        return new HauntedRareTrayPayload(false, "", 0, 0, "---", "");
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
