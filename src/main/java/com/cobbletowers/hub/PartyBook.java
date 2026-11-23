package com.cobbletowers.hub;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Who is in which party, and who has invited whom (owner, 20 Nov 2026). No Minecraft types, so every rule is tested on its own:
 * <ul>
 *   <li>Sneak + right-click a player: invites them. Sneak + right-clicking back someone who invited you joins their party.</li>
 *   <li>A leader sneak + right-clicking a member of their own party removes them.</li>
 *   <li>Leaving passes the lead to the next member; a party left with one player is no party.</li>
 *   <li>Players standing on the warp pad together merge into one party ({@link #gather}).</li>
 * </ul>
 */
public final class PartyBook {

    /** An unanswered invite lapses after this long. */
    public static final long INVITE_MILLIS = 60_000L;

    public enum Click { INVITED, REINVITED, JOINED, REMOVED, NOT_LEADER, YOU_ARE_IN_A_PARTY, THEY_ARE_IN_A_PARTY, FULL, SELF }

    /** What a click did, and the party it concerns (null for an invite from someone with no party yet). */
    public record Result(Click click, Party party) {}

    private final Map<UUID, Party> byMember = new HashMap<>();
    /** target -> (inviter -> lapses at) */
    private final Map<UUID, Map<UUID, Long>> invites = new HashMap<>();

    public Optional<Party> partyOf(UUID player) {
        return Optional.ofNullable(byMember.get(player));
    }

    public Collection<Party> parties() {
        return new LinkedHashSet<>(byMember.values());
    }

    public boolean isEmpty() {
        return byMember.isEmpty() && invites.isEmpty();
    }

    /** Whether {@code inviter} has an open invite to {@code target}. */
    public boolean invited(UUID target, UUID inviter, long now) {
        Long until = invites.getOrDefault(target, Map.of()).get(inviter);
        return until != null && now < until;
    }

    /** Everyone {@code inviter} has an open invite out to, oldest first. */
    public List<UUID> invitesBy(UUID inviter, long now) {
        List<UUID> out = new ArrayList<>();
        invites.forEach((target, from) -> {
            Long until = from.get(inviter);
            if (until != null && now < until) out.add(target);
        });
        out.sort((a, b) -> Long.compare(invites.get(a).get(inviter), invites.get(b).get(inviter)));
        return out;
    }

    /** {@code actor} sneak + right-clicked {@code target}. */
    public Result click(UUID actor, UUID target, long now) {
        if (actor.equals(target)) return new Result(Click.SELF, null);
        Party mine = byMember.get(actor);
        Party theirs = byMember.get(target);
        if (mine != null && mine == theirs) {
            if (!mine.leader().equals(actor)) return new Result(Click.NOT_LEADER, mine);
            leave(target);
            return new Result(Click.REMOVED, mine);
        }
        // they asked first: clicking back accepts, unless their invite went stale (they joined someone else's party since)
        if (invited(actor, target, now) && (theirs == null || theirs.leader().equals(target))) {
            if (mine != null) return new Result(Click.YOU_ARE_IN_A_PARTY, mine);
            Party party = theirs != null ? theirs : open(target);
            if (party.full()) return new Result(Click.FULL, party);
            join(party, actor);
            return new Result(Click.JOINED, party);
        }
        if (theirs != null) return new Result(Click.THEY_ARE_IN_A_PARTY, theirs);
        if (mine != null && !mine.leader().equals(actor)) return new Result(Click.NOT_LEADER, mine);
        if (mine != null && mine.full()) return new Result(Click.FULL, mine);
        boolean again = invited(target, actor, now);
        invites.computeIfAbsent(target, id -> new LinkedHashMap<>()).put(actor, now + INVITE_MILLIS);
        return new Result(again ? Click.REINVITED : Click.INVITED, mine);
    }

    /** Takes the player out of their party (the next member leads); returns the party they were in, if any. */
    public Optional<Party> leave(UUID player) {
        Party party = byMember.remove(player);
        if (party == null) return Optional.empty();
        party.remove(player);
        if (party.size() < 2) {
            for (UUID rest : party.members()) byMember.remove(rest);
            for (UUID rest : party.members()) party.remove(rest);
        }
        return Optional.of(party);
    }

    /** Whether a party has been emptied (by {@link #leave}): it is gone, and anyone holding it should let go. */
    public static boolean dissolved(Party party) {
        return party.size() == 0;
    }

    /**
     * Players who stood on the warp pad together merge into one party: the party with the most of them standing there takes in the rest
     * who have none, while it has room; with no party among them, two or more partyless players start one (the first to arrive leads).
     * Players in a different party are left alone. Returns who joined, in order.
     */
    public List<UUID> gather(List<UUID> onPad, long now) {
        Party host = null;
        int best = 0;
        for (UUID id : onPad) {
            Party party = byMember.get(id);
            if (party == null || party.full()) continue;
            int here = (int) onPad.stream().filter(party::contains).count();
            if (here > best) {
                best = here;
                host = party;
            }
        }
        List<UUID> free = onPad.stream().filter(id -> !byMember.containsKey(id)).toList();
        List<UUID> joined = new ArrayList<>();
        int from = 0;
        if (host == null) {
            if (free.size() < 2) return joined;
            host = open(free.get(0));
            joined.add(free.get(0));
            from = 1;
        }
        for (int i = from; i < free.size() && !host.full(); i++) {
            join(host, free.get(i));
            joined.add(free.get(i));
        }
        return joined;
    }

    /** Drops lapsed invites; returns (target, inviter) pairs that lapsed. */
    public List<UUID[]> expire(long now) {
        List<UUID[]> lapsed = new ArrayList<>();
        invites.forEach((target, from) -> from.entrySet().removeIf(e -> {
            if (now < e.getValue()) return false;
            lapsed.add(new UUID[]{target, e.getKey()});
            return true;
        }));
        invites.values().removeIf(Map::isEmpty);
        return lapsed;
    }

    /** Forgets every invite to or from the player (they logged out, or joined a party). */
    public void forgetInvites(UUID player) {
        invites.remove(player);
        invites.values().forEach(from -> from.remove(player));
        invites.values().removeIf(Map::isEmpty);
    }

    public void clear() {
        byMember.clear();
        invites.clear();
    }

    private Party open(UUID leader) {
        Party party = new Party(leader);
        byMember.put(leader, party);
        forgetInvites(leader);
        return party;
    }

    private void join(Party party, UUID player) {
        party.add(player);
        byMember.put(player, party);
        forgetInvites(player);
    }
}
