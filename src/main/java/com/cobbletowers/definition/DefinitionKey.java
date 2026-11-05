package com.cobbletowers.definition;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * Which file a digest belongs to: its folder as well as its id, since {@code towers/neutral.json} and {@code
 * rulesets/neutral.json} are both {@code cobbletowers:neutral}.
 * @param folder one of towers, floors, rulesets, milestones
 */
public record DefinitionKey(String folder, ResourceLocation id) {

    public DefinitionKey {
        Objects.requireNonNull(folder, "folder");
        Objects.requireNonNull(id, "id");
        if (folder.isBlank()) throw new IllegalArgumentException("folder must not be blank");
    }

    public static DefinitionKey of(String folder, ResourceLocation id) {
        return new DefinitionKey(folder, id);
    }

    /** The folder a tower definition lives in; the one lookup {@code TowerContent.summary} needs. */
    public static DefinitionKey tower(ResourceLocation id) {
        return new DefinitionKey("towers", id);
    }

    @Override
    public String toString() {
        return folder + "/" + id;
    }
}
