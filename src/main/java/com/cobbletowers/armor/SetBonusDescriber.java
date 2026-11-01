package com.cobbletowers.armor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/**
 * What a set bonus means in words, for tooltips (P25). The one place that turns a {@link SetBonus} into text, run
 * server-side from the real records so datapack retunes are described correctly. Pure.
 */
public final class SetBonusDescriber {

    private static final Map<String, String> ATTRIBUTES = Map.ofEntries(
            Map.entry("max_health", "Max Health"),
            Map.entry("movement_speed", "Movement Speed"),
            Map.entry("luck", "Luck"),
            Map.entry("oxygen_bonus", "Breath"),
            Map.entry("water_movement_efficiency", "Water Movement"),
            Map.entry("attack_damage", "Attack Damage"),
            Map.entry("attack_speed", "Attack Speed"),
            Map.entry("armor", "Armor"),
            Map.entry("armor_toughness", "Armor Toughness"),
            Map.entry("knockback_resistance", "Knockback Resistance"),
            Map.entry("jump_strength", "Jump Strength"),
            Map.entry("step_height", "Step Height"),
            Map.entry("safe_fall_distance", "Safe Fall Distance"),
            Map.entry("submerged_mining_speed", "Underwater Mining"),
            Map.entry("mining_efficiency", "Mining Efficiency"));

    private static final Map<String, String> WEATHERS = Map.of(
            "raindance", "Rain", "sunnyday", "Harsh Sun", "sandstorm", "a Sandstorm", "hail", "Hail", "snowscape", "Snow");
    private static final Map<String, String> TERRAINS = Map.of(
            "electricterrain", "Electric Terrain", "grassyterrain", "Grassy Terrain",
            "mistyterrain", "Misty Terrain", "psychicterrain", "Psychic Terrain");
    private static final Map<String, String> STATS = Map.of(
            "atk", "Attack", "def", "Defense", "spa", "Sp. Atk", "spd", "Sp. Def", "spe", "Speed",
            "accuracy", "Accuracy", "evasion", "Evasiveness");
    private static final Map<String, String> STATUSES = Map.of(
            "brn", "Burned", "par", "Paralyzed", "psn", "Poisoned", "tox", "Badly Poisoned", "slp", "Asleep", "frz", "Frozen");
    private static final Map<String, String> SIDE_CONDITIONS = Map.of(
            "tailwind", "Tailwind", "reflect", "Reflect", "lightscreen", "Light Screen",
            "auroraveil", "Aurora Veil", "safeguard", "Safeguard", "mist", "Mist");

    private SetBonusDescriber() {}

    /** One bonus as one or more lines (a battle bonus can hold several effects). */
    public static List<String> describe(SetBonus bonus) {
        List<String> lines = new ArrayList<>();
        if (bonus instanceof SetBonus.PlayerAttribute attribute) {
            lines.add(attributeLine(attribute));
        } else if (bonus instanceof SetBonus.CobblemonModifier modifier) {
            lines.add(switch (modifier.kind()) {
                case XP_PERCENT -> "+" + modifier.percent() + "% Pok" + "é" + "mon experience";
                case CATCH_RATE_PERCENT -> "+" + modifier.percent() + "% catch rate";
                case SHINY_PERCENT -> "+" + modifier.percent() + "% shiny odds";
            });
        } else if (bonus instanceof SetBonus.TowerModifier modifier) {
            lines.add(switch (modifier.kind()) {
                case VENDOR_DISCOUNT_PERCENT -> modifier.percent() + "% off vendor services";
                case RAID_POINTS_PERCENT -> "+" + modifier.percent() + "% Raid Points";
            });
        } else if (bonus instanceof SetBonus.BattleEffects effects) {
            for (JsonElement effect : effects.effects()) {
                if (effect.isJsonObject()) lines.add(effectLine(effect.getAsJsonObject()));
            }
        }
        return lines;
    }

    // ---- player attributes
    // ------------------------------------------------------------------------------------------

    static String attributeLine(SetBonus.PlayerAttribute bonus) {
        String name = attributeName(bonus.attribute());
        double amount = bonus.amount();
        String sign = amount < 0 ? "-" : "+";
        boolean multiplied = !bonus.operation().equals("add_value");
        String value = multiplied ? number(Math.abs(amount) * 100) + "%" : number(Math.abs(amount));
        return sign + value + " " + name;
    }

    static String attributeName(ResourceLocation attribute) {
        String path = attribute.getPath();
        String leaf = path.substring(path.lastIndexOf('.') + 1);
        String known = ATTRIBUTES.get(leaf);
        return known != null ? known : titleCase(leaf.replace('_', ' '));
    }

    // ---- battle effects
    // ---------------------------------------------------------------------------------------------

    public static String effectLine(JsonObject op) {
        String name = string(op, "op");
        String side = sideOf(op);
        return switch (name) {
            case "weather" -> "Starts the battle in " + WEATHERS.getOrDefault(string(op, "id"), titleCase(string(op, "id")))
                    + duration(op, "for ");
            case "terrain" -> TERRAINS.getOrDefault(string(op, "id"), titleCase(string(op, "id"))) + " at the start of the battle"
                    + duration(op, "for ");
            case "boost" -> leads(side, "start") + " with " + signed(integer(op, "stages")) + " " + STATS.getOrDefault(string(op, "stat"), "stat");
            case "hp" -> leads(side, "start") + " at " + integer(op, "percent") + "% HP";
            case "status" -> leads(side, "start") + " " + STATUSES.getOrDefault(string(op, "status"), "afflicted");
            case "sidecondition" -> sides(side) + (side.equals("both") ? " start" : " starts") + " with " + SIDE_CONDITIONS.getOrDefault(string(op, "id"), titleCase(string(op, "id")))
                    + duration(op, "for ");
            case "damage" -> subject(side) + " " + movesOf(op) + " deal " + moreOrLess(integer(op, "percent")) + " damage";
            case "resist" -> takes(side) + " " + moreOrLess(integer(op, "percent")) + " damage" + fromType(op);
            case "enrage" -> sides(side) + " enrages every " + integer(op, "every") + " turns (+1 Attack and Sp. Atk)";
            case "cap" -> "Relic damage bonuses are capped";
            default -> titleCase(name);
        };
    }

    private static String sideOf(JsonObject op) {
        String side = string(op, "side");
        return side.isEmpty() ? "both" : side;
    }

    /** "Your lead", "The opposing lead", "Both leads". */
    private static String leads(String side, String verb) {
        return switch (side) {
            case "self" -> "Your lead " + verb + (verb.endsWith("s") ? "" : "s");
            case "foe" -> "The opposing lead " + verb + (verb.endsWith("s") ? "" : "s");
            default -> "Both leads " + verb;
        };
    }

    /** "Your side", "The opposing side", "Both sides". */
    private static String sides(String side) {
        return switch (side) {
            case "self" -> "Your side";
            case "foe" -> "The opposing side";
            default -> "Both sides";
        };
    }

    private static String subject(String side) {
        return switch (side) {
            case "self" -> "Your";
            case "foe" -> "The opposing";
            default -> "All";
        };
    }

    private static String takes(String side) {
        return switch (side) {
            case "self" -> "You take";
            case "foe" -> "The opposing side takes";
            default -> "Everyone takes";
        };
    }

    private static String movesOf(JsonObject op) {
        String type = string(op, "type");
        return type.isEmpty() || type.equals("any") ? "moves" : type + " moves";
    }

    private static String fromType(JsonObject op) {
        String type = string(op, "type");
        return type.isEmpty() || type.equals("any") ? "" : " from " + type + " moves";
    }

    /** {@code percent} is a multiplier (120 means +20%, 90 means -10%). */
    private static String moreOrLess(int percent) {
        if (percent == 100) return "unchanged";
        return percent > 100 ? (percent - 100) + "% more" : (100 - percent) + "% less";
    }

    private static String duration(JsonObject op, String lead) {
        int turns = integer(op, "duration");
        return turns > 0 ? " " + lead + turns + (turns == 1 ? " turn" : " turns") : "";
    }

    // ---- small helpers
    // ------------------------------------------------------------------------------------------------

    private static String string(JsonObject op, String key) {
        JsonElement value = op.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static int integer(JsonObject op, String key) {
        JsonElement value = op.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsInt() : 0;
    }

    private static String signed(int n) {
        return (n >= 0 ? "+" : "") + n;
    }

    /** 2.0 -> "2", 0.5 -> "0.5", 12.25 -> "12.25": no trailing zeros, never scientific notation. */
    static String number(double value) {
        if (value == Math.rint(value)) return String.valueOf((long) value);
        String text = String.format(Locale.ROOT, "%.2f", value);
        return text.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    static String titleCase(String text) {
        StringBuilder out = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }
}
