package com.cobbletowers.network;

import com.cobbletowers.CobbleTowers;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * One thing the player did on the play screen. {@code argument} is a tower id for {@code SELECT_TOWER}, a player name
 * for {@code INVITE}, a host name for {@code ACCEPT}/{@code DECLINE}, else unused. Re-checked by the server.
 */
public record PlayActionPayload(Action action, String argument) implements CustomPacketPayload {

    public enum Action { SELECT_TOWER, INVITE, ACCEPT, DECLINE, START, LEAVE, REFRESH, OPEN_CHOOSER, TOGGLE_POKEMON, CLEAR_CHOICE, SET_ASCENSION, SET_PLAYLIST, READY, UNREADY, CONFIRM_MODE, SET_THREAT }

    public static final CustomPacketPayload.Type<PlayActionPayload> TYPE = new CustomPacketPayload.Type<>(
            CobbleTowers.id("play_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.idMapper(i -> Action.values()[Math.floorMod(i, Action.values().length)], Action::ordinal),
            PlayActionPayload::action,
            ByteBufCodecs.STRING_UTF8, PlayActionPayload::argument,
            PlayActionPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
