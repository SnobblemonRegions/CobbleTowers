package com.cobbletowers.network;

import com.cobbletowers.CobbleTowers;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A run's vendor catalog now (TDS #16, #19) plus the caller's Tower Token balance (no always-on wallet HUD). One
 * catalog for every tower. The wire name {@code cobbleDollars} is kept: the balance is the same wallet, renamed for players only.
 * @param team everyone in the run, caller included, with whether they can be bought for now (TDS #18)
 * @param message the outcome of the last purchase attempt, or empty
 * @param shop what the Gilded Merchant's counter shows besides the catalog (P59); {@link Shop#NONE} for screens that do not draw it
 */
public record VendorCatalogPayload(long cobbleDollars, List<Entry> services, List<Teammate> team, String message, Shop shop)
        implements CustomPacketPayload {

    /** The web and native screens build the catalog without the counter's extras. */
    public VendorCatalogPayload(long cobbleDollars, List<Entry> services, List<Teammate> team, String message) {
        this(cobbleDollars, services, team, message, Shop.NONE);
    }

    /**
     * The counter shop (P59): where it stands, the merchant's line, every good with what it does (including those sold elsewhere), and each
     * teammate's party health so the screen can show who needs what.
     * @param tower the tower's display name
     * @param floor the floor the party is on, 1-based; 0 in the preparation room
     * @param floors how many floors the tower has
     * @param place {@code atrium} or {@code prep}
     * @param line what the merchant says
     * @param goods every good in the catalog, sold here or not, with its description
     * @param parties each teammate's party health, in {@code team} order
     */
    public record Shop(String tower, int floor, int floors, String place, String line, List<Good> goods, List<Party> parties) {
        public static final Shop NONE = new Shop("", 0, 0, "", "", List.of(), List.of());
        static final StreamCodec<RegistryFriendlyByteBuf, Shop> CODEC = StreamCodec.of(
                (buf, s) -> {
                    ByteBufCodecs.stringUtf8(64).encode(buf, s.tower());
                    ByteBufCodecs.VAR_INT.encode(buf, s.floor());
                    ByteBufCodecs.VAR_INT.encode(buf, s.floors());
                    ByteBufCodecs.stringUtf8(16).encode(buf, s.place());
                    ByteBufCodecs.stringUtf8(256).encode(buf, s.line());
                    Good.CODEC.apply(ByteBufCodecs.list(32)).encode(buf, s.goods());
                    Party.CODEC.apply(ByteBufCodecs.list(8)).encode(buf, s.parties());
                },
                buf -> new Shop(ByteBufCodecs.stringUtf8(64).decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.stringUtf8(16).decode(buf), ByteBufCodecs.stringUtf8(256).decode(buf),
                        Good.CODEC.apply(ByteBufCodecs.list(32)).decode(buf), Party.CODEC.apply(ByteBufCodecs.list(8)).decode(buf)));
    }

    /**
     * One good on the counter.
     * @param here whether it is sold where the vendor stands now (the prep stand sells run supplies only)
     * @param where for a good not sold here, where it is ("Sold on the floors")
     */
    public record Good(ResourceLocation id, String displayName, int price, String about, boolean here, String where) {
        static final StreamCodec<RegistryFriendlyByteBuf, Good> CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, Good::id,
                ByteBufCodecs.stringUtf8(64), Good::displayName,
                ByteBufCodecs.VAR_INT, Good::price,
                ByteBufCodecs.stringUtf8(256), Good::about,
                ByteBufCodecs.BOOL, Good::here,
                ByteBufCodecs.stringUtf8(64), Good::where,
                Good::new);
    }

    /** One teammate's party, in party order. */
    public record Party(UUID id, List<Mon> mons) {
        static final StreamCodec<RegistryFriendlyByteBuf, Party> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Party::id,
                Mon.CODEC.apply(ByteBufCodecs.list(6)), Party::mons,
                Party::new);
    }

    /**
     * @param hp percent of max HP, 0 when fainted
     * @param status Showdown's short status name ({@code brn}, {@code psn}, {@code tox}, {@code par}, {@code slp}, {@code frz}), or empty
     */
    public record Mon(String name, int hp, boolean fainted, String status) {
        static final StreamCodec<RegistryFriendlyByteBuf, Mon> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), Mon::name,
                ByteBufCodecs.VAR_INT, Mon::hp,
                ByteBufCodecs.BOOL, Mon::fainted,
                ByteBufCodecs.stringUtf8(8), Mon::status,
                Mon::new);
    }

    /** @param remainingPurchases -1 means unlimited (a wire sentinel) */
    public record Entry(ResourceLocation id, String displayName, int priceCobbleDollars, int remainingPurchases) {
        static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, Entry::id,
                ByteBufCodecs.STRING_UTF8, Entry::displayName,
                ByteBufCodecs.VAR_INT, Entry::priceCobbleDollars,
                ByteBufCodecs.VAR_INT, Entry::remainingPurchases,
                Entry::new);
    }

    /** @param online false for a teammate who is not connected: nothing can be applied to them. */
    public record Teammate(UUID id, String name, boolean online) {
        static final StreamCodec<RegistryFriendlyByteBuf, Teammate> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Teammate::id,
                ByteBufCodecs.STRING_UTF8, Teammate::name,
                ByteBufCodecs.BOOL, Teammate::online,
                Teammate::new);
    }

    public static final CustomPacketPayload.Type<VendorCatalogPayload> TYPE = new CustomPacketPayload.Type<>(
            CobbleTowers.id("vendor_catalog"));

    public static final StreamCodec<RegistryFriendlyByteBuf, VendorCatalogPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, VendorCatalogPayload::cobbleDollars,
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), VendorCatalogPayload::services,
            Teammate.STREAM_CODEC.apply(ByteBufCodecs.list()), VendorCatalogPayload::team,
            ByteBufCodecs.STRING_UTF8, VendorCatalogPayload::message,
            Shop.CODEC, VendorCatalogPayload::shop,
            VendorCatalogPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
