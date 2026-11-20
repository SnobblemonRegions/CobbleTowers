package com.cobbletowers.club;

import com.cobbletowers.ServerState;
import com.cobbletowers.TowerLog;
import com.cobbletowers.club.ClubBook.Club;
import com.cobbletowers.club.ClubBook.Result;
import com.cobbletowers.definition.TowerDefinition;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.persistence.TowerClubStore;
import com.cobbletowers.persistence.TowerWalletStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Clubs at run time (P35): player actions, invites and the hook that counts a regional cycle clear. Rules are in
 * {@link ClubBook}. Regional towers only; Neutral and the Test tower never count.
 */
public final class ClubService {

    /** How long an invite stays open. */
    static final long INVITE_TTL_MILLIS = 5 * 60 * 1000L;

    private record Invite(String club, long expiresAt) {}

    private static final Map<UUID, Invite> INVITES = new HashMap<>();

    static {
        ServerState.onStop(INVITES::clear);
    }

    private ClubService() {}

    /**
     * The week a clear belongs to, as {@code 2026-w41}: the same trial week trials and contracts use (configured zone
     * and reset hour).
     */
    public static String weekKey() {
        return com.cobbletowers.trial.TrialClock.weekKey(com.cobbletowers.trial.TrialService.today());
    }

    public static void clear() {
        INVITES.clear();
    }

    static boolean regional(ResourceLocation towerId) {
        TowerDefinition tower = TowerDefinitionRegistry.content().towers().get(towerId);
        return tower != null && tower.regionalTheme().isPresent();
    }

    // ---- the hook ----------------------------------------------------------------------------------

    /** A regional cycle was cleared by these players with this difficulty score. */
    public static void onCycleCleared(MinecraftServer server, ResourceLocation tower, List<UUID> players, int score) {
        if (!regional(tower)) return;
        TowerClubStore store = TowerClubStore.get(server);
        ClubBook book = store.book();
        for (UUID player : players) {
            boolean goalMet = book.recordClear(player, score, weekKey(), com.cobbletowers.season.Seasons.activeNumber().orElse(0));
            if (goalMet) {
                book.clubOf(player).ifPresent(club -> {
                    tell(server, club, "Your club " + club.name() + " met its weekly goal! Every member can claim "
                            + ClubBook.WEEKLY_REWARD + " Tower Tokens on the Club page of the Hall.");
                    TowerLog.info("Club {} met its weekly goal ({})", club.name(), weekKey());
                });
            }
        }
        store.changed();
        store.checkpoint(server);
    }

    // ---- actions -----------------------------------------------------------------------------------

    public static List<String> info(MinecraftServer server, ServerPlayer player) {
        ClubBook book = TowerClubStore.get(server).book();
        Optional<Club> found = book.clubOf(player.getUUID());
        if (found.isEmpty()) {
            return List.of("You are not in a club. Start one on the Club page of the Hall, or ask a member to invite you.",
                    "Your best regional clear: " + book.bestOf(player.getUUID()) + ". Clubs count regional towers only.");
        }
        Club club = found.get();
        List<String> lines = new ArrayList<>();
        java.util.Optional<Integer> season = com.cobbletowers.season.Seasons.viewNumber();
        lines.add("[" + club.tag() + "] " + club.name() + " (" + club.banner() + " banner) -- "
                + (season.isPresent() ? "season " + season.get() + " score " + book.seasonScore(club, season.get()) + ", all-time " : "score ")
                + book.score(club) + ", " + club.members().size() + "/" + ClubBook.MAX_MEMBERS + " members");
        if (!club.honors().isEmpty()) lines.add("Honours: " + String.join("; ", club.honors())
                + (club.unlockedBanners().isEmpty() ? "" : " (banners unlocked: " + String.join(", ", new java.util.TreeSet<>(club.unlockedBanners())) + ")"));
        lines.add("Weekly goal: " + Math.min(book.weekClears(club, weekKey()), ClubBook.WEEKLY_GOAL) + "/" + ClubBook.WEEKLY_GOAL
                + " regional cycle clears (" + weekKey() + "); "
                + (book.weekClears(club, weekKey()) >= ClubBook.WEEKLY_GOAL
                        ? (club.claimed().contains(player.getUUID()) ? "you have claimed this week's reward"
                                : "met! Claim it on the Club page: pays " + ClubBook.WEEKLY_REWARD + " Tower Tokens")
                        : "pays " + ClubBook.WEEKLY_REWARD + " Tower Tokens to every member when met"));
        StringBuilder members = new StringBuilder("Members: ");
        for (Map.Entry<UUID, String> member : club.members().entrySet()) {
            members.append(member.getValue()).append(member.getKey().equals(club.owner()) ? " (owner)" : "")
                    .append(" ").append(book.bestOf(member.getKey())).append(", ");
        }
        lines.add(members.substring(0, members.length() - 2));
        return lines;
    }

    public static List<String> top(MinecraftServer server) {
        return top(server, false);
    }

    /** The club board: the season's by default while a season is on view, or the all-time one on request. */
    public static List<String> top(MinecraftServer server, boolean allTime) {
        ClubBook book = TowerClubStore.get(server).book();
        java.util.Optional<Integer> season = allTime ? java.util.Optional.empty() : com.cobbletowers.season.Seasons.viewNumber();
        List<String> lines = new ArrayList<>();
        lines.add(season.isPresent()
                ? "Club board, season " + season.get() + " (the sum of each member's best regional clear this season; the Club page has the lifetime board):"
                : "Club board, all-time (the sum of each member's best regional clear):");
        int rank = 1;
        List<Club> ranked = season.isPresent() ? book.topForSeason(season.get(), 10) : book.top(10);
        for (Club club : ranked) {
            int score = season.isPresent() ? book.seasonScore(club, season.get()) : book.score(club);
            lines.add(rank++ + ". [" + club.tag() + "] " + club.name() + " -- " + score + " (" + club.members().size() + " members)"
                    + (club.honors().isEmpty() ? "" : " *"));
        }
        if (rank == 1) lines.add("No club has scored yet.");
        return lines;
    }

    /** The season's club board as the Hall freezes it: the top ten, best first. */
    public static List<com.cobbletowers.season.HallSeason.Club> hallClubs(MinecraftServer server, int season) {
        ClubBook book = TowerClubStore.get(server).book();
        List<com.cobbletowers.season.HallSeason.Club> frozen = new ArrayList<>();
        for (Club club : book.topForSeason(season, 10)) {
            frozen.add(new com.cobbletowers.season.HallSeason.Club(club.name(), club.tag(), club.banner(), book.seasonScore(club, season),
                    new ArrayList<>(club.members().values())));
        }
        return frozen;
    }

    /**
     * The end of a season for clubs (P36c): the top three clubs as the Hall froze them unlock a place banner and a
     * line of honour, and every member keeps a permanent mark. Idempotent. Older per-player season bests are then
     * forgotten.
     */
    public static void awardSeason(MinecraftServer server, int season, List<String> podium) {
        TowerClubStore store = TowerClubStore.get(server);
        com.cobbletowers.persistence.TowerSeasonProgressStore marks = com.cobbletowers.persistence.TowerSeasonProgressStore.get(server);
        String[] places = {"champion", "second place", "third place"};
        for (int rank = 0; rank < Math.min(podium.size(), ClubBook.PODIUM); rank++) {
            int place = rank;
            store.book().find(podium.get(rank)).ifPresent(club -> {
                String banner = ClubBook.PRESTIGE_BANNERS.get(place);
                store.book().honor(club, banner, "Season " + season + ": " + places[place]);
                for (UUID member : club.memberIds()) {
                    com.cobbletowers.season.CosmeticsService.award(server, member, java.util.Set.of("s" + season + ":club_" + banner));
                }
                TowerLog.info("Club {} finished season {} in {} place and unlocks the {} banner", club.name(), season, places[place], banner);
            });
        }
        store.book().pruneSeasonBests(season);
        store.changed();
        store.checkpoint(server);
        marks.checkpoint(server);
    }

    public static String create(MinecraftServer server, ServerPlayer player, String name, String tag) {
        TowerClubStore store = TowerClubStore.get(server);
        String useTag = tag == null || tag.isBlank() ? name.substring(0, Math.min(ClubBook.MAX_TAG, name.length())) : tag;
        Result result = store.book().create(player.getUUID(), player.getGameProfile().getName(), name, useTag,
                System.currentTimeMillis());
        if (result == Result.OK) {
            store.changed();
            store.checkpoint(server);
            com.cobbletowers.season.ChatTags.refresh(server, player.getUUID());
            return "Club " + name + " [" + useTag.toUpperCase(Locale.ROOT) + "] created. Invite people from the Club page.";
        }
        return describe(result);
    }

    public static String invite(MinecraftServer server, ServerPlayer host, ServerPlayer target) {
        ClubBook book = TowerClubStore.get(server).book();
        Optional<Club> club = book.clubOf(host.getUUID());
        if (club.isEmpty()) return describe(Result.NOT_IN_CLUB);
        if (!club.get().owner().equals(host.getUUID())) return describe(Result.NOT_OWNER);
        if (club.get().members().size() >= ClubBook.MAX_MEMBERS) return describe(Result.FULL);
        if (book.clubOf(target.getUUID()).isPresent()) return target.getGameProfile().getName() + " is already in a club.";
        INVITES.put(target.getUUID(), new Invite(club.get().name(), System.currentTimeMillis() + INVITE_TTL_MILLIS));
        target.sendSystemMessage(Component.literal(host.getGameProfile().getName() + " invited you to the club "
                + club.get().name() + ". Accept on the Club page to join (open 5 minutes)."));
        return "Invited " + target.getGameProfile().getName() + " to " + club.get().name() + ".";
    }

    public static String accept(MinecraftServer server, ServerPlayer player, String clubName) {
        Invite invite = INVITES.get(player.getUUID());
        if (invite == null || invite.expiresAt() < System.currentTimeMillis() || !invite.club().equalsIgnoreCase(clubName)) {
            INVITES.remove(player.getUUID());
            return "You have no open invite to " + clubName + ".";
        }
        TowerClubStore store = TowerClubStore.get(server);
        Result result = store.book().join(player.getUUID(), player.getGameProfile().getName(), clubName);
        if (result != Result.OK) return describe(result);
        INVITES.remove(player.getUUID());
        store.changed();
        store.checkpoint(server);
        com.cobbletowers.season.ChatTags.refresh(server, player.getUUID());
        store.book().find(clubName).ifPresent(club -> tell(server, club, player.getGameProfile().getName() + " joined the club."));
        return "You joined " + clubName + ".";
    }

    public static String leave(MinecraftServer server, ServerPlayer player) {
        TowerClubStore store = TowerClubStore.get(server);
        Optional<Club> club = store.book().clubOf(player.getUUID());
        Result result = store.book().leave(player.getUUID());
        if (result == Result.OK) {
            store.changed();
            store.checkpoint(server);
            com.cobbletowers.season.ChatTags.refresh(server, player.getUUID());
            club.ifPresent(c -> tell(server, c, player.getGameProfile().getName() + " left the club."));
            return "You left the club.";
        }
        return describe(result);
    }

    public static String disband(MinecraftServer server, ServerPlayer player) {
        TowerClubStore store = TowerClubStore.get(server);
        List<UUID> members = store.book().clubOf(player.getUUID()).map(Club::memberIds).orElse(List.of());
        Result result = store.book().disband(player.getUUID());
        if (result == Result.OK) {
            for (UUID member : members) com.cobbletowers.season.ChatTags.refresh(server, member);
            store.changed();
            store.checkpoint(server);
            return "Your club was disbanded.";
        }
        return describe(result);
    }

    public static String kick(MinecraftServer server, ServerPlayer owner, String name) {
        TowerClubStore store = TowerClubStore.get(server);
        Optional<Club> club = store.book().clubOf(owner.getUUID());
        if (club.isEmpty()) return describe(Result.NOT_IN_CLUB);
        Optional<UUID> target = club.get().members().entrySet().stream()
                .filter(member -> member.getValue().equalsIgnoreCase(name)).map(Map.Entry::getKey).findFirst();
        if (target.isEmpty()) return name + " is not in your club.";
        Result result = store.book().kick(owner.getUUID(), target.get());
        if (result == Result.OK) {
            com.cobbletowers.season.ChatTags.refresh(server, target.get());
            store.changed();
            store.checkpoint(server);
            return "Removed " + name + " from the club.";
        }
        return describe(result);
    }

    public static String banner(MinecraftServer server, ServerPlayer owner, String color) {
        TowerClubStore store = TowerClubStore.get(server);
        Result result = store.book().setBanner(owner.getUUID(), color);
        if (result == Result.OK) {
            store.book().clubOf(owner.getUUID()).ifPresent(club -> {
                for (UUID member : club.memberIds()) com.cobbletowers.season.ChatTags.refresh(server, member);
            });
            store.changed();
            store.checkpoint(server);
            return "Banner set to " + color + ".";
        }
        java.util.Set<String> earned = store.book().clubOf(owner.getUUID()).map(Club::unlockedBanners).orElse(java.util.Set.of());
        return describe(result) + (result == Result.BAD_BANNER ? " Try: " + String.join(", ", ClubBook.BANNERS)
                + (earned.isEmpty() ? "" : ", " + String.join(", ", new java.util.TreeSet<>(earned))) + "." : "");
    }

    public static String claim(MinecraftServer server, ServerPlayer player) {
        TowerClubStore store = TowerClubStore.get(server);
        Result result = store.book().claim(player.getUUID(), weekKey());
        if (result == Result.OK) {
            TowerWalletStore.get(server).credit(player.getUUID(), ClubBook.WEEKLY_REWARD);
            com.cobbletowers.season.SeasonProgressService.award(server, player.getUUID(),
                    com.cobbletowers.season.SeasonPoints.Source.CLUB_CLAIM, 0, false);
            store.changed();
            store.checkpoint(server);
            return "Weekly club reward claimed: +" + ClubBook.WEEKLY_REWARD + " Tower Tokens.";
        }
        return describe(result);
    }

    static String describe(Result result) {
        return switch (result) {
            case OK -> "Done.";
            case BAD_NAME -> "A club name is " + ClubBook.MIN_NAME + "-" + ClubBook.MAX_NAME + " letters, digits or underscores.";
            case BAD_TAG -> "A club tag is " + ClubBook.MIN_TAG + "-" + ClubBook.MAX_TAG + " letters or digits.";
            case NAME_TAKEN -> "That club name is taken.";
            case ALREADY_IN_CLUB -> "You are already in a club. Leave it first, on the Club page.";
            case NOT_IN_CLUB -> "You are not in a club.";
            case NOT_OWNER -> "Only the club's owner can do that.";
            case FULL -> "That club is full (" + ClubBook.MAX_MEMBERS + " members).";
            case NO_SUCH_CLUB -> "There is no such club.";
            case BAD_BANNER -> "That is not a banner colour.";
            case NOT_A_MEMBER -> "That player is not a member you can remove.";
            case GOAL_NOT_MET -> "Your club has not met this week's goal yet.";
            case ALREADY_CLAIMED -> "You already claimed this week's reward.";
        };
    }

    private static void tell(MinecraftServer server, Club club, String message) {
        for (UUID id : club.memberIds()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) player.sendSystemMessage(Component.literal("[" + club.tag() + "] " + message));
        }
    }
}
