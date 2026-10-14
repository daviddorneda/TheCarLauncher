package com.thecarlauncher.radio;

import org.junit.Assert;
import org.junit.Test;

public class RadioStationTest {

    @Test
    public void testFmStationCreationAndSerialization() {
        RadioStation fm = new RadioStation(9220, "Radio Belgrade");

        Assert.assertTrue(fm.isFm());
        Assert.assertFalse(fm.isInternet());
        Assert.assertEquals(9220, fm.freq);
        Assert.assertEquals("Radio Belgrade", fm.name);

        String serialized = fm.serialize();
        Assert.assertEquals("9220|Radio Belgrade", serialized);

        RadioStation restored = RadioStation.deserialize(serialized);
        Assert.assertNotNull(restored);
        Assert.assertTrue(restored.isFm());
        Assert.assertEquals(9220, restored.freq);
        Assert.assertEquals("Radio Belgrade", restored.name);
    }

    @Test
    public void testInternetStationCreationAndSerialization() {
        RadioStation net = new RadioStation("BBC Radio 1", "http://stream.live.vc.bbcmedia.co.uk/bbc_radio_one",
                "https://example.com/logo.png", "https://bbc.co.uk", "uuid-1234", "GB", "pop,news");

        Assert.assertFalse(net.isFm());
        Assert.assertTrue(net.isInternet());
        Assert.assertEquals("BBC Radio 1", net.name);
        Assert.assertEquals("http://stream.live.vc.bbcmedia.co.uk/bbc_radio_one", net.streamUrl);

        String serialized = net.serialize();
        Assert.assertTrue(serialized.startsWith("INT|"));

        RadioStation restored = RadioStation.deserialize(serialized);
        Assert.assertNotNull(restored);
        Assert.assertTrue(restored.isInternet());
        Assert.assertEquals("BBC Radio 1", restored.name);
        Assert.assertEquals("http://stream.live.vc.bbcmedia.co.uk/bbc_radio_one", restored.streamUrl);
        Assert.assertEquals("https://example.com/logo.png", restored.logoUrl);
        Assert.assertEquals("GB", restored.country);
        Assert.assertEquals("pop,news", restored.tags);
    }

    @Test
    public void testUrlValidation() {
        Assert.assertTrue(RadioStation.isValidUrl("http://stream.example.com/radio.mp3"));
        Assert.assertTrue(RadioStation.isValidUrl("https://stream.example.com/live.m3u8"));

        Assert.assertFalse(RadioStation.isValidUrl(null));
        Assert.assertFalse(RadioStation.isValidUrl(""));
        Assert.assertFalse(RadioStation.isValidUrl("ftp://stream.example.com"));
        Assert.assertFalse(RadioStation.isValidUrl("javascript:alert(1)"));
        Assert.assertFalse(RadioStation.isValidUrl("file:///sdcard/music.mp3"));
        Assert.assertFalse(RadioStation.isValidUrl("http://a"));
    }

    @Test
    public void testLegacyFmCompatibility() {
        RadioStation legacy = RadioStation.deserialize("10100|RTS 1");
        Assert.assertNotNull(legacy);
        Assert.assertTrue(legacy.isFm());
        Assert.assertEquals(10100, legacy.freq);
        Assert.assertEquals("RTS 1", legacy.name);
    }
}
