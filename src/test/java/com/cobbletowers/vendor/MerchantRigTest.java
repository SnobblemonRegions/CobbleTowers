package com.cobbletowers.vendor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * The Gilded Merchant's server-side rig: model points sent through {@link MerchantRig#pose} land where the Blockbench geometry says they
 * should, for the client's item-display rendering (it draws {@code M (F v)}, F = a half turn about y, v = (model - 8) / 16).
 */
class MerchantRigTest {

    private static final Path MODELS = Path.of("src/main/resources/assets/cobbletowers/models");
    private final MerchantRig rig = MerchantRig.get();

    /** Where the client puts a part model's point (model units), in blocks relative to the vendor's feet. */
    private static Vector3f clientDraws(Matrix4f m, float[] modelPoint) {
        Vector3f v = new Vector3f((modelPoint[0] - 8) / 16, (modelPoint[1] - 8) / 16, (modelPoint[2] - 8) / 16);
        v.set(-v.x, v.y, -v.z);   // F: the client's own half turn
        return m.transformPosition(v);
    }

    private static float[] fromOf(String model, String element) throws Exception {
        JsonObject json = JsonParser.parseString(Files.readString(MODELS.resolve(model + ".json"))).getAsJsonObject();
        for (JsonElement e : json.getAsJsonArray("elements")) {
            if (e.getAsJsonObject().get("name").getAsString().equals(element)) {
                JsonArray from = e.getAsJsonObject().getAsJsonArray("from");
                return new float[] {from.get(0).getAsFloat(), from.get(1).getAsFloat(), from.get(2).getAsFloat()};
            }
        }
        throw new AssertionError(element + " is not in " + model);
    }

    private MerchantRig.Part part(String name) {
        return rig.parts().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    private static void assertNear(float x, float y, float z, Vector3f got) {
        assertEquals(x, got.x, 1e-3, "x " + got);
        assertEquals(y, got.y, 1e-3, "y " + got);
        assertEquals(z, got.z, 1e-3, "z " + got);
    }

    @Test
    void everyPartHasItsModel() {
        assertFalse(rig.parts().isEmpty());
        for (MerchantRig.Part p : rig.parts()) assertTrue(Files.exists(MODELS.resolve(p.model() + ".json")), p.model());
        assertTrue(rig.parts().stream().anyMatch(MerchantRig.Part::glow), "the eyes and lantern need full-bright parts");
    }

    @Test
    void staffFootLandsAtItsBlockbenchCornerFacingNorth() throws Exception {
        // yaw 180 faces north, the way the model is authored, so nothing turns: Blockbench (5.35, 0, -5.25) units -> blocks
        Map<MerchantRig.Part, Matrix4f> pose = rig.pose(-1, -1, 180f);
        assertNear(5.35f / 16, 0, -5.25f / 16, clientDraws(pose.get(part("staff")), fromOf("gilded_merchant/staff", "staff_ferrule")));
    }

    @Test
    void facingFollowsTheMobsYaw() throws Exception {
        // yaw 0 faces south: the model turns a half circle, so the staff (on his right, +x) moves to -x and its front edge to +z
        Map<MerchantRig.Part, Matrix4f> pose = rig.pose(-1, -1, 0f);
        assertNear(-5.35f / 16, 0, 5.25f / 16, clientDraws(pose.get(part("staff")), fromOf("gilded_merchant/staff", "staff_ferrule")));
    }

    @Test
    void scaleHangsPlumbFromTheBentForearm() throws Exception {
        // forearm bent 80 deg forward, the scale bone -80: the pan keeps its authored offset from the grip, which itself swung forward
        double a = Math.toRadians(80);
        float gripY = (float) (16 - 7.75 * Math.cos(a)), gripZ = (float) (-7.75 * Math.sin(a));
        Map<MerchantRig.Part, Matrix4f> pose = rig.pose(-1, -1, 180f);
        Vector3f pan = clientDraws(pose.get(part("scale")), fromOf("gilded_merchant/scale", "scale_pan_l"));
        assertNear(-10.9f / 16, (gripY + 1.75f - 8.25f) / 16, (gripZ - 1.4f) / 16, pan);
    }

    @Test
    void theBowStartsAndEndsAtRest() {
        Map<MerchantRig.Part, Matrix4f> rest = rig.pose(-1, -1, 180f);
        double length = rig.animation(MerchantRig.GREET).length();
        for (double t : new double[] {0, length}) {
            Map<MerchantRig.Part, Matrix4f> bow = rig.pose(-1, t, 180f);
            for (MerchantRig.Part p : rig.parts()) assertTrue(bow.get(p).equals(rest.get(p), 1e-4f), p.name() + " at " + t);
        }
        Map<MerchantRig.Part, Matrix4f> deep = rig.pose(-1, 0.8, 180f);
        assertFalse(deep.get(part("head")).equals(rest.get(part("head")), 1e-3f), "mid-bow the head has moved");
    }

    @Test
    void idleLoopsSeamlessly() {
        double length = rig.animation(MerchantRig.IDLE).length();
        Map<MerchantRig.Part, Matrix4f> start = rig.pose(0, -1, 90f), end = rig.pose(length, -1, 90f);
        for (MerchantRig.Part p : rig.parts()) assertTrue(start.get(p).equals(end.get(p), 1e-4f), p.name());
    }
}
