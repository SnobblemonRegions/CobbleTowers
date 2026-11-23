package com.cobbletowers.hub;

import com.cobbletowers.definition.PlaylistDefinition;
import com.cobbletowers.definition.PlaylistRegistry;
import com.cobbletowers.definition.TowerDefinition;
import com.cobbletowers.definition.TowerDefinitionRegistry;
import com.cobbletowers.definition.TrialPoolDefinition;
import com.cobbletowers.trial.TrialClock;
import com.cobbletowers.trial.TrialSchedule;
import com.cobbletowers.trial.TrialService;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment;
import eu.pb4.polymer.virtualentity.api.elements.TextDisplayElement;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * The plate over a trial shrine (owner, 20 Nov 2026): which trial it is today, its tower, mode, modifiers and floors, how long until it
 * resets, and the rule of the attempt. Refreshed every second (the countdown); text displays only, never saved.
 */
final class TrialPlate extends ElementHolder {

    private final TextDisplayElement kind, label, details, reset, rule;
    private final boolean daily;
    private String shown = "";

    TrialPlate(ServerLevel level, Vec3 at, boolean daily) {
        this.daily = daily;
        int colour = daily ? 0xF2C744 : 0xC58CFF;
        kind = line(2.15, 0.9f, Component.literal(daily ? "DAILY TRIAL" : "WEEKLY TRIAL").withColor(colour));
        label = line(1.55, 1.6f, Component.empty());
        details = line(1.15, 0.75f, Component.empty());
        reset = line(0.8, 0.75f, Component.empty());
        rule = line(0.5, 0.6f, Component.literal("One scored attempt each; later runs are practice").withColor(0xA0A6B4));
        ChunkAttachment.ofTicking(this, level, at);
    }

    private TextDisplayElement line(double y, float scale, Component text) {
        TextDisplayElement t = new TextDisplayElement(text);
        t.setBillboardMode(Display.BillboardConstraints.CENTER);
        t.setTextAlignment(Display.TextDisplay.Align.CENTER);
        t.setLineWidth(360);
        t.setBackground(0xC8080710);
        t.setShadow(true);
        t.setBrightness(new Brightness(15, 15));
        t.setViewRange(1.5f);
        t.setScale(new Vector3f(scale, scale, scale));
        t.setOffset(new Vec3(0, y, 0));
        return addElement(t);
    }

    void refresh(TrialPoolDefinition.Kind which) {
        Optional<TrialSchedule.Instance> found = TrialService.current(which);
        String l, d;
        if (found.isEmpty()) {
            l = "No trial today";
            d = "This server has no " + (daily ? "daily" : "weekly") + " trial set up";
        } else {
            TrialSchedule.Instance t = found.get();
            TowerDefinition tower = TowerDefinitionRegistry.content().towers().get(t.entry().tower());
            String mode = t.entry().playlist().flatMap(PlaylistRegistry::get).map(PlaylistDefinition::displayName).orElse("Standard");
            String mods = t.entry().modifiers().stream().map(id -> pretty(id.getPath())).collect(Collectors.joining(", "));
            l = t.title();
            d = (tower == null ? t.entry().tower().getPath() : tower.displayName()) + "  ·  " + mode + (mods.isEmpty() ? "" : "  ·  " + mods)
                    + "  ·  " + t.floors() + " floors";
        }
        long left = TrialService.millisUntilReset();
        if (!daily) {
            java.time.LocalDate today = TrialService.today();
            long days = 6 - java.time.temporal.ChronoUnit.DAYS.between(TrialClock.weekStart(today), today);
            left += days * 86_400_000L;
        }
        String r = "Resets in " + TrialClock.describe(left);
        String key = l + "|" + d + "|" + r;
        if (key.equals(shown)) return;
        shown = key;
        label.setText(Component.literal(l).withColor(0xFFFFFF));
        details.setText(Component.literal(d).withColor(0xDCE0EC));
        reset.setText(Component.literal(r).withColor(0x96E6A0));
    }

    private static String pretty(String path) {
        String s = path.replace('_', ' ');
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
