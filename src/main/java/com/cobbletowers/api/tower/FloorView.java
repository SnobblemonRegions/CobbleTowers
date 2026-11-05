package com.cobbletowers.api.tower;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/** One floor of a tower, as an addon may read it. */
public interface FloorView {

    /** 1-based, and contiguous within a tower. */
    int index();

    /** Present on a milestone floor only. */
    Optional<MilestoneKind> milestone();
}
