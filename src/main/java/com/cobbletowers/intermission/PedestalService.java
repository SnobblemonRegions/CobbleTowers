package com.cobbletowers.intermission;

import com.cobbletowers.ServerState;
import com.cobbletowers.TowerLog;
import com.cobbletowers.api.tower.RunState;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.instance.CellGrid;
import com.cobbletowers.instance.CellPreparer;
import com.cobbletowers.instance.TowerDimension;
import com.cobbletowers.modifier.DraftService;
import com.cobbletowers.network.IntermissionStatePayload;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.route.FloorLayouts;
import com.cobbletowers.runtime.TowerRuns;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * The modifier cards as pedestals (P55, the "Arcade Cabinet" look): per card a neon stand on a glowing pad, a light
 * beam, a spinning gem with dust orbiting it, three stacked text plates (name, big payout, details) and an invisible
 * click box, standing near the vendor for an intermission on a tower with {@code pedestal_votes}. A right-click votes
 * through the same {@link IntermissionService#pick} as the menu. Cards grow out of the floor one after another, the
 * card under a crosshair lifts, and a settled vote fires a shockwave. Entities and particles only, so releasing the
 * cell removes them; what they show is rebuilt from the draft, never stored.
 */
public final class PedestalService {

    private static final int GAZE_EVERY_TICKS = 4;
    private static final int SYNC_EVERY_TICKS = 20;
    private static final int SPIN_TICKS = 20;
    private static final int GROW_TICKS = 10;
    private static final int CARD_STAGGER_TICKS = 8;
    /** Plate colours (ARGB): normal, a card under a crosshair, the chosen card, and the cards that lost. */
    private static final int PLATE = 0xE6040410;
    private static final int PLATE_LIFTED = 0xE61A2A66;
    private static final int PLATE_CHOSEN = 0xE6063A1E;
    private static final int PLATE_LOST = 0x99040410;
    private static final float TINY = 0.001f;

    /** The entities of one card, by what they are, so an animation can reach each. */
    private record CardParts(List<UUID> stand, UUID gem, UUID beam, UUID padOuter, UUID padInner, List<UUID> texts, UUID hitbox) {}

    /** What stands for one run: enough to aim at it, redo it, and tell what changed. {@code lifted} is the cards under a crosshair. */
    private record Placed(List<PedestalRules.Slot> slots, List<String> names, List<PedestalStyle.Style> styles,
                          String signature, boolean settled, int[] votes, int chosen, List<CardParts> cards, Set<Integer> lifted,
                          List<IntermissionStatePayload.Card> draftCards, long hold) {}

    /** A pose of a display: where its model's corner lands, how it turns, how big it is. Text anchors at its own origin. */
    private record Pose(double[] t, double[] q, float sx, float sy, float sz, boolean block) {
        CompoundTag tag() {
            return transformation(t, q, sx, sy, sz);
        }

        /** The same display shrunk to nothing at the point its middle sits, for a card that has not grown yet. */
        CompoundTag collapsed() {
            double[] centre = t;
            if (block) {
                double[] half = PedestalRules.rotate(q, sx / 2.0, sy / 2.0, sz / 2.0);
                centre = new double[] {t[0] + half[0], t[1] + half[1], t[2] + half[2]};
            }
            return transformation(centre, q, TINY, TINY, TINY);
        }
    }

    /** A display waiting to grow to its pose. */
    private record Grow(UUID id, Pose pose, boolean text) {}

    private static final Map<UUID, Placed> PLACED = new HashMap<>();
    /** Which card each player's crosshair was last on, so the chime sounds once per look. */
    private static final Map<UUID, Integer> LAST_GAZE = new HashMap<>();
    /** The card a player last clicked in each run: the guess at the winner when the record does not say. */
    private static final Map<UUID, Integer> LAST_VOTE = new HashMap<>();
    private static final UUID NO_VIEWER = new UUID(0L, 0L);
    /** How long the old cards stay up to celebrate before the next round grows in. */
    private static final int LOCK_HOLD_TICKS = 26;

    static {
        ServerState.onStop(PLACED::clear);
        ServerState.onStop(LAST_GAZE::clear);
        ServerState.onStop(LAST_VOTE::clear);
        ServerState.onStop(PedestalAnim::clear);
    }

    private PedestalService() {}

    public static void install() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!entity.getTags().contains(PedestalRules.TAG)) return InteractionResult.PASS;
            if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) return InteractionResult.SUCCESS;
            // Both hands fire for one click; only the main hand votes.
            if (hand == InteractionHand.MAIN_HAND) click(serverPlayer, entity);
            return InteractionResult.SUCCESS;
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (!level.dimension().equals(TowerDimension.LEVEL)) return;
            if (!entity.getTags().contains(PedestalRules.TAG)) return;
            boolean live = entity.getTags().stream().map(PedestalRules::runOf).flatMap(Optional::stream).findFirst()
                    .flatMap(TowerRuns::get).filter(run -> run.state() == RunState.INTERMISSION).isPresent();
            if (!live) entity.discard();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = server.getTickCount();
            try {
                if (!PedestalAnim.idle()) PedestalAnim.run(server, now);
                if (now % GAZE_EVERY_TICKS == 0) tick(server);
            } catch (RuntimeException ex) {
                TowerLog.error("A pedestal tick failed", ex);
            }
        });
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------

    /** Whether the run's tower shows pedestals. */
    public static boolean enabled(PersistedRun run) {
        var tower = TowerDefinitionRegistry.content().towers().get(run.towerId());
        return tower != null && tower.pedestalVotes();
    }

    /** The slots in front of the floor's doors, when it has doors (a walkable tower); empty otherwise. */
    private static Optional<List<PedestalRules.Slot>> atDoors(PersistedRun run, com.cobbletowers.definition.FloorLayout layout, BlockPos origin, int count) {
        if (layout.doors().isEmpty()) return Optional.empty();
        List<PedestalRules.Slot> doors = new ArrayList<>();
        for (var door : layout.doors()) {
            BlockPos at = door.pedestal().in(origin);
            doors.add(new PedestalRules.Slot(at.getX() + 0.5, at.getY(), at.getZ() + 0.5));
        }
        List<Integer> lanes = run.modifiers().draft().filter(draft -> draft.route()).map(draft -> draft.lanes()).orElse(List.of());
        return PedestalRules.atDoors(lanes, doors, count);
    }

    /** Puts the pedestals up or brings them in step with the draft. Idempotent; safe to call at any time. */
    public static void sync(MinecraftServer server, PersistedRun run) {
        if (!enabled(run)) return;
        ServerLevel level = TowerDimension.level(server);
        if (level == null || run.cell().isEmpty() || run.state() != RunState.INTERMISSION) {
            despawn(server, run);
            return;
        }
        IntermissionStatePayload.Draft draft = IntermissionService.draftOf(run, NO_VIEWER);
        Optional<com.cobbletowers.definition.FloorLayout> layout =
                FloorLayouts.layoutOf(TowerDefinitionRegistry.content(), run, run.floorIndex());
        if (draft.state() == 0 || draft.cards().isEmpty() || layout.isEmpty()) {
            despawn(server, run);
            return;
        }
        Optional<BlockPos> origin = CellPreparer.originFor(server, run.cell().getAsInt(), layout.get());
        if (origin.isEmpty()) return;
        // A walkable tower: the chambers behind the doors are dressed for the stops the lanes lead to.
        com.cobbletowers.route.RoomDresser.dress(server, run, layout.get(), origin.get());
        com.cobbletowers.trick.TrickOrTreatService.tend(server, run);

        BlockPos vendor = layout.get().exit().in(origin.get());
        BlockPos toward = layout.get().spectator().in(origin.get());
        int count = draft.cards().size();
        List<PedestalRules.Slot> slots = atDoors(run, layout.get(), origin.get(), count)
                .orElseGet(() -> PedestalRules.layout(vendor.getX() + 0.5, vendor.getZ() + 0.5,
                        toward.getX() + 0.5, toward.getZ() + 0.5, vendor.getY(), count));
        boolean settled = draft.state() == 2;
        int voters = Math.max(1, DraftService.voters(run).size());
        Placed before = PLACED.get(run.runId());
        long now = server.getTickCount();
        if (before != null && before.hold() > now) return;   // the old cards are celebrating; a scheduled sync follows
        Set<Integer> lifted = before == null ? new HashSet<>() : before.lifted();

        List<PedestalHolo.Holo> holos = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<PedestalStyle.Style> styles = new ArrayList<>();
        int[] votes = new int[count];
        StringBuilder signature = new StringBuilder().append(draft.state()).append('|').append(draft.chosen());
        for (int i = 0; i < count; i++) {
            IntermissionStatePayload.Card card = draft.cards().get(i);
            PedestalHolo.Holo holo = PedestalHolo.build(card, card.votes(), voters, settled, draft.chosen() == i);
            holos.add(holo);
            names.add(card.displayName());
            styles.add(PedestalStyle.styleFor(card.risk()));
            votes[i] = card.votes();
            signature.append('|').append(holo.name().getString()).append(holo.big().getString()).append(holo.body().getString());
        }
        if (before != null && before.signature().equals(signature.toString())) return;

        boolean sameCards = before != null && before.names().equals(names) && before.slots().equals(slots);
        // A vote that settled the draft and opened the next round in one go: the old cards celebrate first.
        if (before != null && !sameCards && !before.settled() && !before.cards().isEmpty()) {
            int winner = winnerOf(before, run);
            if (winner >= 0) {
                finishRound(server, level, run, before, winner);
                return;
            }
        }
        if (sameCards && updateTexts(level, before.cards(), holos, settled, draft.chosen(), lifted)) {
            react(server, level, before, run, slots, styles, votes, settled, draft.chosen());
            PLACED.put(run.runId(), new Placed(slots, names, styles, signature.toString(), settled, votes, draft.chosen(),
                    before.cards(), lifted, draft.cards(), 0));
            return;
        }
        // A new set of cards (the first draft, or an event room's next round) grows in; a repair does not replay it.
        boolean intro = !sameCards && !settled;
        PedestalAnim.cancel(run.runId());
        removeEntities(level, run);
        List<CardParts> cards = new ArrayList<>();
        List<List<Grow>> grows = new ArrayList<>();
        lifted = new HashSet<>();
        if (!spawnAll(level, run, slots, styles, holos, settled, draft.chosen(), intro, cards, grows)) {
            removeEntities(level, run);
            PLACED.remove(run.runId());
            TowerLog.warn("Run {}: the modifier pedestals could not be spawned", run.runId());
            return;
        }
        PLACED.put(run.runId(), new Placed(slots, names, styles, signature.toString(), settled, votes, draft.chosen(), cards, lifted,
                draft.cards(), 0));
        if (intro) scheduleIntro(server, run.runId(), slots, styles, cards, grows);
    }

    /** Remembers which card a player just clicked, as the fallback guess at a round's winner. */
    static void noteVote(UUID runId, int cardIndex) {
        LAST_VOTE.put(runId, cardIndex);
    }

    /**
     * Which of the old cards won, or -1. The run's record says it for a modifier or a relic (it is the newest one held);
     * for a room the best the pedestals can do is the most-voted card, then the card last clicked.
     */
    private static int winnerOf(Placed old, PersistedRun run) {
        List<net.minecraft.resources.ResourceLocation> won = new ArrayList<>();
        var modifiers = run.modifiers();
        if (!modifiers.accumulated().isEmpty()) won.add(modifiers.accumulated().get(modifiers.accumulated().size() - 1));
        if (!modifiers.relics().isEmpty()) won.add(modifiers.relics().get(modifiers.relics().size() - 1));
        for (int i = 0; i < old.draftCards().size(); i++) {
            if (won.contains(old.draftCards().get(i).id())) return i;
        }
        int best = -1;
        for (int i = 0; i < old.votes().length; i++) {
            if (old.votes()[i] > 0 && (best < 0 || old.votes()[i] > old.votes()[best])) best = i;
        }
        if (best >= 0) return best;
        Integer clicked = LAST_VOTE.get(run.runId());
        return clicked != null && clicked < old.cards().size() ? clicked : -1;
    }

    /** Plays the lock-in on the old cards, keeps them up for a moment, then lets the next round grow in. */
    private static void finishRound(MinecraftServer server, ServerLevel level, PersistedRun run, Placed old, int winner) {
        long now = server.getTickCount();
        LAST_VOTE.remove(run.runId());
        int voters = Math.max(1, DraftService.voters(run).size());
        List<PedestalHolo.Holo> settled = new ArrayList<>();
        for (int i = 0; i < old.draftCards().size(); i++) {
            IntermissionStatePayload.Card card = old.draftCards().get(i);
            settled.add(PedestalHolo.build(card, card.votes() + (i == winner ? 1 : 0), voters, true, i == winner));
        }
        updateTexts(level, old.cards(), settled, true, winner, old.lifted());
        lockIn(server, level, old, run, old.slots(), old.styles(), winner);
        PLACED.put(run.runId(), new Placed(old.slots(), old.names(), old.styles(), "held", true, old.votes(), winner, old.cards(),
                old.lifted(), old.draftCards(), now + LOCK_HOLD_TICKS));
        PedestalAnim.at(now + LOCK_HOLD_TICKS, run.runId(), s -> refresh(s, run.runId()));
    }

    /** Called after a vote so the count on the hologram changes at once rather than at the next sync. */
    public static void refresh(MinecraftServer server, UUID runId) {
        TowerRuns.get(runId).ifPresent(run -> sync(server, run));
    }

    /** Takes the run's pedestals down. Safe to call at any time and more than once. */
    public static void despawn(MinecraftServer server, PersistedRun run) {
        com.cobbletowers.route.RoomDresser.forget(run.runId());
        PLACED.remove(run.runId());
        LAST_VOTE.remove(run.runId());
        PedestalAnim.cancel(run.runId());
        ServerLevel level = TowerDimension.level(server);
        if (level != null && !run.cell().isEmpty()) removeEntities(level, run);
    }

    // ---- entities --------------------------------------------------------------------------------------------

    /** Searched inside the run's own cell, so a lookup never walks the whole dimension. */
    private static List<Entity> entitiesOf(ServerLevel level, PersistedRun run) {
        String tag = PedestalRules.runTag(run.runId());
        return level.getEntities((Entity) null, CellGrid.boundsOf(run.cell().getAsInt()), e -> e.getTags().contains(tag));
    }

    private static void removeEntities(ServerLevel level, PersistedRun run) {
        for (Entity entity : entitiesOf(level, run)) entity.discard();
    }

    private static Entity live(ServerLevel level, UUID id) {
        Entity entity = id == null ? null : level.getEntity(id);
        return entity == null || entity.isRemoved() ? null : entity;
    }

    private static int plate(boolean settled, int chosen, int index, boolean lifted) {
        if (settled) return chosen == index ? PLATE_CHOSEN : PLATE_LOST;
        return lifted ? PLATE_LIFTED : PLATE;
    }

    private static Component piece(PedestalHolo.Holo holo, int line) {
        return switch (line) {
            case PedestalRules.LINE_NAME -> holo.name();
            case PedestalRules.LINE_BIG -> holo.big();
            default -> holo.body();
        };
    }

    private static boolean updateTexts(ServerLevel level, List<CardParts> cards, List<PedestalHolo.Holo> holos, boolean settled,
                                       int chosen, Set<Integer> lifted) {
        if (cards.size() != holos.size()) return false;
        for (int i = 0; i < holos.size(); i++) {
            for (int line = 0; line < 3; line++) {
                Entity entity = live(level, cards.get(i).texts().get(line));
                if (entity == null) return false;
                CompoundTag data = entity.saveWithoutId(new CompoundTag());
                data.putString("text", Component.Serializer.toJson(piece(holos.get(i), line), level.registryAccess()));
                data.putInt("background", plate(settled, chosen, i, lifted.contains(i)));
                entity.load(data);
            }
        }
        return true;
    }

    private static boolean spawnAll(ServerLevel level, PersistedRun run, List<PedestalRules.Slot> slots,
                                    List<PedestalStyle.Style> styles, List<PedestalHolo.Holo> holos, boolean settled,
                                    int chosen, boolean intro, List<CardParts> out, List<List<Grow>> grows) {
        PedestalRules.Stack stack = PedestalRules.stack(PedestalRules.BODY_LINES);
        double[] ys = {stack.nameY(), stack.bigY(), stack.bodyY()};
        double[] scales = {PedestalRules.NAME_SCALE, PedestalRules.BIG_SCALE, PedestalRules.BODY_SCALE};
        int[] widths = {400, 200, 190};
        double[] noMove = {0, 0, 0};
        double[] noTurn = {0, 0, 0, 1};
        for (int i = 0; i < slots.size(); i++) {
            PedestalRules.Slot slot = slots.get(i);
            PedestalStyle.Style look = styles.get(i);
            String[] tags = {PedestalRules.TAG, PedestalRules.runTag(run.runId()), PedestalRules.cardTag(i)};
            List<Grow> grow = new ArrayList<>();

            UUID padOuter = block(level, look.capBlock(), slot, 0, new Pose(new double[] {-1.0, 0.005, -1.0}, noTurn, 2.0f, 0.03f, 2.0f, true),
                    true, tags, PedestalRules.PART_STAND, intro, grow);
            UUID padInner = block(level, "minecraft:black_concrete", slot, 0, new Pose(new double[] {-0.91, 0.0075, -0.91}, noTurn, 1.82f, 0.035f, 1.82f, true),
                    false, tags, PedestalRules.PART_STAND, intro, grow);
            List<UUID> stand = new ArrayList<>();
            stand.add(block(level, "minecraft:black_concrete", slot, 0, new Pose(new double[] {-0.55, 0, -0.55}, noTurn, 1.1f, 0.28f, 1.1f, true),
                    false, tags, PedestalRules.PART_STAND, intro, grow));
            stand.add(block(level, look.capBlock(), slot, 0, new Pose(new double[] {-0.58, 0.28, -0.58}, noTurn, 1.16f, 0.05f, 1.16f, true),
                    true, tags, PedestalRules.PART_STAND, intro, grow));
            stand.add(block(level, "minecraft:polished_blackstone", slot, 0, new Pose(new double[] {-0.35, 0.33, -0.35}, noTurn, 0.7f, 0.62f, 0.7f, true),
                    false, tags, PedestalRules.PART_STAND, intro, grow));
            stand.add(block(level, look.capBlock(), slot, 0, new Pose(new double[] {-0.45, 0.95, -0.45}, noTurn, 0.9f, 0.1f, 0.9f, true),
                    true, tags, PedestalRules.PART_STAND, intro, grow));
            UUID beam = block(level, look.beamBlock(), slot, 0, beamPose(0.14), true, tags, PedestalRules.PART_STAND, intro, grow);
            UUID gem = block(level, look.gemBlock(), slot, PedestalRules.GEM_HEIGHT, gemPose(0, 0), true, tags, PedestalRules.PART_GEM, intro, grow);

            List<UUID> texts = new ArrayList<>();
            for (int line = 0; line < 3; line++) {
                float s = (float) scales[line];
                Pose pose = new Pose(noMove, noTurn, s, s, s, false);
                CompoundTag text = base("minecraft:text_display", slot.x(), slot.y() + ys[line], slot.z(), tags, PedestalRules.PART_TEXT);
                text.getList("Tags", 8).add(StringTag.valueOf(PedestalRules.lineTag(line)));
                text.putString("text", Component.Serializer.toJson(piece(holos.get(i), line), level.registryAccess()));
                text.putString("billboard", "center");
                text.putString("alignment", "center");
                text.putInt("line_width", widths[line]);
                text.putInt("background", plate(settled, chosen, i, false));
                text.putBoolean("shadow", true);
                text.put("brightness", fullbright());
                text.put("transformation", intro ? pose.collapsed() : pose.tag());
                if (intro) text.putByte("text_opacity", (byte) 5);
                UUID id = add(level, text);
                if (id == null) return false;
                texts.add(id);
                if (intro) grow.add(new Grow(id, pose, true));
            }

            CompoundTag hitbox = base("minecraft:interaction", slot.x(), slot.y(), slot.z(), tags, PedestalRules.PART_HITBOX);
            hitbox.putFloat("width", (float) PedestalRules.HITBOX_WIDTH);
            hitbox.putFloat("height", (float) PedestalRules.HITBOX_HEIGHT);
            hitbox.putBoolean("response", true);
            UUID hit = add(level, hitbox);

            if (hit == null || gem == null || beam == null || padOuter == null || padInner == null || stand.contains(null)) return false;
            out.add(new CardParts(stand, gem, beam, padOuter, padInner, texts, hit));
            grows.add(grow);
        }
        return true;
    }

    private static UUID add(ServerLevel level, CompoundTag tag) {
        Optional<Entity> made = EntityType.create(tag, level);
        if (made.isEmpty() || !level.addFreshEntity(made.get())) return null;
        return made.get().getUUID();
    }

    /** One block display at the slot, collapsed first when the card is about to grow in. Null when it could not be spawned. */
    private static UUID block(ServerLevel level, String blockId, PedestalRules.Slot slot, double dy, Pose pose, boolean glow,
                              String[] tags, String part, boolean intro, List<Grow> grow) {
        CompoundTag tag = base("minecraft:block_display", slot.x(), slot.y() + dy, slot.z(), tags, part);
        CompoundTag state = new CompoundTag();
        state.putString("Name", blockId);
        tag.put("block_state", state);
        tag.put("transformation", intro ? pose.collapsed() : pose.tag());
        if (glow) tag.put("brightness", fullbright());
        UUID id = add(level, tag);
        if (id != null && intro) grow.add(new Grow(id, pose, false));
        return id;
    }

    private static CompoundTag fullbright() {
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        return brightness;
    }

    private static CompoundTag base(String id, double x, double y, double z, String[] tags, String part) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(y));
        pos.add(DoubleTag.valueOf(z));
        tag.put("Pos", pos);
        ListTag tagList = new ListTag();
        for (String t : tags) tagList.add(StringTag.valueOf(t));
        tagList.add(StringTag.valueOf(part));
        tag.put("Tags", tagList);
        tag.putBoolean("Invulnerable", true);
        return tag;
    }

    /** The beam: a thin translucent column from the cap toward the ceiling; {@code width} makes it breathe. */
    private static Pose beamPose(double width) {
        return new Pose(new double[] {-width / 2, 1.05, -width / 2}, new double[] {0, 0, 0, 1}, (float) width, 5.6f, (float) width, true);
    }

    private static Pose padPose(double size) {
        return new Pose(new double[] {-size / 2, 0.005, -size / 2}, new double[] {0, 0, 0, 1}, (float) size, 0.03f, (float) size, true);
    }

    /** The gem at {@code spin} radians; {@code lift} raises and enlarges it (1 for a card under a crosshair, 2 for the winner). */
    private static Pose gemPose(double spin, double lift) {
        double[] q = PedestalRules.gemRotation(spin);
        double size = PedestalRules.GEM_SIZE * (1 + 0.2 * lift);
        double[] centre = PedestalRules.centreOffset(q, size);
        return new Pose(new double[] {centre[0], centre[1] + 0.18 * lift, centre[2]}, q, (float) size, (float) size, (float) size, true);
    }

    private static CompoundTag transformation(double[] translation, double[] rotation, float sx, float sy, float sz) {
        CompoundTag t = new CompoundTag();
        t.put("translation", floats((float) translation[0], (float) translation[1], (float) translation[2]));
        t.put("scale", floats(sx, sy, sz));
        t.put("left_rotation", floats((float) rotation[0], (float) rotation[1], (float) rotation[2], (float) rotation[3]));
        t.put("right_rotation", floats(0f, 0f, 0f, 1f));
        return t;
    }

    private static ListTag floats(float... values) {
        ListTag list = new ListTag();
        for (float v : values) list.add(FloatTag.valueOf(v));
        return list;
    }

    /** Moves a display to a pose over {@code ticks}; the client interpolates, so one update gives a smooth move. */
    private static void pose(ServerLevel level, UUID id, Pose pose, int ticks) {
        Entity entity = live(level, id);
        if (entity == null) return;
        CompoundTag data = entity.saveWithoutId(new CompoundTag());
        data.put("transformation", pose.tag());
        data.putInt("start_interpolation", 0);
        data.putInt("interpolation_duration", ticks);
        entity.load(data);
    }

    private static void opacity(ServerLevel level, List<UUID> texts, int value) {
        for (UUID id : texts) {
            Entity entity = live(level, id);
            if (entity == null) continue;
            CompoundTag data = entity.saveWithoutId(new CompoundTag());
            data.putByte("text_opacity", (byte) value);
            entity.load(data);
        }
    }

    private static void background(ServerLevel level, List<UUID> texts, int argb) {
        for (UUID id : texts) {
            Entity entity = live(level, id);
            if (entity == null) continue;
            CompoundTag data = entity.saveWithoutId(new CompoundTag());
            data.putInt("background", argb);
            entity.load(data);
        }
    }

    // ---- effects ---------------------------------------------------------------------------------------------

    private static DustParticleOptions dust(PedestalStyle.Style look, float size) {
        return new DustParticleOptions(new Vector3f(look.dust()[0], look.dust()[1], look.dust()[2]), size);
    }

    /** Sparks and tinted dust around a gem; {@code big} adds a firework ring for the winner. */
    private static void burst(ServerLevel level, PedestalRules.Slot slot, PedestalStyle.Style look, int ring, boolean big) {
        double y = slot.y() + PedestalRules.GEM_HEIGHT;
        level.sendParticles(dust(look, 1.4f), slot.x(), y, slot.z(), ring, 0.45, 0.35, 0.45, 0.0);
        for (int j = 0; j < ring; j++) {
            double a = j * Math.PI * 2 / ring;
            level.sendParticles(big ? ParticleTypes.FIREWORK : ParticleTypes.ELECTRIC_SPARK, slot.x(), y, slot.z(), 0,
                    Math.cos(a) * 0.35, 0.12, Math.sin(a) * 0.35, 1.0);
        }
    }

    /** Each card grows out of the floor in turn, its plates flickering in, with a spark and a chime as it lands. */
    private static void scheduleIntro(MinecraftServer server, UUID runId, List<PedestalRules.Slot> slots, List<PedestalStyle.Style> styles,
                                      List<CardParts> cards, List<List<Grow>> grows) {
        long start = server.getTickCount() + 6;
        for (int i = 0; i < cards.size(); i++) {
            final int card = i;
            long due = start + (long) i * CARD_STAGGER_TICKS;
            PedestalAnim.at(due, runId, s -> {
                ServerLevel level = TowerDimension.level(s);
                if (level == null) return;
                for (Grow g : grows.get(card)) {
                    Entity entity = live(level, g.id());
                    if (entity == null) continue;
                    CompoundTag data = entity.saveWithoutId(new CompoundTag());
                    data.put("transformation", g.pose().tag());
                    data.putInt("start_interpolation", 0);
                    data.putInt("interpolation_duration", g.text() ? GROW_TICKS - 2 : GROW_TICKS);
                    if (g.text()) data.putByte("text_opacity", (byte) 40);
                    entity.load(data);
                }
                PedestalRules.Slot slot = slots.get(card);
                burst(level, slot, styles.get(card), 14, false);
                level.playSound(null, slot.x(), slot.y() + 1.5, slot.z(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS,
                        0.9f, 0.9f + 0.25f * card);
            });
            // the plates flicker in: dim, bright, dim, solid
            int[] flicker = {200, 70, 255};
            for (int step = 0; step < flicker.length; step++) {
                final int value = flicker[step];
                PedestalAnim.at(due + 2L * (step + 1), runId, s -> {
                    ServerLevel level = TowerDimension.level(s);
                    if (level != null) opacity(level, cards.get(card).texts(), value);
                });
            }
        }
    }

    /** What changed since the last look: a card that gained a vote pops, and a settled draft locks in. */
    private static void react(MinecraftServer server, ServerLevel level, Placed before, PersistedRun run,
                              List<PedestalRules.Slot> slots, List<PedestalStyle.Style> styles, int[] votes, boolean settled, int chosen) {
        for (int i = 0; i < slots.size() && i < before.votes().length; i++) {
            if (votes[i] > before.votes()[i]) {
                PedestalRules.Slot slot = slots.get(i);
                burst(level, slot, styles.get(i), 14, false);
                level.playSound(null, slot.x(), slot.y() + 1.5, slot.z(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.BLOCKS, 0.8f, 1.3f);
            }
        }
        if (settled && !before.settled() && chosen >= 0 && chosen < slots.size()) lockIn(server, level, before, run, slots, styles, chosen);
    }

    /**
     * The vote locks in: a shockwave runs out across the floor, the winning gem and beam surge, a title tells the party,
     * and the cards that lost flicker out to static.
     */
    private static void lockIn(MinecraftServer server, ServerLevel level, Placed placed, PersistedRun run,
                               List<PedestalRules.Slot> slots, List<PedestalStyle.Style> styles, int chosen) {
        long now = server.getTickCount();
        UUID runId = run.runId();
        PedestalRules.Slot slot = slots.get(chosen);
        PedestalStyle.Style look = styles.get(chosen);
        CardParts winner = placed.cards().get(chosen);
        placed.lifted().clear();

        burst(level, slot, look, 28, true);
        level.playSound(null, slot.x(), slot.y() + 1.5, slot.z(), SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 0.8f, 1.0f);
        level.playSound(null, slot.x(), slot.y() + 1.0, slot.z(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 1.0f, 0.7f);
        announce(server, DraftService.voters(run), placed.names().get(chosen), look.accent());

        pose(level, winner.gem(), gemPose(angleAt(now), 2), 4);
        pose(level, winner.beam(), beamPose(0.5), 4);
        pose(level, winner.padOuter(), padPose(2.5), 4);
        PedestalAnim.at(now + 10, runId, s -> {
            ServerLevel l = TowerDimension.level(s);
            if (l == null) return;
            pose(l, winner.gem(), gemPose(angleAt(s.getTickCount()), 0), 8);
            pose(l, winner.beam(), beamPose(0.14), 8);
            pose(l, winner.padOuter(), padPose(2.0), 8);
        });
        for (int k = 1; k <= 10; k++) {
            final double radius = 0.5 + 0.55 * k;
            PedestalAnim.at(now + k, runId, s -> {
                ServerLevel l = TowerDimension.level(s);
                if (l == null) return;
                for (int j = 0; j < 28; j++) {
                    double[] p = PedestalRules.ring(slot.x(), slot.z(), radius, j * Math.PI * 2 / 28);
                    l.sendParticles(ParticleTypes.END_ROD, p[0], slot.y() + 0.15, p[1], 0, 0, 0.03, 0, 1.0);
                    if (j % 2 == 0) l.sendParticles(dust(look, 1.2f), p[0], slot.y() + 0.12, p[1], 1, 0, 0, 0, 0.0);
                }
            });
        }
        // the losing cards flicker to static, then stay dim
        int[] flicker = {60, 200, 40, 120};
        for (int i = 0; i < placed.cards().size(); i++) {
            if (i == chosen) continue;
            final List<UUID> texts = placed.cards().get(i).texts();
            for (int step = 0; step < flicker.length; step++) {
                final int value = flicker[step];
                PedestalAnim.at(now + 2L * (step + 1), runId, s -> {
                    ServerLevel l = TowerDimension.level(s);
                    if (l != null) opacity(l, texts, value);
                });
            }
        }
    }

    private static void announce(MinecraftServer server, List<UUID> audience, String name, int accent) {
        Component title = Component.literal("LOCKED IN").setStyle(Style.EMPTY.withColor(accent).withBold(true));
        Component subtitle = Component.literal(name.toUpperCase(Locale.ROOT)).setStyle(Style.EMPTY.withColor(0xFFFFFF));
        for (UUID id : audience) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) continue;
            player.connection.send(new ClientboundSetTitlesAnimationPacket(4, 26, 10));
            player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
            player.connection.send(new ClientboundSetTitleTextPacket(title));
        }
    }

    private static double angleAt(long tick) {
        return (tick / SPIN_TICKS) * (Math.PI / 2);
    }

    /** Every second: each gem turns a quarter, and the beams and pads breathe. The client smooths the second between. */
    private static void spin(ServerLevel level, long tick) {
        double angle = angleAt(tick);
        boolean wide = (tick / SPIN_TICKS) % 2 == 0;
        for (UUID runId : List.copyOf(PLACED.keySet())) {
            Placed placed = PLACED.get(runId);
            for (int i = 0; i < placed.cards().size(); i++) {
                CardParts card = placed.cards().get(i);
                if (live(level, card.gem()) == null) {
                    PLACED.remove(runId);   // the next sync puts the stand back up
                    break;
                }
                if (placed.settled() && placed.chosen() != i) continue;   // the cards that lost stand still
                pose(level, card.gem(), gemPose(angle, placed.lifted().contains(i) ? 1 : 0), SPIN_TICKS);
                pose(level, card.beam(), beamPose(wide ? 0.2 : 0.12), SPIN_TICKS);
                pose(level, card.padOuter(), padPose(wide ? 2.14 : 2.0), SPIN_TICKS);
            }
        }
    }

    /** Dust orbiting each gem on two crossed rings, a spark rising up each beam, and the odd drift of runes. */
    private static void ambient(ServerLevel level, long tick) {
        double angle = tick * 0.22;
        for (Placed placed : PLACED.values()) {
            for (int i = 0; i < placed.slots().size(); i++) {
                if (placed.settled() && placed.chosen() != i) continue;
                PedestalRules.Slot slot = placed.slots().get(i);
                PedestalStyle.Style look = placed.styles().get(i);
                double gy = slot.y() + PedestalRules.GEM_HEIGHT;
                for (int ring = 0; ring < 2; ring++) {
                    for (int j = 0; j < 3; j++) {
                        double[] p = PedestalRules.orbit(slot.x(), gy, slot.z(), 0.62, (ring == 0 ? angle : -angle) + j * Math.PI * 2 / 3,
                                ring == 0 ? 0.55 : -0.55);
                        level.sendParticles(dust(look, 0.8f), p[0], p[1], p[2], 1, 0, 0, 0, 0.0);
                    }
                }
                level.sendParticles(ParticleTypes.END_ROD, slot.x(), slot.y() + 1.3, slot.z(), 0, 0, 1.0, 0, 0.04);
                if (tick % 16 == 0) level.sendParticles(ParticleTypes.ENCHANT, slot.x(), gy + 0.3, slot.z(), 6, 0.6, 0.5, 0.6, 0.5);
            }
        }
    }

    // ---- clicking and looking --------------------------------------------------------------------------------

    /** The tick of each player's last accepted click: one click arrives as two interaction events, and the copy must not vote on the next vote. */
    private static final Map<UUID, Integer> LAST_CLICK = new HashMap<>();
    private static final int CLICK_GAP_TICKS = 10;

    private static boolean repeatedClick(ServerPlayer player) {
        int now = player.server.getTickCount();
        Integer last = LAST_CLICK.get(player.getUUID());
        if (last != null && now - last < CLICK_GAP_TICKS) return true;
        LAST_CLICK.put(player.getUUID(), now);
        if (LAST_CLICK.size() > 32) LAST_CLICK.values().removeIf(tick -> now - tick > CLICK_GAP_TICKS);
        return false;
    }

    private static void click(ServerPlayer player, Entity entity) {
        if (repeatedClick(player)) return;
        Optional<UUID> pedestalRun = entity.getTags().stream().map(PedestalRules::runOf).flatMap(Optional::stream).findFirst();
        Optional<Integer> card = entity.getTags().stream().map(PedestalRules::cardOf).flatMap(Optional::stream).findFirst();
        if (pedestalRun.isEmpty() || card.isEmpty()) return;
        Optional<PersistedRun> playerRun = TowerRuns.forPlayer(player.getUUID());
        String message;
        if (playerRun.isEmpty() || !playerRun.get().runId().equals(pedestalRun.get())) {
            message = "These pedestals belong to another team.";
        } else if (playerRun.get().state() != RunState.INTERMISSION) {
            message = "You can vote between floors.";
        } else {
            Placed placed = PLACED.get(pedestalRun.get());
            String name = placed != null && card.get() < placed.names().size() ? placed.names().get(card.get()) : "";
            message = IntermissionService.pick(player.server, player, card.get());
            if ("Vote recorded.".equals(message) && !name.isEmpty()) message = "You voted for " + name + ".";
        }
        TowerLog.info("Pedestal of run {}: {} clicked card {}", pedestalRun.get(), player.getGameProfile().getName(), card.get());
        if (!message.isEmpty()) player.displayClientMessage(Component.literal(message), true);
    }

    private static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        boolean syncNow = now % SYNC_EVERY_TICKS == 0;
        Set<UUID> synced = new HashSet<>();
        Set<UUID> watching = new HashSet<>();
        Map<UUID, Set<Integer>> looked = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.level().dimension().equals(TowerDimension.LEVEL)) continue;
            Optional<PersistedRun> run = TowerRuns.forPlayer(player.getUUID()).filter(r -> r.state() == RunState.INTERMISSION);
            if (run.isEmpty() || !enabled(run.get())) continue;
            UUID runId = run.get().runId();
            // A run with nothing standing yet (after a restart) is brought up here, then kept in step each second.
            if ((syncNow || !PLACED.containsKey(runId)) && synced.add(runId)) sync(server, run.get());
            watching.add(player.getUUID());
            int index = gaze(player, PLACED.get(runId));
            if (index >= 0) looked.computeIfAbsent(runId, id -> new HashSet<>()).add(index);
        }
        LAST_GAZE.keySet().retainAll(watching);
        ServerLevel level = TowerDimension.level(server);
        if (level == null || PLACED.isEmpty()) return;
        lift(level, now, looked);
        ambient(level, now);
        if (syncNow) {
            prune(server);
            spin(level, now);
        }
    }

    /** The card under any player's crosshair lifts and its plates brighten; it settles back when nobody looks. */
    private static void lift(ServerLevel level, long now, Map<UUID, Set<Integer>> looked) {
        for (Map.Entry<UUID, Placed> entry : PLACED.entrySet()) {
            Placed placed = entry.getValue();
            if (placed.settled()) continue;
            Set<Integer> want = looked.getOrDefault(entry.getKey(), Set.of());
            if (want.equals(placed.lifted())) continue;
            for (int i = 0; i < placed.cards().size(); i++) {
                boolean on = want.contains(i);
                if (on == placed.lifted().contains(i)) continue;
                CardParts card = placed.cards().get(i);
                pose(level, card.gem(), gemPose(angleAt(now), on ? 1 : 0), 4);
                background(level, card.texts(), on ? PLATE_LIFTED : PLATE);
            }
            placed.lifted().clear();
            placed.lifted().addAll(want);
        }
    }

    /** Takes down pedestals whose run has left the intermission by a road that did not pass through the transition hook. */
    private static void prune(MinecraftServer server) {
        for (UUID runId : List.copyOf(PLACED.keySet())) {
            Optional<PersistedRun> run = TowerRuns.get(runId);
            if (run.isEmpty()) {
                PLACED.remove(runId);
                PedestalAnim.cancel(runId);
            } else if (run.get().state() != RunState.INTERMISSION) {
                despawn(server, run.get());
            }
        }
    }

    /** What the player's crosshair is on: prompts, chimes and sparkles for them alone. Returns the card index, or -1. */
    private static int gaze(ServerPlayer player, Placed placed) {
        if (placed == null || placed.settled()) {
            LAST_GAZE.remove(player.getUUID());
            return -1;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Optional<Integer> hit = PedestalRules.gazedAt(eye.x, eye.y, eye.z, look.x, look.y, look.z, placed.slots());
        if (hit.isEmpty() || hit.get() >= placed.names().size()) {
            LAST_GAZE.remove(player.getUUID());
            return -1;
        }
        int index = hit.get();
        PedestalRules.Slot slot = placed.slots().get(index);
        PedestalStyle.Style style = placed.styles().get(index);
        MutableComponent prompt = Component.literal("▶ Click to vote: " + placed.names().get(index).toUpperCase(Locale.ROOT))
                .setStyle(Style.EMPTY.withColor(style.accent()).withBold(true));
        player.displayClientMessage(prompt, true);
        player.serverLevel().sendParticles(player, dust(style, 1.0f), false, slot.x(), slot.y() + PedestalRules.GEM_HEIGHT, slot.z(),
                3, 0.45, 0.4, 0.45, 0.0);
        Integer last = LAST_GAZE.put(player.getUUID(), index);
        if (last == null || last != index) {
            player.playNotifySound(SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 0.8f, style.pitch());
            player.serverLevel().sendParticles(player, ParticleTypes.ELECTRIC_SPARK, false, slot.x(), slot.y() + PedestalRules.GEM_HEIGHT,
                    slot.z(), 8, 0.4, 0.3, 0.4, 0.0);
        }
        return index;
    }
}
