package com.thecarlauncher.radio;

import java.util.Locale;

/**
 * Model representing either an FM radio station or an Internet radio stream.
 */
public final class RadioStation {
    public static final int TYPE_FM = 0;
    public static final int TYPE_INTERNET = 1;

    public final int type;
    public final int freq;            // FM frequency in MCU units (e.g. 9220 = 92.20 MHz)
    public final String name;        // Station display name
    public final String streamUrl;   // Internet stream URL (HTTP/HTTPS)
    public final String logoUrl;     // Favicon / logo URL
    public final String homepage;    // Optional station website
    public final String stationUuid; // Optional Radio Browser UUID
    public final String country;     // Optional country code/name
    public final String tags;        // Optional tags / genre

    /** FM station constructor */
    public RadioStation(int freq, String name) {
        this.type = TYPE_FM;
        this.freq = freq;
        this.name = name == null ? "" : name.trim();
        this.streamUrl = "";
        this.logoUrl = "";
        this.homepage = "";
        this.stationUuid = "";
        this.country = "";
        this.tags = "";
    }

    /** Internet station constructor */
    public RadioStation(String name, String streamUrl, String logoUrl, String homepage,
                        String stationUuid, String country, String tags) {
        this.type = TYPE_INTERNET;
        this.freq = 0;
        this.name = name == null ? "" : name.trim();
        this.streamUrl = streamUrl == null ? "" : streamUrl.trim();
        this.logoUrl = logoUrl == null ? "" : logoUrl.trim();
        this.homepage = homepage == null ? "" : homepage.trim();
        this.stationUuid = stationUuid == null ? "" : stationUuid.trim();
        this.country = country == null ? "" : country.trim();
        this.tags = tags == null ? "" : tags.trim();
    }

    /** Full constructor for deserialization */
    public RadioStation(int type, int freq, String name, String streamUrl, String logoUrl,
                        String homepage, String stationUuid, String country, String tags) {
        this.type = type;
        this.freq = freq;
        this.name = name == null ? "" : name.trim();
        this.streamUrl = streamUrl == null ? "" : streamUrl.trim();
        this.logoUrl = logoUrl == null ? "" : logoUrl.trim();
        this.homepage = homepage == null ? "" : homepage.trim();
        this.stationUuid = stationUuid == null ? "" : stationUuid.trim();
        this.country = country == null ? "" : country.trim();
        this.tags = tags == null ? "" : tags.trim();
    }

    public boolean isFm() {
        return type == TYPE_FM;
    }

    public boolean isInternet() {
        return type == TYPE_INTERNET;
    }

    public String getDisplayName() {
        if (name.length() > 0) return name;
        if (isFm()) return "FM " + RadioController.format(freq) + " MHz";
        if (streamUrl.length() > 0) return streamUrl;
        return "Radio";
    }

    /** Validates whether a URL uses http:// or https:// scheme and has valid host length */
    public static boolean isValidUrl(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(Locale.ROOT);
        if (u.startsWith("http://")) return u.length() >= 10;   // e.g. http://a.b
        if (u.startsWith("https://")) return u.length() >= 11;  // e.g. https://a.b
        return false;
    }


    /** Converts legacy FM Station instance */
    public static RadioStation fromFmStation(RadioController.Station fm) {
        if (fm == null) return null;
        return new RadioStation(fm.freq, fm.name);
    }

    /** Serializes a station to a String for storage in Prefs */
    public String serialize() {
        if (isFm()) {
            return freq + "|" + name.replace(";", " ").replace("|", " ");
        } else {
            // Internet format: INT|name|streamUrl|logoUrl|homepage|uuid|country|tags
            return "INT|"
                    + sanitize(name) + "|"
                    + sanitize(streamUrl) + "|"
                    + sanitize(logoUrl) + "|"
                    + sanitize(homepage) + "|"
                    + sanitize(stationUuid) + "|"
                    + sanitize(country) + "|"
                    + sanitize(tags);
        }
    }

    /** Deserializes a stored station String from Prefs */
    public static RadioStation deserialize(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        raw = raw.trim();
        if (raw.startsWith("INT|")) {
            String[] parts = raw.split("\\|", -1);
            String name = parts.length > 1 ? parts[1] : "";
            String streamUrl = parts.length > 2 ? parts[2] : "";
            String logoUrl = parts.length > 3 ? parts[3] : "";
            String homepage = parts.length > 4 ? parts[4] : "";
            String uuid = parts.length > 5 ? parts[5] : "";
            String country = parts.length > 6 ? parts[6] : "";
            String tags = parts.length > 7 ? parts[7] : "";
            return new RadioStation(TYPE_INTERNET, 0, name, streamUrl, logoUrl, homepage, uuid, country, tags);
        } else {
            // Legacy FM format: freq|name
            int bar = raw.indexOf('|');
            if (bar > 0) {
                try {
                    int freq = Integer.parseInt(raw.substring(0, bar));
                    String name = raw.substring(bar + 1);
                    return new RadioStation(freq, name);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace("|", " ").replace(";", " ").replace("\n", " ").trim();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RadioStation)) return false;
        RadioStation that = (RadioStation) o;
        if (type != that.type) return false;
        if (isFm()) {
            return freq == that.freq;
        } else {
            return streamUrl.equalsIgnoreCase(that.streamUrl);
        }
    }

    @Override
    public int hashCode() {
        return isFm() ? freq : streamUrl.toLowerCase(Locale.ROOT).hashCode();
    }
}
