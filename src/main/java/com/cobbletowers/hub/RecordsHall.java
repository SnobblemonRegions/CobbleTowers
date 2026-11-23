package com.cobbletowers.hub;

import com.cobbletowers.definition.AchievementRegistry;
import com.cobbletowers.definition.TowerDefinition;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.definition.TrialPoolDefinition;
import com.cobbletowers.mastery.LeaderboardRules.Board;
import com.cobbletowers.mastery.LeaderboardRules.Entry;
import com.cobbletowers.mastery.LeaderboardRules.Key;
import com.cobbletowers.mastery.LeaderboardRules.Member;
import com.cobbletowers.mastery.LeaderboardRules.Mode;
import com.cobbletowers.network.TrackStatePayload;
import com.cobbletowers.persistence.TowerLeaderboardStore;
import com.cobbletowers.persistence.TowerMasteryStore;
import com.cobbletowers.persistence.TowerSeasonProgressStore;
import com.cobbletowers.season.SeasonPoints;
import com.cobbletowers.season.Seasons;
import com.cobbletowers.track.TrackService;
import com.cobbletowers.trial.TrialSchedule;
import com.cobbletowers.trial.TrialService;
import com.mojang.authlib.GameProfile;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment;
import eu.pb4.polymer.virtualentity.api.elements.InteractionElement;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.TextDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.VirtualElement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Brightness;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The Records Hall's live parts (owner, 20 Nov 2026). Everyone sees the same leaderboard wall (today's Daily and this week's Weekly top
 * ten, and each tower's deepest Ascension) and the season's top three as statues on the rank dais. Each player inside sees only their
 * own season track (the tier marks they have passed lit in the floor ring, and the Season Chest on the dais's front step, whose lid opens as
 * they walk up and whose screen holds the track), their own trophies on every tower's case, and their own rank at the door. Display entities only, never saved; rebuilt from the stores every few seconds.
 */
final class RecordsHall {

    /** How often the hall re-reads the stores, in ticks. */
    private static final int EVERY = 100;
    private static final int BOARD_ROWS = 10;
    /** How long an earned trophy takes to turn a quarter, in ticks. */
    private static final int SPIN_TICKS = 40;
    /** The Season Chest: how near opens it (blocks), its lid's angles (degrees), how long the lid takes to swing, and the preview's bob. */
    private static final double NEAR = 4.0;
    private static final float OPEN_AT = 75, AJAR_AT = 10;
    private static final int LID_TICKS = 12, BOB_TICKS = 30;

    private static Wall wall;
    private static final Map<UUID, View> VIEWS = new HashMap<>();

    private RecordsHall() {}

    /** Re-reads a player's own parts of the hall now (after a claim at the Season Chest), if they are inside. */
    static void refreshFor(ServerPlayer player) {
        View view = VIEWS.get(player.getUUID());
        if (view != null) view.refresh(player.server, player);
    }

    static void clear() {
        if (wall != null) wall.destroy();
        wall = null;
        VIEWS.values().forEach(View::destroy);
        VIEWS.clear();
    }

    static void tick(MinecraftServer server, PartyService.Hub hub) {
        Optional<HubLayout.Records> found = hub.layout().records();
        if (found.isEmpty()) return;
        HubLayout.Records records = found.get();
        BlockPos at = hub.origin().offset((int) records.centre().x(), (int) records.centre().y(), (int) records.centre().z());
        if (!hub.level().hasChunk(at.getX() >> 4, at.getZ() >> 4)) return;
        Vec3 centre = new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        long now = server.getTickCount();
        if (wall == null || wall.getAttachment() == null) {
            if (wall != null) wall.destroy();
            wall = new Wall(hub.level(), centre, records);
        }
        if (now >= wall.due) {
            wall.due = now + EVERY;
            wall.refresh(server);
        }
        // one view per player inside; it goes when they leave
        for (ServerPlayer p : hub.level().players()) {
            boolean inside = records.inside(p.getX() - hub.origin().getX(), p.getY() - hub.origin().getY(), p.getZ() - hub.origin().getZ());
            View view = VIEWS.get(p.getUUID());
            if (inside && (view == null || view.getAttachment() == null)) {
                if (view != null) view.destroy();
                view = new View(hub.level(), centre, records, p.getUUID());
                VIEWS.put(p.getUUID(), view);
            }
            if (inside && now >= view.due) {
                view.due = now + EVERY;
                view.refresh(server, p);
            }
            if (inside) view.animate(p, now);
        }
        VIEWS.entrySet().removeIf(e -> {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            boolean gone = p == null || p.level() != hub.level() || !records.inside(p.getX() - hub.origin().getX(),
                    p.getY() - hub.origin().getY(), p.getZ() - hub.origin().getZ());
            if (gone) e.getValue().destroy();
            return gone;
        });
    }

    // ---- shared helpers --------------------------------------------------------------------------------------------------------------

    /** A point of the layout (structure coordinates) as an offset from the holder, which sits on the middle of the hall's centre block. */
    private static Vec3 local(HubLayout.Point p, HubLayout.Records r) {
        return new Vec3(p.x() - ((int) r.centre().x() + 0.5), p.y() - (int) r.centre().y(), p.z() - ((int) r.centre().z() + 0.5));
    }

    /** A point in a trophy case's own frame (blocks; +z out into the hall, origin the panel's face at floor level) as an offset from the holder. */
    private static Vec3 inCase(HubLayout.Shelf shelf, HubLayout.Records r, Vec3 p) {
        Vector3f v = new Quaternionf().rotateY((float) Math.toRadians(shelf.facing())).transform(new Vector3f((float) p.x, (float) p.y, (float) p.z));
        return local(shelf.at(), r).add(v.x, v.y, v.z);
    }

    /** The case's (and its trophies') light: steady, a little under full so the model's own face shading still reads. */
    private static final Brightness CASE_LIGHT = new Brightness(12, 12);
    private static final Brightness FULL = new Brightness(15, 15);

    /** A model part (a case chunk, a trophy, a medal) at {@code offset}, turned to face the hall. An item display draws its model half
     *  turned, so the turn is the case's facing plus 180. */
    private static ItemDisplayElement part(ElementHolder holder, ItemStack stack, Vec3 offset, float facing, boolean bright, float size) {
        ItemDisplayElement e = new ItemDisplayElement(stack);
        e.setModelTransformation(ItemDisplayContext.NONE);
        e.setBrightness(bright ? FULL : CASE_LIGHT);
        e.setViewRange(0.8f);
        e.setShadowRadius(0f);
        e.setDisplaySize(size, size);
        e.setOffset(offset);
        e.setLeftRotation(new Quaternionf().rotateY((float) Math.toRadians(facing + 180)));
        return holder.addElement(e);
    }

    private static TextDisplayElement text(ElementHolder holder, Vec3 offset, float scale, int width, boolean billboard) {
        TextDisplayElement t = new TextDisplayElement(Component.empty());
        t.setBillboardMode(billboard ? Display.BillboardConstraints.CENTER : Display.BillboardConstraints.FIXED);
        t.setTextAlignment(Display.TextDisplay.Align.CENTER);
        t.setLineWidth(width);
        t.setBackground(billboard ? 0xB0080710 : 0);
        t.setShadow(true);
        t.setBrightness(new Brightness(15, 15));
        t.setViewRange(1.0f);
        t.setScale(new Vector3f(scale, scale, scale));
        t.setOffset(offset);
        return holder.addElement(t);
    }

    private static ItemDisplayElement item(ElementHolder holder, Vec3 offset, float scale) {
        ItemDisplayElement e = new ItemDisplayElement(ItemStack.EMPTY);
        e.setModelTransformation(ItemDisplayContext.FIXED);
        e.setBillboardMode(Display.BillboardConstraints.VERTICAL);
        e.setScale(new Vector3f(scale, scale, scale));
        e.setBrightness(new Brightness(15, 15));
        e.setViewRange(0.8f);
        e.setShadowRadius(0f);
        e.setOffset(offset);
        return holder.addElement(e);
    }

    private static String nameOf(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) return online.getGameProfile().getName();
        var cache = server.getProfileCache();
        return cache == null ? "Someone" : cache.get(id).map(GameProfile::getName).orElse("Someone");
    }

    private static String towerName(ResourceLocation id) {
        TowerDefinition tower = TowerDefinitionRegistry.content().towers().get(id);
        return tower == null ? id.getPath() : tower.displayName();
    }

    // ---- the wall and the statues (everyone) ---------------------------------------------------------------------------------------

    /** The leaderboard wall's three columns and the rank dais' three statues. */
    private static final class Wall extends ElementHolder {

        private final TextDisplayElement[] heads = new TextDisplayElement[3], bodies = new TextDisplayElement[3];
        private final ItemDisplayElement[] busts = new ItemDisplayElement[3];
        private final TextDisplayElement[] plates = new TextDisplayElement[3];
        private final UUID[] shownBust = new UUID[3];
        private final List<ResourceLocation> towers;
        /** The tick of the next re-read; the hub ticks every few ticks, so a due time, not a remainder. */
        long due;
        private String shown = "";

        Wall(ServerLevel level, Vec3 centre, HubLayout.Records r) {
            towers = r.shelves().stream().map(HubLayout.Shelf::tower).toList();
            Quaternionf face = new Quaternionf().rotateY((float) Math.toRadians(r.boardFacing()));
            for (int i = 0; i < 3 && i < r.board().size(); i++) {
                Vec3 top = local(r.board().get(i), r);
                heads[i] = text(this, top.add(0, -0.4, 0), 1.6f, 120, false);
                heads[i].setLeftRotation(face);
                bodies[i] = text(this, top.add(0, -6.2, 0), 1.25f, 190, false);
                bodies[i].setLeftRotation(face);
            }
            for (int i = 0; i < 3 && i < r.statues().size(); i++) {
                Vec3 top = local(r.statues().get(i), r);
                busts[i] = item(this, top.add(0, 0.75, 0), 1.7f);
                plates[i] = text(this, top.add(0, 2.05, 0), 0.8f, 200, true);
            }
            // each tower's trophy case, in its own stone (the trophies on it are each viewer's own)
            TrophyModels.Layout cases = TrophyModels.layout();
            for (HubLayout.Shelf shelf : r.shelves()) {
                String tower = shelf.tower().getPath();
                for (AltarModels.Chunk c : cases.cases().getOrDefault(tower, cases.cases().get("neutral"))) {
                    part(this, TrophyModels.chunk(c.model()), inCase(shelf, r, c.at()), shelf.facing(), c.glow(), 3.5f);
                }
            }
            ChunkAttachment.ofTicking(this, level, centre);
        }

        void refresh(MinecraftServer server) {
            List<Component> h = new ArrayList<>(), b = new ArrayList<>();
            column(server, TrialPoolDefinition.Kind.DAILY, h, b);
            ascension(server, towers, h, b);
            column(server, TrialPoolDefinition.Kind.WEEKLY, h, b);
            String key = h.toString() + b;
            if (!key.equals(shown)) {
                shown = key;
                for (int i = 0; i < 3; i++) {
                    if (heads[i] == null) continue;
                    heads[i].setText(h.get(i));
                    bodies[i].setText(b.get(i));
                }
            }
            statues(server);
        }

        private static void column(MinecraftServer server, TrialPoolDefinition.Kind kind, List<Component> h, List<Component> b) {
            boolean daily = kind == TrialPoolDefinition.Kind.DAILY;
            int colour = daily ? 0xF2C744 : 0xC58CFF;
            Optional<TrialSchedule.Instance> trial = TrialService.current(kind);
            h.add(Component.literal(daily ? "DAILY TRIAL" : "WEEKLY TRIAL").withColor(colour).withStyle(ChatFormatting.BOLD));
            if (trial.isEmpty()) {
                b.add(Component.literal("No trial running").withColor(0x8A8F9C));
                return;
            }
            TowerLeaderboardStore store = TowerLeaderboardStore.get(server);
            List<Entry> rows = new ArrayList<>();
            for (Mode mode : List.of(Mode.SOLO, Mode.TEAM)) rows.addAll(store.top(new Key(Board.TRIAL, trial.get().boardId(), mode), BOARD_ROWS));
            rows.sort(Comparator.comparingLong(Entry::value).reversed().thenComparingLong(Entry::at));
            MutableComponent body = Component.literal(trial.get().title()).withColor(0xDCE0EC);
            if (rows.isEmpty()) body.append(Component.literal("\n\nNo scores yet.\nBe the first.").withColor(0x8A8F9C));
            for (int i = 0; i < Math.min(BOARD_ROWS, rows.size()); i++) {
                Entry e = rows.get(i);
                body.append(Component.literal("\n" + (i + 1) + ".  ").withColor(i == 0 ? colour : 0xA0A6B4));
                body.append(Component.literal(names(e.players())).withColor(0xFFFFFF));
                body.append(Component.literal("  " + e.value()).withColor(0x96E6A0));
            }
            b.add(body);
        }

        private static void ascension(MinecraftServer server, List<ResourceLocation> towers, List<Component> h, List<Component> b) {
            h.add(Component.literal("DEEPEST ASCENSION").withColor(0x6FD3E8).withStyle(ChatFormatting.BOLD));
            TowerLeaderboardStore store = TowerLeaderboardStore.get(server);
            MutableComponent body = Component.literal("Best climb in each tower").withColor(0xDCE0EC);
            for (ResourceLocation tower : towers) {
                Optional<Entry> best = List.of(Mode.SOLO, Mode.TEAM).stream()
                        .flatMap(m -> store.top(new Key(Board.ASCENSION, tower, m), 1).stream())
                        .max(Comparator.comparingLong(Entry::value).thenComparing(Comparator.comparingLong(Entry::at).reversed()));
                body.append(Component.literal("\n\n" + towerName(tower)).withColor(0xF2C744));
                body.append(best.isEmpty() ? Component.literal("\nnot climbed yet").withColor(0x8A8F9C)
                        : Component.literal("\nAscension " + best.get().value() + "  ").withColor(0x96E6A0)
                                .append(Component.literal(names(best.get().players())).withColor(0xFFFFFF)));
            }
            b.add(body);
        }

        private static String names(List<Member> players) {
            String all = players.stream().map(Member::name).collect(Collectors.joining(", "));
            return all.length() > 26 ? all.substring(0, 25) + "…" : all;
        }

        /** The season's top three: a bust of each and their points; an empty plinth says what it waits for. */
        private void statues(MinecraftServer server) {
            Optional<Integer> season = Seasons.viewNumber();
            List<Map.Entry<UUID, SeasonPoints.Progress>> top = season.isEmpty() ? List.of()
                    : TowerSeasonProgressStore.get(server).top(season.get(), 3);
            int[] colours = {0xF2C744, 0xDCE0EC, 0xE0A070};
            for (int i = 0; i < 3; i++) {
                if (busts[i] == null) continue;
                if (i >= top.size()) {
                    shownBust[i] = null;
                    busts[i].setItem(ItemStack.EMPTY);
                    plates[i].setText(Component.literal("#" + (i + 1) + "\n").withColor(colours[i])
                            .append(Component.literal(season.isEmpty() ? "No season running" : "Unclaimed this season").withColor(0x8A8F9C)));
                    continue;
                }
                UUID id = top.get(i).getKey();
                plates[i].setText(Component.literal("#" + (i + 1) + "  ").withColor(colours[i]).withStyle(ChatFormatting.BOLD)
                        .append(Component.literal(nameOf(server, id)).withColor(0xFFFFFF))
                        .append(Component.literal("\n" + top.get(i).getValue().total() + " season points").withColor(0xA0A6B4)));
                if (!id.equals(shownBust[i])) {
                    shownBust[i] = id;
                    bust(server, busts[i], id);
                }
            }
        }

        /** A player head with that player's skin; an online player's profile has it already, others are looked up off the thread. */
        private static void bust(MinecraftServer server, ItemDisplayElement element, UUID id) {
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            GameProfile profile = online != null ? online.getGameProfile() : new GameProfile(id, nameOf(server, id));
            element.setItem(head(new ResolvableProfile(profile)));
            if (online == null) {
                new ResolvableProfile(profile).resolve().thenAcceptAsync(resolved -> element.setItem(head(resolved)), server);
            }
        }

        private static ItemStack head(ResolvableProfile profile) {
            ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
            stack.set(DataComponents.PROFILE, profile);
            return stack;
        }
    }

    // ---- one player's own records ------------------------------------------------------------------------------------------------

    /** The track markers, trophies and rank plate one player sees; nobody else is sent them. */
    private static final class View extends ElementHolder {

        private final UUID owner;
        long due;
        private final List<ItemDisplayElement> inlays = new ArrayList<>();
        /** The Season Chest: its lid (turned about its hinge), the light inside, the next reward floating over it, and where it is. */
        private final ItemDisplayElement chestLid, chestGlow, chestPreview;
        private final Vec3 chestAt;
        private final float chestFacing;
        private ItemStack preview = ItemStack.EMPTY;
        private int ready;
        /** The lid's angle as last sent (degrees), when the motes and sparkles next rise, and the preview's bob. */
        private float lid = -1;
        private long moteDue, bobDue;
        private boolean bobUp;
        private final Map<ResourceLocation, Case> cases = new HashMap<>();
        private final TextDisplayElement rank;
        private final HubLayout.Records records;
        /** The spin's next step, its angle, and what the viewer was last told they are looking at (and when to say it again). */
        private long spinDue, hintDue;
        private float spin;
        private Target gazed;

        /** One tower's case as this viewer sees it: a ladder trophy (and its glow part and light pool) per family, a medal per one-off feat. */
        private static final class Case {
            final HubLayout.Shelf shelf;
            final Map<String, ItemDisplayElement> ladders = new HashMap<>(), ladderGlow = new HashMap<>(), pools = new HashMap<>();
            final Map<String, ItemDisplayElement> medals = new HashMap<>();
            final Map<String, String> tier = new HashMap<>();
            final List<Target> targets = new ArrayList<>();
            TextDisplayElement plate;

            Case(HubLayout.Shelf shelf) {
                this.shelf = shelf;
            }
        }

        /** Something on a case the viewer can look at: where its middle is (world), how near the gaze must pass, and what it is. */
        private record Target(Vec3 at, double radius, Case c, String feat) {}

        View(ServerLevel level, Vec3 centre, HubLayout.Records r, UUID owner) {
            this.owner = owner;
            this.records = r;
            for (HubLayout.Point p : r.track()) {                                      // lit per viewer; laid flat, a hair over the floor
                inlays.add(part(this, ItemStack.EMPTY, local(p, r).add(0, 0.01, 0), 0, true, 1.0f));
            }
            chestFacing = r.chestFacing();
            Vec3 base = local(r.chest(), r);
            chestAt = centre.add(base);
            part(this, SeasonChestModels.part("body"), base, chestFacing, false, 2.2f);
            Vector3f hinge = new Quaternionf().rotateY((float) Math.toRadians(chestFacing))
                    .transform(new Vector3f(0, (float) SeasonChestModels.HINGE_Y, (float) SeasonChestModels.HINGE_Z));
            chestLid = part(this, SeasonChestModels.part("lid"), base.add(hinge.x, hinge.y, hinge.z), chestFacing, false, 2.2f);
            chestLid.setInterpolationDuration(LID_TICKS);
            chestGlow = part(this, ItemStack.EMPTY, base, chestFacing, true, 2.2f);
            chestPreview = item(this, base.add(0, 1.35, 0), 0.5f);
            chestPreview.setInterpolationDuration(BOB_TICKS);
            InteractionElement hit = new InteractionElement(new VirtualElement.InteractionHandler() {
                @Override
                public void interact(ServerPlayer player, InteractionHand hand) {
                    if (hand == InteractionHand.MAIN_HAND && player.getUUID().equals(owner)) SeasonChest.open(player);   // both hands fire for one click
                }
            });
            hit.setSize(2.1f, 1.5f);
            hit.setOffset(base.add(0, -0.05, 0));
            addElement(hit);
            TrophyModels.Layout at = TrophyModels.layout();
            for (HubLayout.Shelf shelf : r.shelves()) {
                Case c = new Case(shelf);
                for (String family : TrophyModels.FAMILIES) {
                    Vec3 foot = at.ladders().get(family);
                    Vec3 o = inCase(shelf, r, foot.add(0, at.footUp(), 0));
                    ItemDisplayElement e = part(this, ItemStack.EMPTY, o, shelf.facing(), false, 2.2f);
                    ItemDisplayElement g = part(this, ItemStack.EMPTY, o, shelf.facing(), true, 2.2f);
                    for (ItemDisplayElement x : new ItemDisplayElement[] {e, g}) x.setInterpolationDuration(SPIN_TICKS + 2);
                    c.ladders.put(family, e);
                    c.ladderGlow.put(family, g);
                    c.pools.put(family, part(this, ItemStack.EMPTY, inCase(shelf, r, foot.add(0, 0.006, 0)), shelf.facing(), true, 1.2f));
                    c.targets.add(new Target(centre.add(inCase(shelf, r, foot.add(0, 0.85, 0))), 0.55, c, family));
                }
                for (String medal : TrophyModels.MEDALS) {
                    Vec3 m = at.medals().get(medal);
                    c.medals.put(medal, part(this, ItemStack.EMPTY, inCase(shelf, r, m), shelf.facing(), false, 1.0f));
                    c.targets.add(new Target(centre.add(inCase(shelf, r, m)), 0.3, c, medal));
                }
                c.plate = text(this, local(shelf.plate(), r), 0.7f, 240, false);
                c.plate.setLeftRotation(new Quaternionf().rotateY((float) Math.toRadians(shelf.facing())));
                c.plate.setBackground(0xB0080710);
                cases.put(shelf.tower(), c);
            }
            rank = text(this, local(r.rank(), r).add(0, 2.6, 0), 0.55f, 220, true);       // over the door, above the eye line
            ChunkAttachment.ofTicking(this, level, centre);
        }

        @Override
        public boolean startWatching(ServerGamePacketListenerImpl handler) {
            return handler.getPlayer().getUUID().equals(owner) && super.startWatching(handler);
        }

        void refresh(MinecraftServer server, ServerPlayer player) {
            track(server, player);
            shelves(server, player);
            rank(server, player);
        }

        /** The floor marks the viewer has passed, lit (the sixteen share the whole track out), and what the chest shows: the first reward
         *  still to claim (a ready one, else the next one ahead), and how many are ready. */
        private void track(MinecraftServer server, ServerPlayer player) {
            TrackStatePayload.Lane lane = TrackService.build(server, player, "", "").season();
            List<TrackStatePayload.Node> nodes = lane.present() ? lane.nodes() : List.of();
            for (int k = 0; k < inlays.size(); k++) {
                boolean lit = SeasonChestLayout.lit(k, inlays.size(), lane.current(), nodes.size());
                inlays.get(k).setItem(lit ? SeasonChestModels.part("inlay") : ItemStack.EMPTY);
            }
            ready = (int) nodes.stream().filter(n -> n.state() == TrackStatePayload.CLAIMABLE).count();
            TrackStatePayload.Node show = nodes.stream().filter(n -> n.state() == TrackStatePayload.CLAIMABLE).findFirst()
                    .orElse(nodes.stream().filter(n -> n.state() == TrackStatePayload.LOCKED).findFirst().orElse(null));
            preview = show == null ? ItemStack.EMPTY : SeasonChest.icon(show);
            if (lid == OPEN_AT) chestPreview.setItem(preview);
        }

        /** The lid: wide open while the viewer is near; ajar with a glint at its seam when something is ready to claim; else shut. */
        private void chest(ServerPlayer player, long now) {
            boolean near = player.position().distanceToSqr(chestAt) < NEAR * NEAR;
            float want = near ? OPEN_AT : ready > 0 ? AJAR_AT : 0;
            if (want != lid) {
                if (lid >= 0 && (want == OPEN_AT) != (lid == OPEN_AT)) {
                    player.playNotifySound(want == OPEN_AT ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5f, 1.0f);
                }
                lid = want;
                // drawn half turned like every part, so the turn about x is in the model's own frame: a positive angle lifts the front
                chestLid.setLeftRotation(new Quaternionf().rotateY((float) Math.toRadians(chestFacing + 180)).rotateX((float) Math.toRadians(want)));
                chestLid.startInterpolation();
                chestGlow.setItem(want == OPEN_AT ? SeasonChestModels.part("glow") : ItemStack.EMPTY);
                chestPreview.setItem(want == OPEN_AT ? preview : ItemStack.EMPTY);
            }
            if (now >= bobDue && lid == OPEN_AT) {
                bobDue = now + BOB_TICKS;
                bobUp = !bobUp;
                chestPreview.setTranslation(new Vector3f(0, bobUp ? 0.12f : 0, 0));
                chestPreview.startInterpolation();
            }
            if (now >= moteDue && lid > 0) {
                moteDue = now + (lid == OPEN_AT ? 6 : 24);
                ServerLevel level = player.serverLevel();
                if (lid == OPEN_AT) {                                                         // motes rising out of the light inside
                    level.sendParticles(player, ParticleTypes.END_ROD, false, chestAt.x, chestAt.y + 0.75, chestAt.z, 1, 0.45, 0.05, 0.2, 0.015);
                } else {                                                                      // a glint at the seam: something to claim
                    Vector3f front = new Quaternionf().rotateY((float) Math.toRadians(chestFacing)).transform(new Vector3f(0, 0, 0.55f));
                    level.sendParticles(player, ParticleTypes.WAX_ON, false, chestAt.x + front.x, chestAt.y + 0.85, chestAt.z + front.z, 2, 0.4, 0.05, 0.05, 0);
                }
            }
        }

        /** Each tower's case: a ladder trophy per family at the rung the player has reached there, a medal per one-off feat (dark until
         *  earned), and the tower's level and trophy count on its plate. */
        private void shelves(MinecraftServer server, ServerPlayer player) {
            TowerMasteryStore store = TowerMasteryStore.get(server);
            int total = AchievementRegistry.all().size();
            for (Case c : cases.values()) {
                String tower = c.shelf.tower().getPath();
                TowerMasteryStore.Progress progress = store.progressOf(player.getUUID(), c.shelf.tower());
                Set<String> held = progress.unlocked().keySet().stream().map(ResourceLocation::getPath).collect(Collectors.toSet());
                for (String family : TrophyModels.FAMILIES) {
                    String tier = Trophies.tier(family, held);
                    if (tier.equals(c.tier.get(family))) continue;
                    c.tier.put(family, tier);
                    c.ladders.get(family).setItem(TrophyModels.trophy(tower, family, tier));
                    c.ladderGlow.get(family).setItem(TrophyModels.trophyGlow(tower, family, tier));
                    boolean shines = tier.equals("gold") || tier.equals("ascended");
                    c.pools.get(family).setItem(shines ? TrophyModels.common("pool") : ItemStack.EMPTY);
                }
                for (int i = 0; i < TrophyModels.MEDALS.size(); i++) {
                    String medal = TrophyModels.MEDALS.get(i);
                    c.medals.get(medal).setItem(TrophyModels.common(held.contains(medal) ? (i % 2 == 0 ? "medal_a" : "medal_b") : "medal_dark"));
                }
                c.plate.setText(Component.literal(towerName(c.shelf.tower()).toUpperCase(java.util.Locale.ROOT))
                        .withColor(0xF2C744).withStyle(ChatFormatting.BOLD)
                        .append(Component.literal("\nLevel " + progress.level() + "  \u00b7  " + held.size() + " of " + total + " trophies")
                                .withColor(0xDCE0EC)));
            }
        }

        /** Every couple of seconds the earned trophies turn a quarter more; and whatever the viewer looks at is named on their action bar. */
        void animate(ServerPlayer player, long now) {
            chest(player, now);
            if (now >= spinDue) {
                spinDue = now + SPIN_TICKS;
                spin = (spin + 90) % 360;
                for (Case c : cases.values()) {
                    Quaternionf turn = new Quaternionf().rotateY((float) Math.toRadians(c.shelf.facing() + 180 + spin));
                    for (String family : TrophyModels.FAMILIES) {
                        String tier = c.tier.get(family);
                        if (tier == null || tier.equals("stone")) continue;
                        for (ItemDisplayElement e : new ItemDisplayElement[] {c.ladders.get(family), c.ladderGlow.get(family)}) {
                            e.setLeftRotation(turn);
                            e.startInterpolation();
                        }
                    }
                }
            }
            Target seen = gaze(player);
            if (seen == null) {
                if (gazed != null) player.displayClientMessage(Component.empty(), true);
                gazed = null;
                return;
            }
            if (seen != gazed || now >= hintDue) {
                gazed = seen;
                hintDue = now + 40;
                Set<String> held = TowerMasteryStore.get(player.server).progressOf(player.getUUID(), seen.c().shelf.tower()).unlocked().keySet()
                        .stream().map(ResourceLocation::getPath).collect(Collectors.toSet());
                player.displayClientMessage(Trophies.hint(seen.feat(), held), true);
            }
        }

        /** The trophy or medal nearest along the viewer's line of sight, within eight blocks, or null. */
        private Target gaze(ServerPlayer player) {
            Vec3 eye = player.getEyePosition();
            Vec3 look = player.getLookAngle();
            Target best = null;
            double bestAlong = 8;
            for (Case c : cases.values()) {
                for (Target t : c.targets) {
                    Vec3 to = t.at().subtract(eye);
                    double along = to.dot(look);
                    if (along <= 0 || along >= bestAlong) continue;
                    if (to.subtract(look.scale(along)).lengthSqr() > t.radius() * t.radius()) continue;
                    best = t;
                    bestAlong = along;
                }
            }
            return best;
        }

        private void rank(MinecraftServer server, ServerPlayer player) {
            Optional<Integer> season = Seasons.viewNumber();
            if (season.isEmpty()) {
                rank.setText(Component.literal("No season running").withColor(0x8A8F9C));
                return;
            }
            TowerSeasonProgressStore store = TowerSeasonProgressStore.get(server);
            SeasonPoints.Progress mine = store.of(player.getUUID(), season.get());
            int place = store.rankOf(player.getUUID(), season.get());
            rank.setText(Component.literal("SEASON " + season.get()).withColor(0xF2C744).withStyle(ChatFormatting.BOLD)
                    .append(Component.literal(place == 0 ? "\nYou have no points yet" : "\nYou are #" + place + " of " + store.playersIn(season.get()))
                            .withColor(0xFFFFFF))
                    .append(Component.literal("\n" + mine.total() + " points  ·  step " + mine.steps()).withColor(0xA0A6B4)));
        }
    }
}
