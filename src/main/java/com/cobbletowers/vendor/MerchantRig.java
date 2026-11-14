package com.cobbletowers.vendor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;

/**
 * The Gilded Merchant's skeleton and animations, as data (src/main/resources/cobbletowers/gilded_merchant/rig.json, written by
 * tools/gilded_merchant/build.py), and the maths that turns a moment of an animation into one display transformation per part.
 *
 * <p>No Minecraft classes here, so the maths is unit-tested on its own. Conventions are Blockbench's: units of 1/16 block, the model faces
 * north (-z) with its feet at the origin; bone rest rotations are Euler ZYX degrees in that space; animation samples are Bedrock
 * convention (x and y rotation negated when applied, x position negated), exactly as Blockbench plays them.
 */
public final class MerchantRig {

    public record Bone(String name, String parent, float[] pivot, float[] rest) {}

    public record Part(String name, String bone, boolean glow, String model) {}

    /** One animation sampled every {@code step} seconds; per bone, per channel ("rotation", "position"), one xyz triple per sample. */
    public record Animation(double length, boolean loop, double step, Map<String, Map<String, float[][]>> bones) {}

    public static final String IDLE = "idle";
    public static final String GREET = "greet";

    private final List<Bone> bones;            // parents before children
    private final List<Part> parts;
    private final Map<String, Animation> animations;
    private final float partScale;

    MerchantRig(List<Bone> bones, List<Part> parts, Map<String, Animation> animations, float partScale) {
        this.bones = bones;
        this.parts = parts;
        this.animations = animations;
        this.partScale = partScale;
    }

    public List<Part> parts() {
        return parts;
    }

    public Animation animation(String name) {
        return animations.get(name);
    }

    public Bone bone(String name) {
        for (Bone b : bones) if (b.name().equals(name)) return b;
        throw new IllegalArgumentException("no bone " + name);
    }

    // ---- loading ---------------------------------------------------------------------------------------------------------------------

    private static MerchantRig instance;

    /** The rig in the jar, read once. */
    public static synchronized MerchantRig get() {
        if (instance == null) {
            try (InputStream in = MerchantRig.class.getResourceAsStream("/cobbletowers/gilded_merchant/rig.json")) {
                if (in == null) throw new IllegalStateException("cobbletowers/gilded_merchant/rig.json is missing from the jar");
                instance = parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        return instance;
    }

    static MerchantRig parse(JsonObject json) {
        List<Bone> bones = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray("bones")) {
            JsonObject b = e.getAsJsonObject();
            JsonElement parent = b.get("parent");
            bones.add(new Bone(b.get("name").getAsString(), parent == null || parent.isJsonNull() ? null : parent.getAsString(),
                    floats(b.getAsJsonArray("pivot")), floats(b.getAsJsonArray("rest"))));
        }
        List<Part> parts = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray("parts")) {
            JsonObject p = e.getAsJsonObject();
            parts.add(new Part(p.get("name").getAsString(), p.get("bone").getAsString(), p.get("glow").getAsBoolean(), p.get("model").getAsString()));
        }
        Map<String, Animation> animations = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> a : json.getAsJsonObject("animations").entrySet()) {
            JsonObject o = a.getValue().getAsJsonObject();
            Map<String, Map<String, float[][]>> perBone = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> b : o.getAsJsonObject("bones").entrySet()) {
                Map<String, float[][]> channels = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> c : b.getValue().getAsJsonObject().entrySet()) {
                    JsonArray samples = c.getValue().getAsJsonArray();
                    float[][] out = new float[samples.size()][];
                    for (int i = 0; i < out.length; i++) out[i] = floats(samples.get(i).getAsJsonArray());
                    channels.put(c.getKey(), out);
                }
                perBone.put(b.getKey(), channels);
            }
            animations.put(a.getKey(), new Animation(o.get("length").getAsDouble(), o.get("loop").getAsBoolean(), o.get("step").getAsDouble(), perBone));
        }
        return new MerchantRig(bones, parts, animations, json.get("part_scale").getAsFloat());
    }

    private static float[] floats(JsonArray a) {
        float[] out = new float[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsFloat();
        return out;
    }

    // ---- posing ----------------------------------------------------------------------------------------------------------------------

    /**
     * The transformation to send each part's item display, for idle at {@code idleSeconds} plus, when {@code greetSeconds >= 0}, the bow at
     * that moment layered on top (the bow is authored as an offset that is zero at both ends, so it starts and stops without a pop).
     * A negative {@code idleSeconds} leaves idle out (the rest pose). {@code mobYaw} is the vendor's yaw (0 = south). Each matrix already carries the half turn the client adds to an item display's model.
     */
    public Map<Part, Matrix4f> pose(double idleSeconds, double greetSeconds, float mobYaw) {
        Map<String, float[]> rot = new LinkedHashMap<>();
        Map<String, float[]> pos = new LinkedHashMap<>();
        if (idleSeconds >= 0) accumulate(animations.get(IDLE), idleSeconds, rot, pos);
        if (greetSeconds >= 0) accumulate(animations.get(GREET), greetSeconds, rot, pos);

        Map<String, Matrix4f> world = new LinkedHashMap<>();
        for (Bone b : bones) {
            Matrix4f m = b.parent() == null ? new Matrix4f() : new Matrix4f(world.get(b.parent()));
            float[] p = pos.getOrDefault(b.name(), new float[3]);
            float[] r = rot.getOrDefault(b.name(), new float[3]);
            m.translate(-p[0], p[1], p[2]);
            aboutPivot(m, b.pivot(), b.rest()[0], b.rest()[1], b.rest()[2]);
            aboutPivot(m, b.pivot(), -r[0], -r[1], r[2]);
            world.put(b.name(), m);
        }

        // client draws M (F v): v = model point about (8, 8, 8) in blocks, F = its own half turn about y.
        // A part's model holds its bone's cubes about the pivot at partScale, so x = pivot + (16 / partScale) v (units).
        Matrix4f base = new Matrix4f().rotateY((float) Math.toRadians(180.0 - mobYaw)).scale(1 / 16f);
        Map<Part, Matrix4f> out = new LinkedHashMap<>();
        for (Part part : parts) {
            Bone b = bone(part.bone());
            Matrix4f m = new Matrix4f(base).mul(world.get(b.name()))
                    .translate(b.pivot()[0], b.pivot()[1], b.pivot()[2])
                    .scale(16 / partScale)
                    .rotateY((float) Math.PI);
            out.put(part, m);
        }
        return out;
    }

    /** Euler ZYX (Rz . Ry . Rx, degrees) about a pivot, post-multiplied. */
    private static void aboutPivot(Matrix4f m, float[] p, float x, float y, float z) {
        if (x == 0 && y == 0 && z == 0) return;
        m.translate(p[0], p[1], p[2])
                .rotateZ((float) Math.toRadians(z)).rotateY((float) Math.toRadians(y)).rotateX((float) Math.toRadians(x))
                .translate(-p[0], -p[1], -p[2]);
    }

    private static void accumulate(Animation anim, double seconds, Map<String, float[]> rot, Map<String, float[]> pos) {
        if (anim == null) return;
        for (Map.Entry<String, Map<String, float[][]>> b : anim.bones().entrySet()) {
            for (Map.Entry<String, float[][]> c : b.getValue().entrySet()) {
                float[] v = sample(anim, c.getValue(), seconds);
                float[] into = (c.getKey().equals("position") ? pos : rot).computeIfAbsent(b.getKey(), k -> new float[3]);
                for (int i = 0; i < 3; i++) into[i] += v[i];
            }
        }
    }

    /** Linear between samples; a looping animation wraps, a one-shot holds its last sample. */
    static float[] sample(Animation anim, float[][] samples, double seconds) {
        double t = anim.loop() ? ((seconds % anim.length()) + anim.length()) % anim.length() : Math.max(0, Math.min(anim.length(), seconds));
        double f = t / anim.step();
        int i = Math.min(samples.length - 1, (int) Math.floor(f));
        int j = Math.min(samples.length - 1, i + 1);
        float a = (float) (f - i);
        float[] out = new float[3];
        for (int k = 0; k < 3; k++) out[k] = samples[i][k] + (samples[j][k] - samples[i][k]) * a;
        return out;
    }
}
