package com.cobbletowers.hub;

import com.cobbletowers.lobby.TowerLobby;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A party at the Tower Hub (owner, 20 Nov 2026): who is going in together, before any tower is chosen. The first member leads; when the
 * leader leaves, the next one does. The leader's Threat pick (stepped on a gate's Threat pedestal) travels with the party to whichever
 * gate it gathers at; the Ascension is voted at the gate. In memory only: a restart forgets parties, as it forgets lobbies.
 */
public final class Party {

    public static final int MAX = TowerLobby.MAX_PLAYERS;

    private final List<UUID> members = new ArrayList<>();
    private int threat;
    /** The mode the leader chose on a gate's mode board: a playlist's id path ("hardcore"...), or "standard". */
    private String mode = "standard";

    Party(UUID leader) {
        members.add(leader);
    }

    public UUID leader() {
        return members.get(0);
    }

    /** Leader first, then in the order they joined. */
    public List<UUID> members() {
        return List.copyOf(members);
    }

    public boolean contains(UUID player) {
        return members.contains(player);
    }

    public int size() {
        return members.size();
    }

    public boolean full() {
        return members.size() >= MAX;
    }

    public int threat() {
        return threat;
    }

    void setThreat(int value) {
        threat = value;
    }

    public String mode() {
        return mode;
    }

    void setMode(String value) {
        mode = value;
    }

    void add(UUID player) {
        if (!members.contains(player)) members.add(player);
    }

    void remove(UUID player) {
        members.remove(player);
    }
}
