package com.cobbletowers.route;

import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.runtime.ParticipantService;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.MenuType;

/**
 * Opens the route screen and turns its pages. A page change sends the chest's open-screen packet again with the new title and then the slots, which swaps the
 * picture without closing the screen (so the mouse stays where it is). Server thread only.
 */
public final class RouteScreenService {

    private RouteScreenService() {}

    /** Opens the screen on the page where the party's next choice is, or refreshes it if it is already open. @return false when there is no map to show */
    public static boolean show(ServerPlayer player, PersistedRun run) {
        return render(player, run, -1);
    }

    /** One page back (-1) or on (+1). Closes the screen if the player is no longer in a run with a map. */
    static void turn(ServerPlayer player, RouteScreenMenu menu, int delta) {
        var run = ParticipantService.runOf(player.getUUID());
        if (run.isEmpty()) {
            player.closeContainer();
            return;
        }
        var snapshot = RouteBookService.snapshot(TowerDefinitionRegistry.content(), run.get());
        if (snapshot.isEmpty()) {
            player.closeContainer();
            return;
        }
        // A button that is not drawn (no page that way) does nothing, so a click on its slot cannot make the server resend the whole screen.
        int target = menu.page() + delta;
        if (target < 0 || target >= RouteScreenLayout.pages(snapshot.get().map())) return;
        render(player, run.get(), target);
    }

    /** How many ticks must pass before the screen is drawn again for the same player: a refresh is two packets of several kilobytes. */
    static final int REDRAW_TICKS = 3;

    private static boolean render(ServerPlayer player, PersistedRun run, int page) {
        var snapshot = RouteBookService.snapshot(TowerDefinitionRegistry.content(), run);
        if (snapshot.isEmpty()) return false;
        long now = player.level().getGameTime();
        if (player.containerMenu instanceof RouteScreenMenu open && open.drawnWithin(now, REDRAW_TICKS)) return true;
        var s = snapshot.get();
        int shown = page < 0 ? RouteScreenLayout.pageOf(s.map(), s.trail()) : page;
        RouteScreenLayout.View view = RouteScreenLayout.build(s.map(), s.trail(), s.reveal(), shown);
        if (player.containerMenu instanceof RouteScreenMenu open) {
            open.drawnAt(now);
            open.show(view);
            player.connection.send(new ClientboundOpenScreenPacket(open.containerId, MenuType.GENERIC_9x6, view.title()));
            open.sendAllDataToRemote();
        } else {
            player.openMenu(new SimpleMenuProvider((id, inventory, p) -> {
                RouteScreenMenu created = new RouteScreenMenu(id, view);
                created.drawnAt(now);
                return created;
            }, Component.empty().append(view.title())));
        }
        return true;
    }
}
