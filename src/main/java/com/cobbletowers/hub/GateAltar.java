package com.cobbletowers.hub;

import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment;
import eu.pb4.polymer.virtualentity.api.elements.InteractionElement;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.TextDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.VirtualElement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The Gate Altar at a Tower Hub gate (owner, 20 Nov 2026): one textured object that takes a party's whole pick. Its parts are item models
 * ({@link AltarModels}): the still body, a focus that turns and bobs, a ring of seven Threat runes (lit up to the level, all in that level's
 * colour), four ready strips on the base (one lit per member standing on the ready ring), the First floor and Best crystals with a mote
 * circling one for each vote, and a plate over it all. Clicks come through its invisible collision ({@link #partAt}); a player looking at
 * a part gets a hint in the action bar. Polymer virtual entities only: nothing saved, drawn again with its chunk. Main thread only.
 */
final class GateAltar extends ElementHolder {

    /**
     * A board part is one display at the altar drawn out on its board by translation, and the client culls a display by a box round where
     * it stands; this wide a box keeps the boards drawn while the altar itself is off screen.
     */
    private static final float BOARD_CULL = 20f;

    enum Part { THREAT, FIRST, BEST, TILE, RULES }

    /** A click or a look: the part, and for a mode tile which one. */
    record Hit(Part part, int tile) {}

    /**
     * What the altar and its boards show: the Threat level (0 none), ready members, votes each way, the settled pick (null if none), the
     * altar's plate; the chosen mode (its index among the tiles) and the rules panel's three lines.
     */
    record Shown(int threat, int ready, int votesFirst, int votesBest, Boolean settledBest, Component title, Component line,
                 int mode, Component rulesTitle, Component rulesBody, Component rulesFoot) {}

    private static final int MAX_MOTES = 4;
    private static final Brightness FULL = new Brightness(15, 15);

    private final ServerLevel level;
    private final Vec3 base;
    private final Quaternionf turn;
    private final float yaw;
    private final ItemDisplayElement focus, focusGlow, crystalFirst, crystalBest;
    private final ItemDisplayElement[] runes = new ItemDisplayElement[7];
    private final ItemDisplayElement[] strips = new ItemDisplayElement[4];
    private final ItemDisplayElement[] motesFirst = new ItemDisplayElement[MAX_MOTES], motesBest = new ItemDisplayElement[MAX_MOTES];
    private final TextDisplayElement title, line;
    private final ItemDisplayElement[] tiles;
    private final ItemDisplayElement tileLit;
    private final TextDisplayElement rulesTitle, rulesBody, rulesFoot;
    private final Vec3 focusAt, firstAt, bestAt;
    private final Map<UUID, Hit> gazing = new HashMap<>();
    private BiFunction<ServerPlayer, Hit, String> hint = (p, part) -> "";
    private java.util.function.BiConsumer<ServerPlayer, Vec3> onClick = (p, at) -> { };
    private Shown shown;
    private int age;

    GateAltar(ServerLevel level, Vec3 base, float yawDegrees, String tower) {
        this.level = level;
        this.base = base;
        this.yaw = yawDegrees;
        this.turn = new Quaternionf().rotateY((float) Math.toRadians(yawDegrees));
        AltarModels.Layout at = AltarModels.layout();
        ItemDisplayElement body = item(AltarModels.own(tower, "body"), false);
        body.setTranslation(new Vector3f(0, (float) at.bodyUp(), 0));
        body.setLeftRotation(turn);
        ItemDisplayElement bodyGlow = item(AltarModels.own(tower, "body_glow"), true);
        bodyGlow.setTranslation(new Vector3f(0, (float) at.bodyUp(), 0));
        bodyGlow.setLeftRotation(turn);
        focusAt = at.focus().getOrDefault(tower, at.focus().get("neutral"));
        focus = item(AltarModels.own(tower, "focus"), false);
        focusGlow = item(AltarModels.own(tower, "focus_glow"), true);
        for (ItemDisplayElement f : new ItemDisplayElement[] {focus, focusGlow}) {
            f.setInterpolationDuration(40);
            place(f, focusAt, 0);
        }
        for (int i = 0; i < 7; i++) {
            runes[i] = item(AltarModels.common("rune_off"), false);
            place(runes[i], at.runes().get(i), 0);
        }
        for (int k = 0; k < 4; k++) {
            float quarter = -90f * k;                       // the front strip, then each next side a quarter turn on
            strips[k] = item(AltarModels.common("ready_off"), false);
            Vec3 p = rotate(at.ready(), quarter);
            place(strips[k], p, quarter);
        }
        firstAt = at.first();
        bestAt = at.best();
        // the vote crystals and their motes are not drawn (owner, 24 Nov 2026: they read as stray block icons); a vote is still a click on
        // the altar's left or right, named in the action bar, and a settled pick still bursts where its crystal was
        crystalFirst = item(ItemStack.EMPTY, true);
        crystalBest = item(ItemStack.EMPTY, true);
        for (ItemDisplayElement c : new ItemDisplayElement[] {crystalFirst, crystalBest}) c.setInterpolationDuration(8);
        place(crystalFirst, firstAt, 0);
        place(crystalBest, bestAt, 0);
        for (int i = 0; i < MAX_MOTES; i++) {
            motesFirst[i] = item(ItemStack.EMPTY, true);
            motesBest[i] = item(ItemStack.EMPTY, true);
            motesFirst[i].setInterpolationDuration(10);
            motesBest[i].setInterpolationDuration(10);
        }
        title = text(3.05, 1.1f);                          // half a block lower with the altar (24 Nov 2026)
        line = text(2.7, 0.8f);
        // the twin mode boards: their frames in chunks, the tiles, the chosen tile's lit frame, the names and the rules, flat on the boards
        for (AltarModels.Chunk c : at.boards().getOrDefault(tower, at.boards().get("neutral"))) {
            ItemDisplayElement e = item(AltarModels.chunk(c.model()), c.glow());
            e.setDisplaySize(BOARD_CULL, 6);
            place(e, c.at(), 180);                                   // an item display draws a model half turned; turn it back
        }
        AltarModels.Tiles t = at.tiles();
        tiles = new ItemDisplayElement[t.modes().size()];
        for (int i = 0; i < tiles.length; i++) {
            tiles[i] = item(AltarModels.common("tile_" + t.modes().get(i)), true);
            tiles[i].setInterpolationDuration(6);
            tiles[i].setDisplaySize(BOARD_CULL, 6);
            place(tiles[i], new Vec3(t.x(), t.y(), tileZ(t, i)), 180);
            flat(new Vec3(t.x() + 0.13, 0.72, tileZ(t, i)), 90, 0.55f, Component.literal(MODE_NAMES.getOrDefault(t.modes().get(i), t.modes().get(i))));
        }
        tileLit = item(AltarModels.common("tile_lit"), true);
        tileLit.setDisplaySize(BOARD_CULL, 6);
        tileLit.setScale(new Vector3f(0.001f, 0.001f, 0.001f));
        flat(new Vec3(t.x() + 0.2, 3.62, 0), 90, 0.9f, Component.literal("MODE").withColor(0xF2C744));
        flat(new Vec3(-t.x() - 0.2, 3.62, 0), -90, 0.9f, Component.literal("RULES").withColor(0xF2C744));
        rulesTitle = flat(new Vec3(-t.x() - 0.16, 2.25, 0), -90, 1.0f, Component.empty());
        rulesBody = flat(new Vec3(-t.x() - 0.16, 1.45, 0), -90, 0.55f, Component.empty());
        rulesFoot = flat(new Vec3(-t.x() - 0.16, 0.95, 0), -90, 0.45f, Component.empty());
        poseMotes(false);
        // The altar's body (owner, 25 Nov 2026: the barriers' outline showed over the lowered altar): one barrier block in its middle
        // column, which the model closes in on every side and from above so its outline never shows; anyone stepping onto the altar's
        // edge is nudged back off (see onTick); and one invisible click box over it all, whose clicks say where they landed.
        InteractionElement click = new InteractionElement(new VirtualElement.InteractionHandler() {
            @Override
            public void interactAt(ServerPlayer player, net.minecraft.world.InteractionHand hand, Vec3 at) {
                // the game sends an interact-at and an interact for one click: the first, with its spot, is the click
                if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) onClick.accept(player, base.add(at));
            }
        });
        click.setSize(3.0f, 1.45f);
        click.setOffset(Vec3.ZERO);
        addElement(click);
        ChunkAttachment.ofTicking(this, level, base);
    }

    // ---- building ----------------------------------------------------------------------------------------------------------------

    private ItemDisplayElement item(ItemStack stack, boolean bright) {
        ItemDisplayElement e = new ItemDisplayElement(stack);
        e.setModelTransformation(ItemDisplayContext.NONE);
        e.setViewRange(1.2f);
        e.setShadowRadius(0);
        if (bright) e.setBrightness(FULL);
        return addElement(e);
    }

    private TextDisplayElement text(double y, float scale) {
        TextDisplayElement t = new TextDisplayElement(Component.empty());
        t.setBillboardMode(Display.BillboardConstraints.CENTER);
        t.setTextAlignment(Display.TextDisplay.Align.CENTER);
        t.setLineWidth(320);
        t.setBackground(0xD6080710);
        t.setShadow(true);
        t.setBrightness(FULL);
        t.setViewRange(1.2f);
        t.setScale(new Vector3f(scale, scale, scale));
        t.setOffset(new Vec3(0, y, 0));
        return addElement(t);
    }

    static final java.util.Map<String, String> MODE_NAMES = java.util.Map.of("standard", "Standard", "hardcore", "Hardcore", "level_cap_50",
            "Level Cap 50", "monotype", "Monotype", "underdog", "Underdog", "solo_gauntlet", "Solo Gauntlet", "rental", "Rental Draft");

    /** Text laid flat on a board (not turning to the viewer): at {@code local}, its face turned {@code face} degrees from the altar's front. */
    private TextDisplayElement flat(Vec3 local, float face, float scale, Component text) {
        TextDisplayElement t = new TextDisplayElement(text);
        t.setBillboardMode(Display.BillboardConstraints.FIXED);
        t.setTextAlignment(Display.TextDisplay.Align.CENTER);
        t.setLineWidth(220);
        t.setBackground(0);
        t.setShadow(true);
        t.setBrightness(FULL);
        t.setViewRange(0.8f);
        t.setDisplaySize(4, 2);
        t.setScale(new Vector3f(scale, scale, scale));
        Vector3f p = new Vector3f((float) local.x, (float) local.y, (float) local.z);
        turn.transform(p);
        t.setOffset(new Vec3(p.x, p.y, p.z));
        t.setLeftRotation(new Quaternionf(turn).rotateY((float) Math.toRadians(face)));
        return addElement(t);
    }

    /** A tile's place along its board. */
    private static double tileZ(AltarModels.Tiles t, int i) {
        return t.z().get(i);
    }

    /** {@code p} turned about y by {@code degrees} (the altar's own quarter turns, before its yaw). */
    private static Vec3 rotate(Vec3 p, float degrees) {
        Vector3f v = new Quaternionf().rotateY((float) Math.toRadians(degrees)).transform(new Vector3f((float) p.x, (float) p.y, (float) p.z));
        return new Vec3(v.x, v.y, v.z);
    }

    /** Puts a centred part at {@code local} (blocks from the floor centre, the altar's own frame), turned by {@code spin} degrees more. */
    private void place(ItemDisplayElement e, Vec3 local, float spin) {
        Vector3f p = new Vector3f((float) local.x, (float) local.y, (float) local.z);
        turn.transform(p);
        e.setTranslation(p);
        e.setLeftRotation(new Quaternionf(turn).rotateY((float) Math.toRadians(spin)));
    }

    // ---- what it shows -------------------------------------------------------------------------------------------------------------

    void hint(BiFunction<ServerPlayer, Hit, String> what) {
        hint = what;
    }

    /** What a click on the altar's body does, given where it landed (world). */
    void onClick(java.util.function.BiConsumer<ServerPlayer, Vec3> what) {
        onClick = what;
    }

    /** Brings the altar in step; only what changed is sent. A pick that has just settled bursts on its crystal. */
    void show(Shown next) {
        Shown was = shown;
        shown = next;
        if (was == null || was.threat() != next.threat()) {
            for (int i = 0; i < 7; i++) {
                boolean lit = i < next.threat();
                runes[i].setItem(AltarModels.common(lit ? "rune_" + next.threat() : "rune_off"));
                runes[i].setBrightness(lit ? FULL : null);
            }
            if (was != null && next.threat() > was.threat()) chime(1.0f + 0.12f * next.threat());
        }
        if (was == null || was.ready() != next.ready()) {
            for (int k = 0; k < 4; k++) {
                boolean lit = k < next.ready();
                strips[k].setItem(AltarModels.common(lit ? "ready_on" : "ready_off"));
                strips[k].setBrightness(lit ? FULL : null);
            }
        }
        if (was == null || !java.util.Objects.equals(was.settledBest(), next.settledBest())) {
            grow(crystalFirst, firstAt, Boolean.FALSE.equals(next.settledBest()));
            grow(crystalBest, bestAt, Boolean.TRUE.equals(next.settledBest()));
            if (next.settledBest() != null && was != null) burst(next.settledBest() ? bestAt : firstAt, next.settledBest());
        }
        if (was == null || was.mode() != next.mode()) {
            AltarModels.Tiles t = AltarModels.layout().tiles();
            for (int i = 0; i < tiles.length; i++) {
                boolean chosen = i == next.mode();
                boolean wasChosen = was != null && i == was.mode();
                if (was != null && chosen == wasChosen) continue;          // only the tiles that move glide (owner, 24 Nov 2026: a click
                place(tiles[i], new Vec3(t.x() + (chosen ? t.proud() : 0), t.y(), tileZ(t, i)), 180);   // pulled the whole row)
                tiles[i].startInterpolation();
            }
            if (next.mode() >= 0 && next.mode() < tiles.length) {
                tileLit.setScale(new Vector3f(1, 1, 1));
                place(tileLit, new Vec3(t.x() + t.proud(), t.y(), tileZ(t, next.mode())), 180);
            } else {
                tileLit.setScale(new Vector3f(0.001f, 0.001f, 0.001f));
            }
            if (was != null) chime(1.6f);
        }
        if (was == null || !was.rulesTitle().equals(next.rulesTitle())) rulesTitle.setText(next.rulesTitle());
        if (was == null || !was.rulesBody().equals(next.rulesBody())) rulesBody.setText(next.rulesBody());
        if (was == null || !was.rulesFoot().equals(next.rulesFoot())) rulesFoot.setText(next.rulesFoot());
        if (was == null || !was.title().equals(next.title())) title.setText(next.title());
        if (was == null || !was.line().equals(next.line())) line.setText(next.line());
        if (was == null || was.votesFirst() != next.votesFirst() || was.votesBest() != next.votesBest()) poseMotes(false);
    }

    private void grow(ItemDisplayElement crystal, Vec3 at, boolean chosen) {
        float s = chosen ? 1.18f : 1.0f;
        crystal.setScale(new Vector3f(s, s, s));
        place(crystal, at.add(0, chosen ? 0.06 : 0, 0), 0);
        crystal.startInterpolation();
    }

    private void poseMotes(boolean interpolate) {
        int first = shown == null ? 0 : Math.min(MAX_MOTES, shown.votesFirst());
        int best = shown == null ? 0 : Math.min(MAX_MOTES, shown.votesBest());
        orbit(motesFirst, firstAt, first, interpolate);
        orbit(motesBest, bestAt, best, interpolate);
    }

    private void orbit(ItemDisplayElement[] motes, Vec3 at, int count, boolean interpolate) {
        for (int i = 0; i < MAX_MOTES; i++) {
            double a = age * 0.12 + i * Math.PI * 2 / Math.max(1, count);
            Vec3 p = at.add(Math.cos(a) * 0.38, 0.15 + 0.12 * Math.sin(a * 0.7 + i), Math.sin(a) * 0.38);
            place(motes[i], p, 0);
            float s = i < count ? 1f : 0.001f;
            motes[i].setScale(new Vector3f(s, s, s));
            if (interpolate) motes[i].startInterpolation();
        }
    }

    private void chime(float pitch) {
        level.playSound(null, base.x, base.y + 1.6, base.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.8f, pitch);
    }

    private void burst(Vec3 local, boolean best) {
        Vector3f p = new Vector3f((float) local.x, (float) local.y, (float) local.z);
        turn.transform(p);
        Vector3f colour = best ? new Vector3f(1f, 0.83f, 0.3f) : new Vector3f(0.88f, 0.92f, 1f);
        DustParticleOptions dust = new DustParticleOptions(colour, 1.2f);
        for (int i = 0; i < 20; i++) {
            double a = i * Math.PI / 10;
            level.sendParticles(dust, base.x + p.x + Math.cos(a) * 0.7, base.y + p.y + 0.2, base.z + p.z + Math.sin(a) * 0.7, 1, 0, 0.04, 0, 0);
        }
        level.playSound(null, base.x + p.x, base.y + p.y, base.z + p.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 1f, best ? 1.3f : 1.0f);
    }

    // ---- clicks and looks ----------------------------------------------------------------------------------------------------------

    /**
     * What a click on the collision under the altar or its boards means, or null for none of them: on the altar, its left (seen from the
     * front) is First floor, its right Best, its middle Threat; on the left board, the nearest mode tile; on the right board, the rules.
     */
    Hit hitAt(Vec3 hit) {
        Vector3f local = new Vector3f((float) (hit.x - base.x), 0, (float) (hit.z - base.z));
        new Quaternionf(turn).conjugate().transform(local);
        if (Math.abs(local.x) <= 1.6f && Math.abs(local.z) <= 1.6f) {
            return new Hit(local.x < -0.75f ? Part.FIRST : local.x > 0.75f ? Part.BEST : Part.THREAT, -1);
        }
        if (Math.abs(local.z) > 4.7f || Math.abs(local.x) < 6.3f || Math.abs(local.x) > 8.7f) return null;
        if (local.x > 0) return new Hit(Part.RULES, -1);
        AltarModels.Tiles t = AltarModels.layout().tiles();
        int best = 0;
        for (int i = 1; i < t.z().size(); i++) {
            if (Math.abs(tileZ(t, i) - local.z) < Math.abs(tileZ(t, best) - local.z)) best = i;
        }
        return new Hit(Part.TILE, best);
    }

    private Hit lookedHit(ServerPlayer p) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        AltarModels.Tiles t = AltarModels.layout().tiles();
        Hit best = null;
        double bestAlong = 9.0;
        for (int i = -1; i < t.z().size(); i++) {
            Vec3 local = i < 0 ? new Vec3(-t.x(), 1.6, 0) : new Vec3(t.x(), t.y(), tileZ(t, i));
            Vector3f w = new Vector3f((float) local.x, (float) local.y, (float) local.z);
            turn.transform(w);
            Vec3 to = new Vec3(base.x + w.x - eye.x, base.y + w.y - eye.y, base.z + w.z - eye.z);
            double along = to.dot(look);
            if (along <= 0 || along > bestAlong) continue;
            if (to.subtract(look.scale(along)).length() < (i < 0 ? 1.4 : 0.45)) {
                best = i < 0 ? new Hit(Part.RULES, -1) : new Hit(Part.TILE, i);
                bestAlong = along;
            }
        }
        if (best != null) return best;
        Part part = looked(p);
        return part == null ? null : new Hit(part, -1);
    }

    private Part looked(ServerPlayer p) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        Part best = null;
        double bestAlong = 7.0;
        Vec3 threatAt = new Vec3(0, 1.25, 0);
        for (Part part : new Part[] {Part.THREAT, Part.FIRST, Part.BEST}) {          // the altar's own parts (the boards are looked at above)
            Vec3 local = switch (part) { case FIRST -> firstAt; case BEST -> bestAt; default -> threatAt; };
            Vector3f w = new Vector3f((float) local.x, (float) local.y, (float) local.z);
            turn.transform(w);
            Vec3 to = new Vec3(base.x + w.x - eye.x, base.y + w.y - eye.y, base.z + w.z - eye.z);
            double along = to.dot(look);
            if (along <= 0 || along > bestAlong) continue;
            double off = to.subtract(look.scale(along)).length();
            if (off < (part == Part.THREAT ? 0.9 : 0.55)) {
                best = part;
                bestAlong = along;
            }
        }
        return best;
    }

    @Override
    public boolean startWatching(ServerGamePacketListenerImpl handler) {
        // a client without the server pack would see sheets of paper, so it is not shown the altar (its collision and plate still work)
        return PolymerResourcePackUtils.hasMainPack(handler.getPlayer()) && super.startWatching(handler);
    }

    /** How far out from the altar's middle its footprint reaches (its base is three blocks across), plus a player's half width. */
    private static final double FOOT = 1.5 + 0.3;

    /** Anyone standing on the altar's footprint (not its middle, which is a barrier) is pushed gently back out the way they came in. */
    private void keepOff() {
        for (ServerGamePacketListenerImpl c : getWatchingPlayers()) {
            ServerPlayer p = c.getPlayer();
            if (p.isSpectator()) continue;
            double dx = p.getX() - base.x, dz = p.getZ() - base.z, dy = p.getY() - base.y;
            if (Math.abs(dx) >= FOOT || Math.abs(dz) >= FOOT || dy < -0.5 || dy > 2.2) continue;
            double len = Math.max(0.001, Math.sqrt(dx * dx + dz * dz));
            p.setDeltaMovement(dx / len * 0.32, Math.max(0, p.getDeltaMovement().y), dz / len * 0.32);
            p.hurtMarked = true;                            // sends the push to the player's game
        }
    }

    @Override
    protected void onTick() {
        age++;
        keepOff();
        if (age % 40 == 0) {                                // the focus turns a quarter and bobs, smoothly
            float spin = (age / 40) * 90f;
            double bob = (age / 40) % 2 == 0 ? 0.06 : -0.02;
            for (ItemDisplayElement f : new ItemDisplayElement[] {focus, focusGlow}) {
                place(f, focusAt.add(0, bob, 0), spin);
                f.startInterpolation();
            }
        }
        if (age % 10 == 0) poseMotes(true);
        if (age % 4 != 0) return;
        Map<UUID, Hit> now = new HashMap<>();
        for (ServerGamePacketListenerImpl c : getWatchingPlayers()) {
            ServerPlayer p = c.getPlayer();
            Hit hit = lookedHit(p);
            if (hit == null) continue;
            now.put(p.getUUID(), hit);
            if (!hit.equals(gazing.get(p.getUUID()))) {
                String h = hint.apply(p, hit);
                if (!h.isEmpty()) p.displayClientMessage(Component.literal(h), true);
                p.playNotifySound(SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.BLOCKS, 0.3f, hit.part() == Part.THREAT ? 1.0f : hit.part() == Part.BEST ? 1.4f : 1.2f);
            }
        }
        gazing.clear();
        gazing.putAll(now);
    }
}
