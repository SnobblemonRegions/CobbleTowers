package com.cobbletowers.route;

import com.cobbletowers.definition.TowerContent;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.modifier.DraftService;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.runtime.ParticipantService;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

/**
 * Opens the route map for a player in a tower as a book (TODO: a route map the player can view in the tower). The book is built fresh from the run on every
 * open, so it always shows the floor the party is on and what each lane open now leads to. It is shown through a book put into the player's hand on the
 * client only; the real stack is sent back straight away, so nothing is ever added to the inventory. Server thread only.
 */
public final class RouteBookService {

    private RouteBookService() {}

    /**
     * The book for this run, or empty when the tower has no branching map.
     * @param pictures whether to draw it in the pack's picture font ({@link RouteBookMapPages}); otherwise the letter book, which needs no pack
     */
    public static Optional<ItemStack> bookFor(TowerContent content, PersistedRun run, boolean pictures) {
        var found = snapshot(content, run);
        if (found.isEmpty()) return Optional.empty();
        var s = found.get();
        List<Filterable<Component>> pages = new ArrayList<>();
        List<Component> drawn = pictures ? RouteBookMapPages.pages(s.map(), s.trail(), s.reveal()) : RouteBookPages.pages(s.map(), s.trail(), s.reveal(), s.cycle());
        for (Component page : drawn) pages.add(Filterable.passThrough(page));
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("Route Map"), "The Tower", 0, pages, true));
        return Optional.of(book);
    }

    /**
     * The map of a run as the party stands on it.
     * @param trail the lane the party took at each row from the start (row 0) to where it stands
     * @param reveal the highest row whose stops the party can see (a boss always shows)
     * @param cycle the cycle of an ascending tower, 0 for the first
     */
    public record Snapshot(RouteMap.Map map, List<Integer> trail, int reveal, int cycle) {}

    /** The map of the run, or empty when the tower has no branching map. */
    public static Optional<Snapshot> snapshot(TowerContent content, PersistedRun run) {
        var tower = content.towers().get(run.towerId());
        if (tower == null || !tower.routeMap()) return Optional.empty();
        var spot = DraftService.mapSpot(content, tower, run);
        int ahead = 1 + Math.max(0, DraftService.effects(run).scoutingBonus());
        int cycle = run.floorIndex() / Math.max(1, tower.floorCount());
        return Optional.of(new Snapshot(spot.map(), spot.trail(), spot.row() + 1 + ahead, cycle));
    }

    /** Whether the run's tower has a branching map to show. */
    public static boolean hasMap(TowerContent content, PersistedRun run) {
        var tower = content.towers().get(run.towerId());
        return tower != null && tower.routeMap();
    }

    /** Whether the player took the server resource pack, so the picture font draws (without it, its characters would show as boxes). */
    static boolean hasPack(ServerPlayer player) {
        try {
            return PolymerResourcePackUtils.hasMainPack(player);
        } catch (LinkageError absent) {
            return false;
        }
    }

    /** Opens the map for the player; returns the line to tell them when there is none to show. */
    public static Optional<String> open(ServerPlayer player) {
        Optional<PersistedRun> run = ParticipantService.runOf(player.getUUID());
        if (run.isEmpty()) return Optional.of("You are not in a tower run.");
        if (!hasMap(TowerDefinitionRegistry.content(), run.get())) return Optional.of("This tower has no route map: every floor is the same climb.");
        // a player with the resource pack gets the wide screen; without it the picture font would show as boxes, so the plain letter book is opened instead
        if (hasPack(player) && RouteScreenService.show(player, run.get())) return Optional.empty();
        Optional<ItemStack> book = bookFor(TowerDefinitionRegistry.content(), run.get(), false);
        if (book.isEmpty()) return Optional.of("This tower has no route map: every floor is the same climb.");
        InteractionHand hand = InteractionHand.MAIN_HAND;
        int slot = 36 + player.getInventory().selected;
        var menu = player.inventoryMenu;
        player.connection.send(new ClientboundContainerSetSlotPacket(menu.containerId, menu.incrementStateId(), slot, book.get()));
        player.connection.send(new ClientboundOpenBookPacket(hand));
        // put the real stack back on the client; the book window stays open over the top of it
        player.connection.send(new ClientboundContainerSetSlotPacket(menu.containerId, menu.incrementStateId(), slot, player.getItemInHand(hand)));
        return Optional.empty();
    }
}
