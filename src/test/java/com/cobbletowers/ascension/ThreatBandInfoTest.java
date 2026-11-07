package com.cobbletowers.ascension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cobbletowers.ascension.ThreatBandInfo.Band;
import com.cobbletowers.network.PlayStatePayload;
import com.cobbletowers.webgui.PayloadJson;
import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

/** The Threat picker's band table: read from AscensionLib's answer without ever throwing, sent on the wire and to the page unchanged. */
class ThreatBandInfoTest {

    /** What AscensionLib's {@code threatBands()} answers for Threat III and IV (the documented shape). */
    private static final String THREE = "{\"band\":3,\"mix\":[{\"rarity\":\"rare\",\"percent\":60},{\"rarity\":\"epic\",\"percent\":40}],\"credits\":5,\"bossCredits\":7,"
            + "\"unique\":false,\"bossUnique\":false,\"dust\":250,\"facets\":7,\"corePercent\":56,\"apex\":0.3}";
    private static final String FOUR = "{\"band\":4,\"mix\":[{\"rarity\":\"epic\",\"percent\":70},{\"rarity\":\"legendary\",\"percent\":30}],\"credits\":7,\"bossCredits\":9,"
            + "\"unique\":false,\"bossUnique\":false,\"dust\":325,\"facets\":8,\"corePercent\":68,\"apex\":0.9}";

    private static String array(String... bands) {
        return "[" + String.join(",", bands) + "]";
    }

    private static List<Integer> numbers(List<Band> bands) {
        return bands.stream().map(Band::band).toList();
    }

    @Test
    void readsTheDocumentedShape() {
        List<Band> bands = ThreatBandInfo.parse(array(THREE, FOUR));
        assertEquals(List.of(3, 4), numbers(bands));
        Band three = bands.get(0);
        assertEquals(60, three.mix().get(0).percent());
        assertEquals("epic", three.mix().get(1).rarity());
        assertEquals(5, three.credits());
        assertEquals(7, three.bossCredits());
        assertEquals(250, three.dust());
        assertEquals(7, three.facets());
        assertEquals(56, three.corePercent());
        assertEquals(0.3, three.apex(), 1e-9);
    }

    @Test
    void bandsComeOutLowestFirstWhateverTheOrder() {
        assertEquals(List.of(3, 4), numbers(ThreatBandInfo.parse(array(FOUR, THREE))));
    }

    @Test
    void anythingElseGivesNoBandsAndNeverThrows() {
        for (String bad : new String[] {null, "", "  ", "not json", "{}", "[1,2]", "[{}]", "{\"band\":3}", "[null]"}) {
            assertTrue(ThreatBandInfo.parse(bad).isEmpty(), String.valueOf(bad));
        }
    }

    @Test
    void aBandThatIsNotTheDocumentedShapeIsLeftOutAndTheRestStay() {
        assertEquals(List.of(4), numbers(ThreatBandInfo.parse(array(THREE.replace("\"band\":3", "\"band\":9"), FOUR))), "band 9 is outside 1-7");
        assertEquals(List.of(4), numbers(ThreatBandInfo.parse(array(THREE.replace("\"percent\":40", "\"percent\":35"), FOUR))), "percents must add to 100");
        assertEquals(List.of(4), numbers(ThreatBandInfo.parse(array(THREE.replace("\"dust\":250", "\"dust\":-1"), FOUR))), "no negative payout");
        assertEquals(List.of(4), numbers(ThreatBandInfo.parse(array(THREE.replace("\"corePercent\":56", "\"corePercent\":101"), FOUR))), "a chance is at most 100");
        assertEquals(List.of(4), numbers(ThreatBandInfo.parse(array(THREE.replaceAll("\"mix\":\\[.*?\\]", "\"mix\":[]"), FOUR))), "a band with no enemies is left out");
        assertEquals(List.of(4), numbers(ThreatBandInfo.parse(array(THREE.replace(",\"apex\":0.3", ""), FOUR))), "a missing field leaves the band out");
    }

    @Test
    void noMoreThanSevenBandsAreRead() {
        String[] nine = new String[9];
        for (int i = 0; i < nine.length; i++) nine[i] = FOUR.replace("\"band\":4", "\"band\":" + (i % 7 + 1));
        assertTrue(ThreatBandInfo.parse(array(nine)).size() <= ThreatBandInfo.MAX_BANDS);
    }

    @Test
    void theSameAnswerIsParsedOnce() {
        String json = array(THREE, FOUR);
        assertTrue(ThreatBandInfo.cached(json) == ThreatBandInfo.cached(json), "the second call returns the remembered list");
        assertTrue(ThreatBandInfo.cached(null).isEmpty());
    }

    @Test
    void theNetworkCodecRoundTrips() {
        for (Band b : ThreatBandInfo.parse(array(THREE, FOUR))) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            Band.STREAM_CODEC.encode(buf, b);
            assertEquals(b, Band.STREAM_CODEC.decode(buf));
        }
    }

    @Test
    void thePageSeesTheBandsInsideThreat() {
        PlayStatePayload.Threat threat = new PlayStatePayload.Threat(3, 3, true, ThreatBandInfo.parse(array(THREE, FOUR)));
        JsonObject json = PayloadJson.toJson(threat).getAsJsonObject();
        assertEquals(3, json.get("chosen").getAsInt());
        var bands = json.getAsJsonArray("bands");
        assertEquals(2, bands.size());
        JsonObject first = bands.get(0).getAsJsonObject();
        assertEquals(3, first.get("band").getAsInt());
        assertEquals("rare", first.getAsJsonArray("mix").get(0).getAsJsonObject().get("rarity").getAsString());
        assertEquals(250, first.get("dust").getAsInt());
        assertEquals(0, PayloadJson.toJson(PlayStatePayload.Threat.none()).getAsJsonObject().getAsJsonArray("bands").size());
    }
}
