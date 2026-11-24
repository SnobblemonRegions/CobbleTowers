package com.cobbletowers.hub;

import com.cobbletowers.definition.RentalSetDefinition;
import com.cobbletowers.definition.RentalSetDefinition.Rarity;
import com.cobbletowers.rental.RentalDraft;
import com.cobbletowers.rental.RentalDraw;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment;
import eu.pb4.polymer.virtualentity.api.elements.InteractionElement;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.TextDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.VirtualElement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Brightness;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * One player's Rental Draft at a Tower Hub gate (owner, 23 Nov 2026, concept validation/concepts/rental_draft_gate.png): drawn over the gate's
 * rules board for that player alone. A sealed pack hangs in the air over the path; right-clicked, its strip tears off and five cards arc out to
 * their places on the board and turn over (rare and better ring, flash and, for a legendary or mythic, stamp). The player right-clicks two to
 * keep (the card they look at tips toward them, its set on the action bar); the two lock in, their Pokemon hop up onto the board's ledge, and
 * the next pack comes. Six kept is a finished team: the leader's walk-in starts the run. The draft itself is the lobby's own
 * {@link RentalDraft}, so its rules (two per pack, at most two legendary or mythic) are checked there.
 *
 * <p>Places are in the altar's frame, in blocks from its floor centre (the rules board is at +x, facing the path at -x; seen from the path,
 * +z is the right). An item display draws its model half turned, so a model facing its own +z is turned 90 to face -x here.
 */
final class GateDraft extends ElementHolder {

    enum Stage { SEALED, OPENING, CHOOSE, LOCKING, DONE }

    // ---- the frame ----
    // the layers stand well apart (owner, 24 Nov 2026: flicker): the shared rules text is at 7.31, the cover 0.07 in front of it, the
    // cards 0.14 in front of that, the names 0.06 in front of the cards
    static final double CARD_X = 7.10, COVER_X = 7.24, TEXT_X = 7.04;
    static final double CARD_Y = 1.56, HEADER_Y = 2.37, BODY_Y = 1.62;
    // the kept Pokemon stand on the front of the board's top (owner, 24 Nov 2026: they stood behind it, facing away), three each side of the
    // RULES sign in the middle
    static final double LEDGE_X = 7.3, LEDGE_Y = 3.25;
    private static final float LEDGE_SCALE = 1.4f;
    private static final double[] LEDGE_Z = {-2.95, -2.1, -1.25, 1.25, 2.1, 2.95};
    /** Cobblemon's model item faces the other way from the flat models FACE_UP was set for: turned round, and a little toward the middle. */
    private static float ledgeSpin(int k) {
        return FACE_UP + 180 + (k < 3 ? -12 : 12);
    }
    /** Cobblemon's model item is drawn about its middle: raised so the Pokemon stands on the ledge, not sunk into it. */
    private static final double LEDGE_UP = 0.8;
    static final Vec3 PACK_AT = new Vec3(5.5, 4.15, 0);                    // over the path, clear above the board's ledge (3.25)
    static final float FACE_UP = 90, FACE_DOWN = 270, TEXT_FACE = -90;
    private static final float CULL = 20f;
    private static final Brightness LIT = new Brightness(15, 15);
    private static final int GOLD = 0xF2C744, INK = 0xE8EAF2, MUTED = 0xA0A6B4, GREEN = 0x96E6A0;
    private static final int[] TIER_COLOUR = {0xCDD3DD, 0x7BD35A, 0x3FA9F5, 0xB57BEA, 0xF2C744, 0xFF7AC8};

    static double cardZ(int i) {
        return -2.6 + 1.3 * i;
    }

    static double teamZ(int i) {
        return -2.65 + 1.06 * i;
    }

    private final UUID owner;
    private final ServerLevel level;
    private final Vec3 base;
    private final Quaternionf turn;
    final RentalDraft draft;
    private final GateDrafts.Callbacks on;

    private final ItemDisplayElement cover, pack, packTop, packBody, rays;
    private final TextDisplayElement header, body, restart;
    /** The cards: text displays drawn in the card font (DraftModels), so they light themselves; an item model would be shaded. */
    private final TextDisplayElement[] cards = new TextDisplayElement[6];
    /** Each card's sprite and gems, a little in front of the card (never on its plane, or the two flicker). */
    private final TextDisplayElement[] fronts = new TextDisplayElement[6];
    private static final double FRONT = 0.035;
    /** Each card's stats, written in front of its back when it is turned over (Inspect), at this share of the card's scale. */
    private final TextDisplayElement[] infos = new TextDisplayElement[6];
    private static final float INFO_SCALE = 0.25f;
    /** The INSPECT strip on a card's face: from this share of its height down from the top, to the bottom. */
    private static final double STRIP_TOP = 0.74;
    private final ItemDisplayElement[] kept = new ItemDisplayElement[6], checks = new ItemDisplayElement[6],
            rings = new ItemDisplayElement[5], flashes = new ItemDisplayElement[5], stamps = new ItemDisplayElement[5],
            ledge = new ItemDisplayElement[6];
    private final TextDisplayElement[] names = new TextDisplayElement[6];

    private Stage stage = Stage.SEALED;
    private int age, stageAge;
    private final boolean[] chosen = new boolean[5];
    private int lookedAt = -1;
    // Inspect (owner, 24 Nov 2026): resting the crosshair on a card's INSPECT strip turns it over to its stats; looking away, at another card,
    // or clicking turns it back. One card at a time. A turn is the reveal's: edge-on, the side swapped, round again.
    private int inspecting = -1, dwell;
    /** The card just clicked: it is not turned over again until the crosshair has left its strip. */
    private int clickedCard = -1;
    private final boolean[] wantBack = new boolean[6], backShown = new boolean[6];
    private final int[] flipT = new int[6];
    private long hintDue;
    private int ledgeShown;

    GateDraft(ServerLevel level, Vec3 base, float yawDegrees, UUID owner, RentalDraft draft, GateDrafts.Callbacks on) {
        this.level = level;
        this.base = base;
        this.turn = new Quaternionf().rotateY((float) Math.toRadians(yawDegrees));
        this.owner = owner;
        this.draft = draft;
        this.on = on;
        cover = item(DraftModels.fx("cover"), false);
        put(cover, new Vec3(COVER_X, 1.6875, 0), FACE_UP, 7);                   // hides the shared rules text from this player only (a 16 x 4 model, x7)
        rays = item(DraftModels.fx("rays"), true);
        pack = item(DraftModels.fx("pack"), true);
        packTop = item(ItemStack.EMPTY, true);
        packBody = item(ItemStack.EMPTY, true);
        for (ItemDisplayElement e : new ItemDisplayElement[] {rays, pack, packTop, packBody}) e.setInterpolationDuration(10);
        header = text(new Vec3(TEXT_X, HEADER_Y, 0), 0.55f, 400);
        body = text(new Vec3(TEXT_X, BODY_Y, 0), 0.6f, 300);
        restart = text(new Vec3(TEXT_X, 0.93, 2.85), 0.4f, 200);
        restart.setBackground(0xC0080710);
        for (int i = 0; i < 6; i++) {
            kept[i] = item(ItemStack.EMPTY, true);
            cards[i] = cardText();
            fronts[i] = cardText();
            infos[i] = cardText();
            infos[i].setLineWidth(134);
            infos[i].setShadow(true);
            checks[i] = item(ItemStack.EMPTY, true);
            names[i] = text(new Vec3(TEXT_X, CARD_Y, cardZ(Math.min(i, 4))), 0.32f, 120);
            ledge[i] = new ItemDisplayElement(ItemStack.EMPTY);
            ledge[i].setModelTransformation(ItemDisplayContext.FIXED);
            ledge[i].setDisplaySize(CULL, 6);
            ledge[i].setViewRange(0.8f);
            ledge[i].setShadowRadius(0);
            ledge[i].setInterpolationDuration(10);
            ledge[i].setBrightness(LIT);
            addElement(ledge[i]);
            for (ItemDisplayElement e : new ItemDisplayElement[] {kept[i], checks[i]}) e.setInterpolationDuration(6);
            cards[i].setInterpolationDuration(6);
            fronts[i].setInterpolationDuration(6);
            infos[i].setInterpolationDuration(6);
        }
        for (int i = 0; i < 5; i++) {
            rings[i] = item(ItemStack.EMPTY, true);
            flashes[i] = item(ItemStack.EMPTY, true);
            stamps[i] = item(ItemStack.EMPTY, true);
            rings[i].setInterpolationDuration(10);
            stamps[i].setInterpolationDuration(4);
        }
        InteractionElement hit = new InteractionElement(new VirtualElement.InteractionHandler() {
            @Override
            public void interact(ServerPlayer player, InteractionHand hand) {
                if (hand == InteractionHand.MAIN_HAND && player.getUUID().equals(owner) && stage == Stage.SEALED) openPack(player);
            }
        });
        hit.setSize(1.05f, 1.45f);                                             // the pack's own size
        hit.setOffset(world(PACK_AT.add(0, -0.72, 0)));
        addElement(hit);
        for (int k = 0; k < draft.team().size(); k++) showLedge(k, false);
        if (draft.complete()) enter(Stage.DONE);
        else enter(Stage.SEALED);
        ChunkAttachment.ofTicking(this, level, base);
    }

    @Override
    public boolean startWatching(ServerGamePacketListenerImpl handler) {
        return handler.getPlayer().getUUID().equals(owner) && super.startWatching(handler);
    }

    // ---- building blocks ---------------------------------------------------------------------------------------------------------

    private ItemDisplayElement item(ItemStack stack, boolean bright) {
        ItemDisplayElement e = new ItemDisplayElement(stack);
        e.setModelTransformation(ItemDisplayContext.NONE);
        e.setBrightness(bright ? LIT : new Brightness(13, 13));
        e.setViewRange(0.8f);
        e.setShadowRadius(0);
        e.setDisplaySize(CULL, 6);
        return addElement(e);
    }

    private TextDisplayElement cardText() {
        TextDisplayElement t = new TextDisplayElement(Component.empty());
        t.setBillboardMode(Display.BillboardConstraints.FIXED);
        t.setTextAlignment(Display.TextDisplay.Align.CENTER);
        t.setLineWidth(400);
        t.setBackground(0);
        t.setShadow(false);
        t.setBrightness(LIT);
        t.setViewRange(0.8f);
        t.setDisplaySize(CULL, 6);
        return addElement(t);
    }

    /** A card at `local`, its face turned as an item display's would be by `spin` (text faces the other way, so 180 less). */
    private void putCard(int i, Vec3 local, float spin, float scale) {
        Quaternionf r = new Quaternionf(turn).rotateY((float) Math.toRadians(spin - 180));
        Vec3 w = world(local.add(0, -DraftModels.CARD_TEXT_MID * scale, 0));
        Vector3f toward = r.transform(new Vector3f(0, 0, (float) FRONT));                // the front layer: toward the viewer
        float info = scale * INFO_SCALE;                                                 // the stats: their block of lines (10 units each) centred on the card
        // the card picture's own middle is 0.1 block (x its scale) above `local`: its text lines are 10 units apart, not CARD_TEXT_MID's 9
        Vec3 iw = world(local.add(0, 0.1 * scale - DraftModels.STATS_LINES * 10 / 2.0 * info / 40.0, 0));
        infos[i].setTranslation(new Vector3f((float) iw.x, (float) iw.y, (float) iw.z).add(toward));
        infos[i].setLeftRotation(new Quaternionf(r));
        infos[i].setScale(new Vector3f(info, info, info));
        for (int k = 0; k < 2; k++) {
            TextDisplayElement e = k == 0 ? cards[i] : fronts[i];
            Vector3f at = new Vector3f((float) w.x, (float) w.y, (float) w.z);
            if (k == 1) at.add(toward);
            e.setTranslation(at);
            e.setLeftRotation(new Quaternionf(r));
            e.setScale(new Vector3f(scale, scale, scale));
        }
    }

    private void moveCard(int i, Vec3 local, float spin, float scale, int ticks) {
        for (TextDisplayElement e : new TextDisplayElement[] {cards[i], fronts[i], infos[i]}) e.setInterpolationDuration(ticks);
        putCard(i, local, spin, scale);
        for (TextDisplayElement e : new TextDisplayElement[] {cards[i], fronts[i], infos[i]}) e.startInterpolation();
    }

    /** A card put straight there, no glide. */
    private void snapCard(int i, Vec3 local, float spin, float scale) {
        moveCard(i, local, spin, scale, 0);
    }

    /** A card's face (both layers), back (the card alone), or nothing. */
    private void showFace(int i, RentalSetDefinition set, String tier) {
        cards[i].setText(DraftModels.cardBase(set, tier));
        fronts[i].setText(DraftModels.cardFront(set));
        infos[i].setText(Component.empty());
    }

    private void showBack(int i) {
        cards[i].setText(DraftModels.cardBack());
        fronts[i].setText(Component.empty());
        infos[i].setText(Component.empty());
    }

    /** The stats back: the set as the draft screen's hover card tells it. Its name label goes, as the back has the name. */
    private void showStats(int i) {
        cards[i].setText(DraftModels.cardStats(tierName(tierOf(i))));
        fronts[i].setText(Component.empty());
        infos[i].setText(DraftModels.statsText(setOf(i), spreadOf(i).evs()));
        names[i].setText(Component.empty());
    }

    private TextDisplayElement text(Vec3 local, float scale, int width) {
        TextDisplayElement t = new TextDisplayElement(Component.empty());
        t.setBillboardMode(Display.BillboardConstraints.FIXED);
        t.setTextAlignment(Display.TextDisplay.Align.CENTER);
        t.setLineWidth(width);
        t.setBackground(0);
        t.setShadow(true);
        t.setBrightness(LIT);
        t.setViewRange(0.8f);
        t.setDisplaySize(4, 2);
        t.setScale(new Vector3f(scale, scale, scale));
        t.setOffset(world(local));
        t.setLeftRotation(new Quaternionf(turn).rotateY((float) Math.toRadians(TEXT_FACE)));
        return addElement(t);
    }

    /** A place in the altar's frame, as an offset from the holder (on the altar's floor centre). */
    private Vec3 world(Vec3 local) {
        Vector3f p = turn.transform(new Vector3f((float) local.x, (float) local.y, (float) local.z));
        return new Vec3(p.x, p.y, p.z);
    }

    private void put(ItemDisplayElement e, Vec3 local, float spin, float scale) {
        Vec3 w = world(local);
        e.setTranslation(new Vector3f((float) w.x, (float) w.y, (float) w.z));
        e.setLeftRotation(new Quaternionf(turn).rotateY((float) Math.toRadians(spin)));
        e.setScale(new Vector3f(scale, scale, scale));
    }

    private void move(ItemDisplayElement e, Vec3 local, float spin, float scale, int ticks) {
        e.setInterpolationDuration(ticks);
        put(e, local, spin, scale);
        e.startInterpolation();
    }

    private void hide(ItemDisplayElement e) {
        e.setItem(ItemStack.EMPTY);
    }

    private ServerPlayer player() {
        return level.getServer().getPlayerList().getPlayer(owner);
    }

    private void sound(SoundEvent s, float volume, float pitch) {
        ServerPlayer p = player();
        if (p != null) p.playNotifySound(s, SoundSource.BLOCKS, volume, pitch);
    }

    private void particles(ParticleOptions type, Vec3 local, int count, double spread, double speed) {
        ServerPlayer p = player();
        if (p == null) return;
        Vec3 w = base.add(world(local));
        level.sendParticles(p, type, true, w.x, w.y, w.z, count, spread, spread, spread, speed);
    }

    // ---- what is on the board ----------------------------------------------------------------------------------------------------

    private RentalDraw.Pack currentPack() {
        return draft.offer().packs().get(Math.min(draft.currentPack(), RentalDraw.PACKS - 1));
    }

    private static String tierName(Rarity tier) {
        return tier.lower();
    }

    private static String speciesName(RentalSetDefinition set) {
        String s = set.species();
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private RentalSetDefinition setOf(int i) {
        return stage == Stage.DONE ? draft.team().get(i) : currentPack().cards().get(i);
    }

    private Rarity tierOf(int i) {
        return stage == Stage.DONE ? draft.teamTiers().get(i) : currentPack().tiers().get(i);
    }

    private com.cobbletowers.rental.RentalSpread spreadOf(int i) {
        return stage == Stage.DONE ? draft.teamSpreads().get(i) : currentPack().spreads().get(i);
    }

    private record Pose(Vec3 local, float spin, float scale) {}

    /** Where card i rests now: tipped toward the player while looked at, raised while kept, in the team row once done. */
    private Pose pose(int i) {
        if (stage == Stage.DONE) return new Pose(new Vec3(CARD_X, CARD_Y - 0.02, teamZ(i)), FACE_UP, 0.8f);
        if (i < 5 && chosen[i]) return new Pose(slot(i).add(-0.06, 0.09, 0), FACE_UP, 1.0f);
        if (stage == Stage.CHOOSE && i == lookedAt) return new Pose(slot(i).add(-0.05, 0.05, 0), FACE_UP - 10, 1.0f);
        return new Pose(slot(i), FACE_UP, 1.0f);
    }

    /** The name label moves and turns with its card (owner, 24 Nov 2026: a tipped card hid half the name). */
    private void placeName(int i, boolean glide) {
        Vec3 shift = Vec3.ZERO;
        float spin = FACE_UP;
        if (stage == Stage.CHOOSE && i < 5) {
            Pose p = pose(i);
            shift = p.local().subtract(slot(i));
            spin = p.spin();
        }
        Vec3 w = world(shift);
        names[i].setInterpolationDuration(glide ? 4 : 0);
        names[i].setTranslation(new Vector3f((float) w.x, (float) w.y, (float) w.z));
        names[i].setLeftRotation(new Quaternionf(turn).rotateY((float) Math.toRadians(spin - 180)));
        names[i].startInterpolation();
    }

    private Vec3 slot(int i) {
        return new Vec3(CARD_X, CARD_Y, cardZ(i));
    }

    private void enter(Stage next) {
        stage = next;
        stageAge = 0;
        if (next == Stage.SEALED || next == Stage.DONE) {
            java.util.Arrays.fill(wantBack, false);
            java.util.Arrays.fill(backShown, false);
            java.util.Arrays.fill(flipT, 0);
            inspecting = -1;
            dwell = 0;
            lookedAt = -1;
        }
        for (int i = 0; i < 5; i++) {                                      // no reveal effect outlives the reveal
            hide(rings[i]);
            hide(flashes[i]);
            ringGrow[i] = 0;
            if (next != Stage.CHOOSE) {                                    // a legendary's stamp stays while its pack is chosen from
                hide(stamps[i]);
                stampSlam[i] = 0;
            }
        }
        int packNo = Math.min(draft.currentPack(), RentalDraw.PACKS - 1) + 1;
        switch (next) {
            case SEALED -> {
                for (int i = 0; i < 6; i++) clearCard(i);
                java.util.Arrays.fill(chosen, false);
                header.setText(Component.literal("RENTAL DRAFT  ·  PACK " + packNo + " OF " + RentalDraw.PACKS).withColor(GOLD));
                body.setText(Component.literal("Right-click the pack to open it\n").withColor(INK)
                        .append(Component.literal("Keep two of five  ·  at most " + RentalDraft.MAX_TOP_RARITY + " legendary or mythic per team")
                                .withColor(MUTED)));
                restart.setText(Component.empty());
                pack.setItem(DraftModels.fx("pack"));
                rays.setItem(DraftModels.fx("rays"));
                put(pack, PACK_AT, FACE_UP - 6, 1.0f);
                put(rays, PACK_AT.add(0.05, 0, 0), FACE_UP, 1.0f);
                hide(packTop);
                hide(packBody);
            }
            case OPENING -> {
                body.setText(Component.empty());
                header.setText(Component.literal("PACK " + packNo + " OF " + RentalDraw.PACKS).withColor(GOLD));
            }
            case CHOOSE -> updateChooseHeader();
            case LOCKING -> { }
            case DONE -> {
                hide(pack);
                hide(rays);
                hide(packTop);
                hide(packBody);
                header.setText(Component.literal("YOUR RENTAL TEAM  ·  READY").withColor(GREEN));
                body.setText(Component.empty());
                restart.setText(Component.literal("↺ Restart draft").withColor(MUTED));
                List<RentalSetDefinition> team = draft.team();
                List<Rarity> tiers = draft.teamTiers();
                for (int i = 0; i < 6; i++) {
                    if (i >= team.size()) {
                        clearCard(i);
                        continue;
                    }
                    showFace(i, team.get(i), tierName(tiers.get(i)));
                    putCard(i, new Vec3(CARD_X, CARD_Y - 0.02, teamZ(i)), FACE_UP, 0.8f);
                    checks[i].setItem(DraftModels.fx("check"));
                    put(checks[i], new Vec3(CARD_X - 0.08, CARD_Y + 0.46, teamZ(i) + 0.3), FACE_UP, 0.8f);
                    hide(kept[i]);
                    names[i].setText(DraftModels.label(speciesName(team.get(i)), INK));
                    names[i].setOffset(world(new Vec3(TEXT_X, CARD_Y - 0.02 - DraftModels.NAME_BELOW * 0.8, teamZ(i))));
                    names[i].setScale(new Vector3f(0.27f, 0.27f, 0.27f));
                    placeName(i, false);
                }
                for (int k = 0; k < team.size(); k++) showLedge(k, false);
            }
        }
    }

    private void updateChooseHeader() {
        int packNo = draft.currentPack() + 1;
        int n = 0;
        for (boolean c : chosen) if (c) n++;
        header.setText(Component.literal("PACK " + packNo + " OF " + RentalDraw.PACKS + "  ·  ").withColor(GOLD)
                .append(Component.literal(n + " of " + RentalDraft.KEEP + " kept  ·  right-click a card to keep it").withColor(MUTED)));
    }

    private void clearCard(int i) {
        cards[i].setText(Component.empty());
        fronts[i].setText(Component.empty());
        infos[i].setText(Component.empty());
        placeName(i, false);
        hide(kept[i]);
        hide(checks[i]);
        names[i].setText(Component.empty());
        names[i].setScale(new Vector3f(0.32f, 0.32f, 0.32f));
        if (i < 5) {
            names[i].setOffset(world(new Vec3(TEXT_X, CARD_Y - DraftModels.NAME_BELOW, cardZ(i))));
            hide(rings[i]);
            hide(flashes[i]);
            hide(stamps[i]);
        }
    }

    /** The kept Pokemon in team slot k on the ledge; `hop` brings it up from its card (the slot it was kept from). */
    private void showLedge(int k, boolean hop) {
        List<RentalSetDefinition> team = draft.team();
        if (k >= team.size() || k >= 6) return;
        ItemStack model = DraftModels.pokemon(level.registryAccess(), team.get(k).species());
        ledge[k].setItem(model);
        Vec3 to = new Vec3(LEDGE_X, LEDGE_Y + LEDGE_UP, LEDGE_Z[k]);
        float spin = ledgeSpin(k);
        if (hop) {
            int from = draft.picks().get(draft.picks().size() - 1).get(k % 2);
            ledge[k].setInterpolationDuration(0);
            Vec3 w = world(new Vec3(CARD_X - 0.1, CARD_Y + 0.2, cardZ(from)));
            ledge[k].setTranslation(new Vector3f((float) w.x, (float) w.y, (float) w.z));
            ledge[k].setScale(new Vector3f(0.25f, 0.25f, 0.25f));
            ledge[k].setLeftRotation(new Quaternionf(turn).rotateY((float) Math.toRadians(spin)));
            ledge[k].startInterpolation();
            ledgeHop[k] = 2;                                          // the move itself is sent two ticks on, once the start has landed
        } else {
            Vec3 w = world(to);
            ledge[k].setInterpolationDuration(0);
            ledge[k].setTranslation(new Vector3f((float) w.x, (float) w.y, (float) w.z));
            ledge[k].setScale(new Vector3f(LEDGE_SCALE, LEDGE_SCALE, LEDGE_SCALE));
            ledge[k].setLeftRotation(new Quaternionf(turn).rotateY((float) Math.toRadians(spin)));
            ledge[k].startInterpolation();
        }
        ledgeShown = Math.max(ledgeShown, k + 1);
    }

    private final int[] ledgeHop = new int[6];

    // ---- the player's hands ------------------------------------------------------------------------------------------------------

    void openPack(ServerPlayer player) {
        if (stage != Stage.SEALED) return;
        enter(Stage.OPENING);
        sound(SoundEvents.BOOK_PAGE_TURN, 0.9f, 0.6f);
        sound(SoundEvents.BUNDLE_DROP_CONTENTS, 0.8f, 1.2f);
        hide(pack);
        packBody.setItem(DraftModels.fx("pack_body"));
        put(packBody, PACK_AT.add(0, -0.07, 0), FACE_UP - 6, 1.0f);
        packTop.setItem(DraftModels.fx("pack_top"));
        packTop.setInterpolationDuration(0);
        put(packTop, PACK_AT.add(0, 0.66, 0), FACE_UP - 6, 1.0f);
        packTop.startInterpolation();
        particles(ParticleTypes.WAX_ON, PACK_AT.add(0, 0.7, 0), 8, 0.3, 0.05);
    }

    /** A right-click on the rules board's collision at `hit` (world). */
    void click(ServerPlayer player, Vec3 hit) {
        Vector3f l = new Quaternionf(turn).conjugate().transform(new Vector3f((float) (hit.x - base.x), (float) (hit.y - base.y), (float) (hit.z - base.z)));
        if (stage == Stage.SEALED) {
            openPack(player);
            return;
        }
        if (inspecting >= 0) {                                             // a click turns the card back first
            clickedCard = inspecting;
            wantBack[inspecting] = false;
            inspecting = -1;
            dwell = 0;
        }
        if (stage == Stage.DONE) {
            if (l.z > 2.1 && l.y < 1.15) restart(player);
            return;
        }
        if (stage != Stage.CHOOSE) return;
        int best = -1;
        for (int i = 0; i < 5; i++) {
            if (Math.abs(l.z - cardZ(i)) < 0.6 && Math.abs(l.y - CARD_Y) < 0.8) best = i;
        }
        if (best >= 0) choose(player, best);
    }

    private void choose(ServerPlayer player, int i) {
        RentalDraw.Pack p = currentPack();
        if (chosen[i]) {                                                   // a second click on a kept card lets it go again
            chosen[i] = false;
            hide(kept[i]);
            hide(checks[i]);
            Pose back = pose(i);
            if (flipT[i] == 0) moveCard(i, back.local(), back.spin(), back.scale(), 4);
            placeName(i, true);
            sound(SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 0.8f);
            updateChooseHeader();
            return;
        }
        chosen[i] = true;
        Vec3 up = slot(i).add(-0.06, 0.09, 0);
        if (flipT[i] == 0) moveCard(i, up, FACE_UP, 1.0f, 4);
        placeName(i, true);
        kept[i].setItem(DraftModels.fx("kept"));
        put(kept[i], up.add(0.07, 0, 0), FACE_UP, 1.0f);                     // well behind the card
        checks[i].setItem(DraftModels.fx("check"));
        put(checks[i], up.add(-0.08, 0.56, 0.36), FACE_UP, 1.0f);
        sound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.9f, 1.3f);
        particles(ParticleTypes.WAX_ON, up.add(-0.1, 0, 0), 6, 0.25, 0.02);
        List<Integer> picked = new ArrayList<>();
        for (int k = 0; k < 5; k++) if (chosen[k]) picked.add(k);
        if (picked.size() < RentalDraft.KEEP) {
            updateChooseHeader();
            return;
        }
        RentalDraft.Result result = draft.pick(draft.currentPack(), picked);
        if (result != RentalDraft.Result.OK) {
            chosen[i] = false;
            hide(kept[i]);
            hide(checks[i]);
            Pose back = pose(i);
            if (flipT[i] == 0) moveCard(i, back.local(), back.spin(), back.scale(), 4);
            placeName(i, true);
            String why = result == RentalDraft.Result.TOO_MANY_LEGENDARY
                    ? "A team keeps at most " + RentalDraft.MAX_TOP_RARITY + " legendary or mythic Pokemon" : "That pick did not take: " + result;
            player.displayClientMessage(Component.literal(why).withColor(0xFF7A6E), true);
            sound(SoundEvents.VILLAGER_NO, 0.6f, 1.0f);
            updateChooseHeader();
            return;
        }
        enter(Stage.LOCKING);
        header.setText(Component.literal("KEPT  ·  ").withColor(GOLD)
                .append(Component.literal(speciesName(p.cards().get(picked.get(0))) + " and " + speciesName(p.cards().get(picked.get(1)))).withColor(INK)));
        for (int k = 0; k < 5; k++) {
            if (chosen[k]) continue;
            wantBack[k] = false;                                           // no turn outlives its card
            flipT[k] = 0;
            moveCard(k, slot(k).add(0.05, -0.1, 0), FACE_UP, 0.001f, 6);   // the others shrink away
            names[k].setText(Component.empty());
        }
        int first = draft.team().size() - 2;
        showLedge(first, true);
        showLedge(first + 1, true);
        sound(SoundEvents.PLAYER_LEVELUP, 0.5f, 1.6f);
        on.changed(player);
    }

    private void restart(ServerPlayer player) {
        draft.restart();
        for (ItemDisplayElement e : ledge) e.setItem(ItemStack.EMPTY);
        ledgeShown = 0;
        sound(SoundEvents.BOOK_PAGE_TURN, 0.8f, 0.8f);
        enter(Stage.SEALED);
        on.changed(player);
    }

    // ---- time --------------------------------------------------------------------------------------------------------------------

    private static final int LAUNCH = 8, STEP = 6, FLIGHT = 8, FLIP_AFTER = 10, OPEN_END = LAUNCH + STEP * 4 + FLIP_AFTER + 20;

    @Override
    protected void onTick() {
        age++;
        stageAge++;
        effects();                                                         // rings and stamps: placed, then moved a tick or two on
        if (stage == Stage.CHOOSE || stage == Stage.LOCKING || stage == Stage.DONE) turns();
        for (int k = 0; k < 6; k++) {                                      // a kept Pokemon's hop: started from its card, then sent up
            if (ledgeHop[k] > 0 && --ledgeHop[k] == 0) {
                move(ledge[k], new Vec3(LEDGE_X, LEDGE_Y + LEDGE_UP, LEDGE_Z[k]), ledgeSpin(k), LEDGE_SCALE, 12);
                particles(ParticleTypes.END_ROD, new Vec3(LEDGE_X - 0.3, LEDGE_Y + 0.4, LEDGE_Z[k]), 6, 0.2, 0.02);
            }
        }
        switch (stage) {
            case SEALED -> {
                if (age % 30 == 0) {                                       // the pack bobs; the rays turn slowly behind it
                    boolean up = (age / 30) % 2 == 0;
                    move(pack, PACK_AT.add(0, up ? 0.08 : -0.02, 0), FACE_UP - 6, 1.0f, 30);
                    move(rays, PACK_AT.add(0.05, 0, 0), FACE_UP, 1.0f, 30);
                    rays.setLeftRotation(new Quaternionf(turn).rotateY((float) Math.toRadians(FACE_UP)).rotateZ((float) Math.toRadians(age * 1.5)));
                    rays.startInterpolation();
                }
                if (age % 12 == 0) particles(ParticleTypes.WAX_ON, PACK_AT, 2, 0.45, 0.0);
            }
            case OPENING -> opening();
            case LOCKING -> {
                if (stageAge >= 26) {
                    on.changed(player());
                    enter(draft.complete() ? Stage.DONE : Stage.SEALED);
                    if (draft.complete()) {
                        sound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.4f, 1.2f);
                        ServerPlayer p = player();
                        if (p != null) p.displayClientMessage(Component.literal("Your rental team is ready: your leader walks in to start").withColor(GREEN), true);
                    }
                }
            }
            case CHOOSE, DONE -> gaze();
        }
    }

    private void opening() {
        int t = stageAge;
        if (t == 1) move(packTop, PACK_AT.add(-0.4, 1.4, -1.3), FACE_UP - 50, 0.7f, 10);      // the strip tears off and spins away
        if (t == 12) hide(packTop);
        RentalDraw.Pack p = currentPack();
        Vec3 mouth = PACK_AT.add(0, 0.55, 0);
        for (int i = 0; i < 5; i++) {
            int launch = LAUNCH + STEP * i;
            if (t == launch) {                                             // out of the pack's mouth, face down (its back toward the player)
                showBack(i);
                snapCard(i, mouth, FACE_UP, 1.12f);
                sound(SoundEvents.BOOK_PAGE_TURN, 0.6f, 1.1f + i * 0.08f);
            }
            if (t == launch + 2) moveCard(i, slot(i), FACE_UP, 1.0f, FLIGHT);       // the arc to its place
            if (t == launch + FLIP_AFTER) {                                // turned over: edge-on...
                moveCard(i, slot(i), FACE_UP + 90, 1.0f, 3);
                sound(SoundEvents.ITEM_FRAME_ROTATE_ITEM, 0.7f, 1.2f);
            }
            if (t == launch + FLIP_AFTER + 3) {                            // ...the face swapped in while it is edge-on...
                showFace(i, p.cards().get(i), tierName(p.tiers().get(i)));
                snapCard(i, slot(i), FACE_UP - 90, 1.0f);
            }
            if (t == launch + FLIP_AFTER + 4) moveCard(i, slot(i), FACE_UP, 1.0f, 3);   // ...and turned on round to face the player
            if (t == launch + FLIP_AFTER + 7) reveal(i, p);
            if (t == launch + FLIP_AFTER + 10) hide(flashes[i]);
            if (t == launch + FLIP_AFTER + 16) hide(rings[i]);
        }
        if (t == OPEN_END) {                                               // the empty wrapper bursts into sparks
            hide(packBody);
            hide(rays);
            particles(ParticleTypes.FIREWORK, PACK_AT, 18, 0.3, 0.08);
            sound(SoundEvents.FIREWORK_ROCKET_TWINKLE, 0.5f, 1.4f);
            enter(Stage.CHOOSE);
        }
    }

    private void reveal(int i, RentalDraw.Pack p) {
        Rarity tier = p.tiers().get(i);
        names[i].setText(DraftModels.label(speciesName(p.cards().get(i)), INK));
        Vec3 at = slot(i);
        if (!tier.atLeast(Rarity.RARE)) return;
        rings[i].setItem(DraftModels.fx("ring"));
        rings[i].setInterpolationDuration(0);
        put(rings[i], at.add(-0.08, 0, 0), FACE_UP, 0.6f);
        rings[i].startInterpolation();
        ringGrow[i] = 2;
        flashes[i].setItem(DraftModels.fx("flash"));
        put(flashes[i], at.add(-0.12, 0, 0), FACE_UP, 1.0f);
        particles(ParticleTypes.END_ROD, at.add(-0.2, 0.3, 0), tier.isTop() ? 24 : 10, 0.35, 0.04);
        if (tier.isTop()) {
            stamps[i].setItem(DraftModels.fx(tier == Rarity.MYTHIC ? "stamp_mythic" : "stamp_legendary"));
            stamps[i].setInterpolationDuration(0);
            put(stamps[i], at.add(-0.16, -0.25, 0), FACE_UP - 9, 2.0f);
            stamps[i].startInterpolation();
            stampSlam[i] = 2;
            sound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.35f, 1.5f);
            ServerPlayer pl = player();
            if (pl != null) {
                long tops = draft.teamTiers().stream().filter(Rarity::isTop).count();
                pl.displayClientMessage(Component.literal((tier == Rarity.MYTHIC ? "Mythic!  " : "Legendary!  ") + speciesName(p.cards().get(i))
                        + "  ·  " + tops + " of " + RentalDraft.MAX_TOP_RARITY + " kept so far").withColor(GOLD), true);
            }
        } else {
            sound(tier == Rarity.EPIC ? SoundEvents.AMETHYST_BLOCK_RESONATE : SoundEvents.EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        }
    }

    private final int[] ringGrow = new int[5], stampSlam = new int[5];

    private void effects() {
        for (int i = 0; i < 5; i++) {
            if (ringGrow[i] > 0 && --ringGrow[i] == 0) move(rings[i], slot(i).add(-0.08, 0, 0), FACE_UP, 2.4f, 12);
            if (stampSlam[i] > 0 && --stampSlam[i] == 0) move(stamps[i], slot(i).add(-0.16, -0.25, 0), FACE_UP - 9, 1.0f, 4);
        }
    }

    /** The card the player looks at tips toward them; its set is on their action bar. */
    private void gaze() {
        if (age % 2 != 0) return;
        ServerPlayer p = player();
        if (p == null) return;
        Vector3f eye = new Quaternionf(turn).conjugate().transform(new Vector3f((float) (p.getEyePosition().x - base.x),
                (float) (p.getEyePosition().y - base.y), (float) (p.getEyePosition().z - base.z)));
        Vec3 look = p.getLookAngle();
        Vector3f dir = new Quaternionf(turn).conjugate().transform(new Vector3f((float) look.x, (float) look.y, (float) look.z));
        int seen = -1;
        boolean strip = false;
        if (dir.x > 0.05f) {
            float s = (float) ((CARD_X - eye.x) / dir.x);
            float y = eye.y + dir.y * s, z = eye.z + dir.z * s;
            boolean done = stage == Stage.DONE;
            int count = done ? draft.team().size() : 5;
            double size = done ? 0.8 : 1.0, cy = done ? CARD_Y - 0.02 : CARD_Y;
            double w = DraftModels.CARD_W * size, h = DraftModels.CARD_H * size;
            for (int i = 0; i < count; i++) {
                double cz = done ? teamZ(i) : cardZ(i);
                if (Math.abs(z - cz) < w / 2 && Math.abs(y - cy) < h / 2) {
                    seen = i;
                    strip = y < cy + h / 2 - STRIP_TOP * h;
                }
            }
        }
        if (seen != lookedAt) {
            int was = lookedAt;
            lookedAt = seen;
            if (stage == Stage.CHOOSE && was >= 0 && !chosen[was]) {
                if (flipT[was] == 0) {
                    Pose at = pose(was);
                    moveCard(was, at.local(), at.spin(), at.scale(), 4);
                }
                placeName(was, true);
            }
            if (stage == Stage.CHOOSE && seen >= 0 && !chosen[seen]) {
                if (flipT[seen] == 0) {
                    Pose at = pose(seen);
                    moveCard(seen, at.local(), at.spin(), at.scale(), 4);
                }
                placeName(seen, true);
                sound(SoundEvents.UI_BUTTON_CLICK.value(), 0.15f, 1.8f);
            }
            hintDue = 0;
        }
        if (inspecting >= 0 && seen != inspecting) {                      // looked away, or at another card: it turns back
            wantBack[inspecting] = false;
            inspecting = -1;
        }
        if (seen != clickedCard || !strip) clickedCard = -1;
        dwell = seen >= 0 && strip && seen != clickedCard ? dwell + 1 : 0;
        if (dwell >= 3 && inspecting < 0 && seen >= 0) {                   // rested on the strip a moment (six ticks): it turns over
            inspecting = seen;
            wantBack[seen] = true;
        }
        if (seen >= 0 && age >= hintDue) {
            hintDue = age + 40;
            RentalSetDefinition set;
            Rarity tier;
            if (stage == Stage.DONE) {
                set = draft.team().get(seen);
                tier = draft.teamTiers().get(seen);
            } else {
                set = currentPack().cards().get(seen);
                tier = currentPack().tiers().get(seen);
            }
            p.displayClientMessage(hint(set, tier), true);
        }
    }

    /** Each card wanting its other side turns over as a revealed card does: edge-on, the side swapped, round again to rest. */
    private void turns() {
        int count = stage == Stage.DONE ? draft.team().size() : 5;
        for (int i = 0; i < count; i++) {
            if (flipT[i] == 0) {
                if (wantBack[i] == backShown[i]) continue;
                Pose p = pose(i);
                moveCard(i, p.local(), p.spin() + 90, p.scale(), 3);
                sound(SoundEvents.ITEM_FRAME_ROTATE_ITEM, 0.5f, wantBack[i] ? 1.3f : 1.1f);
                flipT[i] = 1;
                continue;
            }
            flipT[i]++;
            Pose p = pose(i);
            if (flipT[i] == 4) {                                           // edge-on: the other side swapped in
                backShown[i] = !backShown[i];
                if (backShown[i]) {
                    showStats(i);
                    particles(ParticleTypes.WAX_ON, p.local().add(-0.1, 0, 0), 5, 0.25, 0.02);
                } else {
                    showFace(i, setOf(i), tierName(tierOf(i)));
                    names[i].setText(DraftModels.label(speciesName(setOf(i)), INK));
                }
                snapCard(i, p.local(), p.spin() - 90, p.scale());
            }
            if (flipT[i] == 5) moveCard(i, p.local(), p.spin(), p.scale(), 3);
            if (flipT[i] >= 8) flipT[i] = 0;
        }
    }

    static Component hint(RentalSetDefinition set, Rarity tier) {
        MutableComponent out = Component.literal(speciesName(set)).withColor(0xFFFFFF)
                .append(Component.literal("  ·  ").withColor(MUTED))
                .append(Component.literal(Character.toUpperCase(tier.lower().charAt(0)) + tier.lower().substring(1)).withColor(TIER_COLOUR[tier.ordinal()]));
        set.item().ifPresent(item -> out.append(Component.literal("  ·  " + words(item.getPath())).withColor(INK)));
        for (int k = 0; k < set.moves().size(); k++) {                       // the moves by their game names
            out.append(Component.literal(k == 0 ? "  ·  " : ", ").withColor(MUTED));
            out.append(com.cobbletowers.battle.cobblemon.CobblemonMoves.name(set.moves().get(k)).withColor(MUTED));
        }
        return out;
    }

    private static String words(String id) {
        StringBuilder b = new StringBuilder();
        for (String w : id.split("_")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    Stage stage() {
        return stage;
    }
}
