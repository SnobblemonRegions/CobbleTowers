package com.cobbletowers.intermission;

import com.cobbletowers.CobbleTowers;
import com.cobbletowers.api.modifier.RiskTier;
import com.cobbletowers.definition.ModifierDefinition;
import com.cobbletowers.definition.TowerContent;
import com.cobbletowers.encounter.EncounterSeed;
import com.cobbletowers.modifier.ModifierResolver;
import com.cobbletowers.persistence.PersistedRun;
import com.cobbletowers.persistence.RunModifierState;
import com.cobbletowers.trial.TrialRules;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongFunction;
import net.minecraft.resources.ResourceLocation;

/**
 * Intermission events (P34b): a room that may appear between the draft and the ready-up, with two options voted on
 * like a draft card. Pure: room and outcome come from the run seed. A party heal is returned as a flag for the
 * caller.
 */
public final class IntermissionEvents {

    /** Its own ordinal space, like every other draw (see {@code DraftDraw}). */
    static final int EVENT_ORDINAL_BASE = 5_000_011;

    /** The chance, in percent, that an eligible intermission has a room at all. */
    public static final int ROOM_CHANCE_PERCENT = 50;

    private IntermissionEvents() {}

    public enum Room {
        SHRINE, GAMBLER, REST,
        /** P45: pay CobbleDollars for a battle relic. */
        FORGE,
        /** P45: a scouting relic, free. */
        LIBRARY,
        /** P45: a coin-flip between a purse and an ambush. */
        VAULT,
        /** P45: swap a held relic for another. */
        POOL,
        /** P55: the Jack-o'-Lantern Vault of an event tower: even odds of a treat or a mimic, and a lost mimic costs a relic and part of the loot. */
        TRICK_OR_TREAT,
        /** Offered only at a regional milestone, and only when there is an Echo to meet (P35). */
        ECHO_DUEL
    }

    public enum Option {
        SHRINE_CURSE(Room.SHRINE, "shrine_curse", "Shrine: take the curse (a relic now, a challenge for good)"),
        SHRINE_LEAVE(Room.SHRINE, "shrine_leave", "Refuse the shrine: no relic, no curse"),
        GAMBLER_STAKE(Room.GAMBLER, "gambler_stake", "Gambler: stake a relic (even odds: win another, or lose it)"),
        GAMBLER_PASS(Room.GAMBLER, "gambler_pass", "Refuse the wager: keep your relics"),
        REST_HEAL(Room.REST, "rest_heal", "Rest: fully heal the party"),
        REST_PRESS_ON(Room.REST, "rest_press_on", "Refuse the rest: the party stays hurt"),
        FORGE_PAY(Room.FORGE, "forge_pay", "Forge: every player pays CobbleDollars for a battle relic"),
        FORGE_LEAVE(Room.FORGE, "forge_leave", "Refuse the forge: keep your CobbleDollars, no relic"),
        LIBRARY_STUDY(Room.LIBRARY, "library_study", "Library: study, and take a scouting relic"),
        LIBRARY_SKIP(Room.LIBRARY, "library_skip", "Refuse to study: no relic"),
        VAULT_CRACK(Room.VAULT, "vault_crack", "Vault: crack it (even odds: a purse for everyone, or an ambush)"),
        VAULT_LEAVE(Room.VAULT, "vault_leave", "Refuse the vault: leave it sealed"),
        POOL_DIVE(Room.POOL, "pool_dive", "Mystic Pool: dive, and swap a relic for a different one"),
        POOL_STAY(Room.POOL, "pool_stay", "Refuse the pool: keep your relics"),
        TRICK_OPEN(Room.TRICK_OR_TREAT, "trick_open", "Jack-o'-Lantern Vault: open it (even odds: a treat, or a mimic. Lose to the mimic and you lose a relic and 5% of the final loot)"),
        TRICK_LEAVE(Room.TRICK_OR_TREAT, "trick_leave", "Refuse the Jack-o'-Lantern Vault: leave it shut, no treat, no mimic"),
        ECHO_FIGHT(Room.ECHO_DUEL, "echo_fight", "Echo Duel: face a champion's team (a safe exhibition, 100 CobbleDollars if you win)"),
        ECHO_DECLINE(Room.ECHO_DUEL, "echo_decline", "Refuse the Echo Duel: no fight, no reward");

        private final Room room;
        private final String id;
        private final String label;

        Option(Room room, String id, String label) {
            this.room = room;
            this.id = id;
            this.label = label;
        }

        public Room room() {
            return room;
        }

        public String label() {
            return label;
        }

        /** The draft-card id of this option. */
        public ResourceLocation cardId() {
            return CobbleTowers.id("event/" + id);
        }

        public static Optional<Option> fromCard(ResourceLocation card) {
            return Arrays.stream(values()).filter(option -> option.cardId().equals(card)).findFirst();
        }
    }

    /**
     * What an option did: the new state, whether the party is healed, a sentence for the team, and CobbleDollars each
     * participant gains (positive) or pays (negative).
     */
    public record Outcome(RunModifierState state, boolean healParty, String message, boolean duel, int walletEach) {
        public Outcome(RunModifierState state, boolean healParty, String message, boolean duel) {
            this(state, healParty, message, duel, 0);
        }

        public Outcome(RunModifierState state, boolean healParty, String message) {
            this(state, healParty, message, false, 0);
        }
    }

    /** How likely each ordinary room is among the eligible ones (relative weights, untuned). */
    static final Map<Room, Integer> WEIGHTS = new EnumMap<>(Map.of(
            Room.REST, 30, Room.SHRINE, 15, Room.GAMBLER, 15, Room.FORGE, 15, Room.LIBRARY, 10, Room.VAULT, 10, Room.POOL, 10,
            Room.TRICK_OR_TREAT, 12));
    /** The relic tags a Forge may give: the ones that change a fight. */
    static final Set<String> FORGE_TAGS = Set.of("offense", "tempo", "sustain", "boss");

    /** An option's label on a given floor: the Forge and the Vault say what they cost or pay there. */
    public static String labelAt(Option option, int floorIndex) {
        return switch (option) {
            case FORGE_PAY -> "Forge: every player pays " + forgeCost(floorIndex) + " CobbleDollars for a battle relic";
            case VAULT_CRACK -> "Vault: crack it (even odds: " + vaultPurse(floorIndex) + " CobbleDollars for everyone, or an ambush)";
            case REST_PRESS_ON -> "Refuse the rest: +" + restPurse(floorIndex) + " CobbleDollars each, the party stays hurt";
            default -> option.label();
        };
    }

    /** What the Forge charges each player on this floor. */
    public static int forgeCost(int floorIndex) {
        return 100 + 25 * floorIndex;
    }

    /** What a cracked Vault pays each player on this floor. */
    public static int vaultPurse(int floorIndex) {
        return 150 + 25 * floorIndex;
    }

    /**
     * What refusing a rest pays each player on this floor: at least twice the vendor's 25 CobbleDollar full heal, growing with the floor, so a hurt party
     * that presses on is paid for the risk (TODO #8).
     */
    public static int restPurse(int floorIndex) {
        return 50 + 10 * floorIndex;
    }

    private static final Set<Option> REFUSALS = Set.of(Option.SHRINE_LEAVE, Option.GAMBLER_PASS, Option.REST_PRESS_ON, Option.FORGE_LEAVE,
            Option.LIBRARY_SKIP, Option.VAULT_LEAVE, Option.POOL_STAY, Option.TRICK_LEAVE, Option.ECHO_DECLINE);

    /** Whether an option is the room's refusal (always its second card): the pedestals mark it, and it answers in words of its own. */
    public static boolean isRefusal(Option option) {
        return REFUSALS.contains(option);
    }

    /**
     * An operator can switch rooms off with {@code -Dcobbletowers.events=off} (also used by tests of the draft
     * alone).
     */
    public static boolean enabled() {
        return !"off".equalsIgnoreCase(System.getProperty("cobbletowers.events", "on"));
    }

    private static volatile Room FORCED_ROOM;

    /** Whether {@code room} could be offered now (P50: a route stop only opens a room that can do something). */
    public static boolean canOffer(TowerContent content, PersistedRun run, int floorIndex, Room room, ToLongFunction<UUID> balance) {
        return enabled() && eligible(content, run, floorIndex, room, balance);
    }

    /**
     * The room the stop the party chose opens (P50): the stop's own room if it can still do something, the Echo Duel at a milestone
     * when there is one to meet, and a dev-build forced room first (as {@link #roomFor} does).
     */
    public static Optional<Room> roomForNode(TowerContent content, PersistedRun run, int floorIndex, boolean echoAvailable,
                                             ToLongFunction<UUID> balance, Optional<Room> node) {
        if (!enabled()) return Optional.empty();
        if (content.milestoneAt(run.towerId(), floorIndex).isPresent()) return echoAvailable ? Optional.of(Room.ECHO_DUEL) : Optional.empty();
        Room asked = FORCED_ROOM;
        if (asked != null) {
            FORCED_ROOM = null;
            return eligible(content, run, floorIndex, asked, balance) ? Optional.of(asked) : Optional.empty();
        }
        return node.filter(room -> eligible(content, run, floorIndex, room, balance));
    }

    /** Dev build test seam: the next ordinary room is this one if it is eligible. */
    public static void forceNextRoom(Room room) {
        FORCED_ROOM = room;
    }

    public static long seedOf(PersistedRun run, int floorIndex) {
        return EncounterSeed.of(run.seed(), floorIndex, EVENT_ORDINAL_BASE);
    }

    // ---- choosing a room -------------------------------------------------------------------------

    /**
     * The room this intermission has, if any. A milestone floor has no ordinary room (it pays a relic) but may have
     * the Echo Duel; other floors have a room about half the time.
     */
    public static Optional<Room> roomFor(TowerContent content, PersistedRun run, int floorIndex, boolean echoAvailable) {
        return roomFor(content, run, floorIndex, echoAvailable, id -> Long.MAX_VALUE);
    }

    /** As above; {@code balance} says what each participant could pay (the Forge needs everyone to afford it). */
    public static Optional<Room> roomFor(TowerContent content, PersistedRun run, int floorIndex, boolean echoAvailable,
                                         ToLongFunction<UUID> balance) {
        if (!enabled()) return Optional.empty();
        if (content.milestoneAt(run.towerId(), floorIndex).isPresent()) {
            return echoAvailable ? Optional.of(Room.ECHO_DUEL) : Optional.empty();
        }
        // a dev build can ask for a particular room once (runs draft room), so a test can see every room live
        Room asked = FORCED_ROOM;
        if (asked != null) {
            FORCED_ROOM = null;
            return eligible(content, run, floorIndex, asked, balance) ? Optional.of(asked) : Optional.empty();
        }
        // a weekly rule (P46) can make every second floor a relic room, and can take Rest rooms away
        boolean relicFloor = floorIndex % 2 == 0 && TrialRules.hasRule(run, "relics_every_second_floor");
        if (!relicFloor && Math.floorMod(seedOf(run, floorIndex), 100) >= ROOM_CHANCE_PERCENT) return Optional.empty();
        boolean noRest = TrialRules.hasRule(run, "no_healing_rooms");
        Optional<String> bias = TrialRules.roomBias(run);
        List<Room> eligible = new ArrayList<>();
        int total = 0;
        for (Room room : Room.values()) {
            if (room == Room.ECHO_DUEL || room == Room.TRICK_OR_TREAT || (room == Room.REST && noRest)) continue;
            if (relicFloor && room != Room.SHRINE && room != Room.FORGE && room != Room.LIBRARY) continue;
            if (eligible(content, run, floorIndex, room, balance)) {
                eligible.add(room);
                total += weightOf(room, bias);
            }
        }
        if (eligible.isEmpty()) return Optional.empty();
        long roll = Math.floorMod(EncounterSeed.of(run.seed(), floorIndex, EVENT_ORDINAL_BASE + 1), total);
        for (Room room : eligible) {
            roll -= weightOf(room, bias);
            if (roll < 0) return Optional.of(room);
        }
        return Optional.of(eligible.get(eligible.size() - 1));
    }

    /** A room's weight; the room a rotating trial leans toward counts triple. */
    public static int weightOf(Room room, Optional<String> bias) {
        int weight = WEIGHTS.getOrDefault(room, 10);
        return bias.isPresent() && bias.get().equals(room.name().toLowerCase(java.util.Locale.ROOT)) ? weight * 3 : weight;
    }

    static boolean eligible(TowerContent content, PersistedRun run, int floorIndex, Room room) {
        return eligible(content, run, floorIndex, room, id -> Long.MAX_VALUE);
    }

    static boolean eligible(TowerContent content, PersistedRun run, int floorIndex, Room room, ToLongFunction<UUID> balance) {
        RunModifierState state = run.modifiers();
        return switch (room) {
            case SHRINE -> state.hasRelicRoom() && !relicChoices(content, state).isEmpty()
                    && !curseChoices(content, run, floorIndex).isEmpty();
            case GAMBLER -> !state.relics().isEmpty();
            case REST -> true;
            case FORGE -> state.hasRelicRoom() && !forgeChoices(content, state).isEmpty() && everyoneCanPay(run, balance, forgeCost(floorIndex));
            case LIBRARY -> state.hasRelicRoom() && !libraryChoices(content, state).isEmpty();
            case VAULT -> !curseChoices(content, run, floorIndex).isEmpty();
            case POOL -> !state.relics().isEmpty() && !relicChoices(content, state).isEmpty();
            // only ever a route stop of an event tower: the route draw decides where, so any run that reaches it may open it
            case TRICK_OR_TREAT -> true;
            case ECHO_DUEL -> false;
        };
    }

    private static boolean everyoneCanPay(PersistedRun run, ToLongFunction<UUID> balance, int cost) {
        if (run.participants().isEmpty()) return false;
        for (var participant : run.participants()) {
            if (balance.applyAsLong(participant.playerId()) < cost) return false;
        }
        return true;
    }

    /** Relics a Forge may give: battle relics the run could take. */
    private static List<ModifierDefinition> forgeChoices(TowerContent content, RunModifierState state) {
        return relicChoices(content, state).stream().filter(relic -> relic.tags().stream().anyMatch(FORGE_TAGS::contains)).toList();
    }

    /** Relics a Library may give: scouting relics the run could take. */
    private static List<ModifierDefinition> libraryChoices(TowerContent content, RunModifierState state) {
        return relicChoices(content, state).stream().filter(relic -> relic.tags().contains("scouting")).toList();
    }

    /** The two cards of a room, in a fixed order. */
    public static List<ResourceLocation> cardsOf(Room room) {
        return Arrays.stream(Option.values()).filter(option -> option.room() == room).map(Option::cardId).toList();
    }

    // ---- resolving an option ---------------------------------------------------------------------

    public static Outcome resolve(TowerContent content, PersistedRun run, int floorIndex, Option option) {
        return resolve(content, run, floorIndex, option, id -> Long.MAX_VALUE);
    }

    /** As above; a paying room that someone can no longer afford does nothing. */
    public static Outcome resolve(TowerContent content, PersistedRun run, int floorIndex, Option option,
                                  ToLongFunction<UUID> balance) {
        RunModifierState state = run.modifiers();
        long seed = seedOf(run, floorIndex);
        switch (option) {
            case SHRINE_CURSE -> {
                List<ModifierDefinition> curses = curseChoices(content, run, floorIndex);
                List<ModifierDefinition> relics = relicChoices(content, state);
                if (curses.isEmpty() || relics.isEmpty() || !state.hasRelicRoom()) {
                    return new Outcome(state, false, "The shrine is silent.");
                }
                ModifierDefinition curse = curses.get((int) Math.floorMod(seed >> 3, curses.size()));
                ModifierDefinition relic = relics.get((int) Math.floorMod(seed >> 7, relics.size()));
                return new Outcome(state.accumulating(curse.id(), floorIndex + 1).withRelic(relic.id(), floorIndex + 1), false,
                        "The shrine takes its price (" + curse.displayName() + ") and gives " + relic.displayName() + ".");
            }
            case GAMBLER_STAKE -> {
                if (state.relics().isEmpty()) return new Outcome(state, false, "You have nothing to stake.");
                ResourceLocation staked = state.relics().get((int) Math.floorMod(seed >> 3, state.relics().size()));
                if (Math.floorMod(seed >> 11, 2) == 0) {
                    List<ModifierDefinition> relics = relicChoices(content, state);
                    if (relics.isEmpty() || !state.hasRelicRoom()) {
                        return new Outcome(state, false, "You win, but there is nothing left to win.");
                    }
                    ModifierDefinition won = relics.get((int) Math.floorMod(seed >> 7, relics.size()));
                    return new Outcome(state.withRelic(won.id(), floorIndex + 1), false, "The gambler pays out: " + won.displayName() + ".");
                }
                return new Outcome(state.withoutRelic(staked), false, "The gambler takes your stake.");
            }
            case FORGE_PAY -> {
                List<ModifierDefinition> relics = forgeChoices(content, state);
                int cost = forgeCost(floorIndex);
                if (relics.isEmpty() || !state.hasRelicRoom()) return new Outcome(state, false, "The forge has nothing left to make.");
                if (!everyoneCanPay(run, balance, cost)) return new Outcome(state, false, "The smith waits: someone cannot pay " + cost + ".");
                ModifierDefinition relic = relics.get((int) Math.floorMod(seed >> 7, relics.size()));
                return new Outcome(state.withRelic(relic.id(), floorIndex + 1), false,
                        "Each of you pays " + cost + " CobbleDollars; the smith hands over " + relic.displayName() + ".", false, -cost);
            }
            case LIBRARY_STUDY -> {
                List<ModifierDefinition> relics = libraryChoices(content, state);
                if (relics.isEmpty() || !state.hasRelicRoom()) return new Outcome(state, false, "The shelves are bare.");
                ModifierDefinition relic = relics.get((int) Math.floorMod(seed >> 7, relics.size()));
                return new Outcome(state.withRelic(relic.id(), floorIndex + 1), false, "You study late and take " + relic.displayName() + ".");
            }
            case VAULT_CRACK -> {
                if (Math.floorMod(seed >> 11, 2) == 0) {
                    int purse = vaultPurse(floorIndex);
                    return new Outcome(state, false, "The vault opens: " + purse + " CobbleDollars each.", false, purse);
                }
                List<ModifierDefinition> curses = curseChoices(content, run, floorIndex);
                if (curses.isEmpty()) return new Outcome(state, false, "The vault is empty.");
                ModifierDefinition curse = curses.get((int) Math.floorMod(seed >> 3, curses.size()));
                return new Outcome(state.accumulating(curse.id(), floorIndex + 1), false,
                        "An ambush! The vault is a trap: " + curse.displayName() + " for the rest of the run.");
            }
            case POOL_DIVE -> {
                if (state.relics().isEmpty()) return new Outcome(state, false, "You have nothing to give the pool.");
                ResourceLocation given = state.relics().get((int) Math.floorMod(seed >> 3, state.relics().size()));
                RunModifierState without = state.withoutRelic(given);
                List<ModifierDefinition> relics = relicChoices(content, without).stream().filter(relic -> !relic.id().equals(given)).toList();
                if (relics.isEmpty()) return new Outcome(state, false, "The pool is still.");
                ModifierDefinition taken = relics.get((int) Math.floorMod(seed >> 7, relics.size()));
                return new Outcome(without.withRelic(taken.id(), floorIndex + 1), false,
                        "The pool takes " + content.modifier(given).map(ModifierDefinition::displayName).orElse("a relic")
                                + " and gives " + taken.displayName() + ".");
            }
            case ECHO_FIGHT -> {
                return new Outcome(state, false, "The Echo Duel begins.", true);
            }
            case TRICK_OPEN -> {
                // the coin is read and acted on by TrickOrTreatService, which owns the vault and the fight; this is the line a pure caller sees
                return new Outcome(state, false, "The vault creaks open...");
            }
            case REST_HEAL -> {
                return new Outcome(state, true, "The party rests and is fully healed.");
            }
            case REST_PRESS_ON -> {
                int purse = restPurse(floorIndex);
                return new Outcome(state, false, "You refuse the rest and press on hurt: +" + purse + " CobbleDollars each.", false, purse);
            }
            case SHRINE_LEAVE -> {
                return new Outcome(state, false, "You refuse the shrine's offering. Nothing changes.");
            }
            case GAMBLER_PASS -> {
                return new Outcome(state, false, "You refuse the wager and keep your relics.");
            }
            case FORGE_LEAVE -> {
                return new Outcome(state, false, "You refuse the forge and keep your CobbleDollars.");
            }
            case LIBRARY_SKIP -> {
                return new Outcome(state, false, "You refuse to study. The shelves stay closed.");
            }
            case VAULT_LEAVE -> {
                return new Outcome(state, false, "You refuse the vault and leave it sealed.");
            }
            case POOL_STAY -> {
                return new Outcome(state, false, "You refuse the pool and keep your relics.");
            }
            case TRICK_LEAVE -> {
                return new Outcome(state, false, "You refuse the vault and leave it shut. No treat, no mimic.");
            }
            case ECHO_DECLINE -> {
                return new Outcome(state, false, "You refuse the duel. The Echo fades.");
            }
            default -> {
                return new Outcome(state, false, "You press on.");
            }
        }
    }

    /** The relic a treat carries: any the run could take now, chosen by the seed; empty when there is none or no room for one. */
    public static Optional<ModifierDefinition> treatRelic(TowerContent content, PersistedRun run, int floorIndex) {
        RunModifierState state = run.modifiers();
        List<ModifierDefinition> relics = relicChoices(content, state);
        if (relics.isEmpty() || !state.hasRelicRoom()) return Optional.empty();
        return Optional.of(relics.get((int) Math.floorMod(seedOf(run, floorIndex) >> 7, relics.size())));
    }

    /** Relics the run could take now, in the pool's stable order. */
    private static List<ModifierDefinition> relicChoices(TowerContent content, RunModifierState state) {
        List<ModifierDefinition> held = new ArrayList<>();
        for (ResourceLocation id : state.relics()) content.modifier(id).ifPresent(held::add);
        return ModifierResolver.eligibleFrom(content.relicPool(), held);
    }

    /** The challenges a shrine may charge: legal to hold, and not a trivial one if anything harsher exists. */
    private static List<ModifierDefinition> curseChoices(TowerContent content, PersistedRun run, int floorIndex) {
        List<ModifierDefinition> held = new ArrayList<>();
        for (ResourceLocation id : run.modifiers().accumulated()) content.modifier(id).ifPresent(held::add);
        List<ModifierDefinition> eligible = ModifierResolver.eligibleFrom(
                content.draftablePool(run.towerId(), floorIndex), held);
        List<ModifierDefinition> harsh = eligible.stream().filter(modifier -> modifier.risk() != RiskTier.MINOR).toList();
        return harsh.isEmpty() ? eligible : harsh;
    }
}
