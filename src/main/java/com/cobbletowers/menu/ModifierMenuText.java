package com.cobbletowers.menu;

import com.cobbletowers.definition.ModifierDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Codex and card text, written to be read: what the modifier does to the fight, and what the risk pays at the end.
 */
public final class ModifierMenuText {
    private ModifierMenuText() {}
    private static String fieldName(String id){
        return switch(id.toLowerCase(Locale.ROOT)){
            case "raindance"->"Rain";case "sunnyday"->"Harsh sun";case "sandstorm"->"Sandstorm";case "hail"->"Hail";case "snowscape"->"Snowfall";
            case "electricterrain"->"Electric terrain";case "grassyterrain"->"Grassy terrain";case "mistyterrain"->"Misty terrain";case "psychicterrain"->"Psychic terrain";
            default->id;
        };
    }
    /** A line that helps the player. The client draws it green and drops the marker. */
    public static final String GOOD = "[+] ";
    /** A line that hurts the player: drawn red. */
    public static final String BAD = "[-] ";

    /**
     * Whether a battle operation helps or hurts the player, as the marker its line starts with: your own side gaining
     * (boosts, damage, resistance, drain, a side condition) is good, your own side losing, or the foe gaining, is bad.
     */
    static String opTone(com.google.gson.JsonObject op){
        String name=op.has("op")?op.get("op").getAsString():"";String side=op.has("side")?op.get("side").getAsString():"both";
        int stages=op.has("stages")?op.get("stages").getAsInt():0;int percent=op.has("percent")?op.get("percent").getAsInt():100;
        boolean mine=side.equals("self"),theirs=side.equals("foe");
        boolean up=switch(name){
            case "boost"->stages>0;
            case "damage"->percent>100;
            case "resist"->percent<100;
            case "hp"->percent>=100;
            case "drain","sidecondition","enrage"->true;
            case "status"->false;
            default->true;
        };
        if(!mine&&!theirs)return "";
        if(name.equals("status"))return theirs?GOOD:BAD;
        if(name.equals("resist")&&theirs)return up?BAD:GOOD;
        if(name.equals("damage")&&theirs)return up?BAD:GOOD;
        if(name.equals("hp")&&theirs)return up?BAD:GOOD;
        if(name.equals("boost")&&theirs)return up?BAD:GOOD;
        return up?(mine?GOOD:BAD):BAD;
    }

    /** The text without its marker, for anything that cannot colour. */
    public static String plain(String line) {
        return line.startsWith(GOOD) || line.startsWith(BAD) ? line.substring(GOOD.length()) : line;
    }

    /**
     * What a card says, one line per effect. A line starting {@link #GOOD} or {@link #BAD} is a benefit or a cost to the
     * player; the rest is neutral information.
     */
    public static List<String> lines(ModifierDefinition m) {
        List<String> lines=new ArrayList<>();var e=m.effect();
        String risk=m.risk().name().toLowerCase(Locale.ROOT);
        // A relic or set bonus pays no risk bonus (only held challenges do), so it does not claim one.
        boolean pays=!m.relic()&&m.tagBonus().isEmpty();
        if(pays)lines.add(GOOD+Character.toUpperCase(risk.charAt(0))+risk.substring(1)+" risk  *  +"+com.cobbletowers.reward.RiskReward.percentOf(m.risk())+"% to your final payout"+(m.stackLimit()>1?"  *  stacks "+m.stackLimit()+"x":""));
        if(e.levelOffset()>0)lines.add(BAD+"Foes rise "+e.levelOffset()+(e.levelOffset()==1?" level":" levels")+" above the tower.");
        if(e.levelOffset()<0)lines.add(GOOD+"Foes fall "+(-e.levelOffset())+(e.levelOffset()==-1?" level":" levels")+" below the tower.");
        if(e.bossLevelOffset()>0)lines.add(BAD+"The champion stands "+e.bossLevelOffset()+" levels taller.");
        if(e.bossLevelOffset()<0)lines.add(GOOD+"The champion is weakened by "+(-e.bossLevelOffset())+" levels.");
        if(e.extraOpponents()>0)lines.add(BAD+e.extraOpponents()+" more challengers storm every floor.");
        if(e.bossHealthPercent()>100)lines.add(BAD+"The boss endures: "+e.bossHealthPercent()+"% health.");
        if(e.bossHealthPercent()<100)lines.add(GOOD+"The boss is frail: only "+e.bossHealthPercent()+"% health.");
        if(e.rewardPercent()>100)lines.add(GOOD+String.format(Locale.ROOT,"Loot swells to x%.2f on every floor.",e.rewardPercent()/100d));
        if(e.rewardPercent()<100)lines.add(BAD+String.format(Locale.ROOT,"Loot shrinks to x%.2f on every floor.",e.rewardPercent()/100d));
        if(!e.allowSwitching())lines.add(BAD+"No switching: your lead stands or falls.");
        if(!e.allowItems())lines.add(BAD+"No items in battle: trust your team.");
        if(!e.bannedMoves().isEmpty())lines.add(BAD+"Forbidden moves: "+String.join(", ",e.bannedMoves())+".");
        e.weather().ifPresent(v->lines.add("The sky turns: "+fieldName(v)+" in every battle."));
        e.terrain().ifPresent(v->lines.add("The ground shifts: "+fieldName(v)+" in every battle."));
        if(e.scoutingBonus()!=0)lines.add(GOOD+"Your scouts see "+e.scoutingBonus()+" floors further ahead.");
        e.custom().ifPresent(v->{
            switch(v) {
                case "glass_cannon" -> {lines.add(GOOD+"Your side deals 40% more damage.");lines.add(BAD+"Start battles at 60% HP.");}
                case "field_hospital" -> lines.add(GOOD+"The party heals on arrival at each intermission.");
                case "fortunes_wheel" -> {lines.add(GOOD+"Reward gamble: 30% chance of x4.");lines.add(BAD+"Otherwise your rewards are x0.5.");}
                case "black_market" -> lines.add(GOOD+"Vendor prices are halved for the rest of the run.");
                case "swift_start" -> lines.add(GOOD+"Start battles with +1 Speed.");
                case "iron_hide" -> lines.add(GOOD+"You take 8% less damage.");
                case "war_banner" -> lines.add(GOOD+"Your side deals 10% more damage.");
                default -> lines.add("Custom behavior: "+v);
            }
        });
        for(var element:e.battle())if(element.isJsonObject())lines.add(opTone(element.getAsJsonObject())+com.cobbletowers.armor.SetBonusDescriber.effectLine(element.getAsJsonObject())+".");
        m.tagBonus().ifPresent(b->lines.add("Set bonus: while you hold "+b.count()+" or more "+b.tag()+" relics."));
        if(!m.requires().isEmpty())lines.add("Requires: "+String.join(", ",m.requires().stream().map(Object::toString).toList()));
        if(!m.excludes().isEmpty())lines.add("Conflicts: "+String.join(", ",m.excludes().stream().map(Object::toString).toList()));
        if(!m.tags().isEmpty())lines.add("Tags: "+String.join(", ",m.tags()));
        if(pays)lines.add("The risk you carry is paid out when the tower ends.");
        if(m.relic())lines.add("Held for this run; not permanent equipment.");
        return lines;
    }
}
