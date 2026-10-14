package com.thecarlauncher.radio;

import org.junit.Assert;
import org.junit.Test;

public class RadioBrowserApiTest {

    @Test
    public void testUrlSelectionFallback() {
        String urlResolved = "https://resolved.stream.org/live.mp3";
        String urlFallback = "http://fallback.stream.org/live.mp3";

        String selectedUrl = RadioStation.isValidUrl(urlResolved) ? urlResolved : urlFallback;
        Assert.assertEquals("https://resolved.stream.org/live.mp3", selectedUrl);

        String invalidResolved = "invalid_scheme://test";
        selectedUrl = RadioStation.isValidUrl(invalidResolved) ? invalidResolved : urlFallback;
        Assert.assertEquals("http://fallback.stream.org/live.mp3", selectedUrl);
    }
}
