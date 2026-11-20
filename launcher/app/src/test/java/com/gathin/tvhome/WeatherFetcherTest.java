package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class WeatherFetcherTest {
    @Test public void requestUsesCurrentApiFieldsAndNumericCoordinates() {
        String url = WeatherFetcher.requestUrl("55.75", "37.62");
        assertTrue(url.contains("latitude=55.75&longitude=37.62"));
        assertTrue(url.contains("weather_code,wind_speed_10m"));
        assertTrue(url.contains("wind_speed_unit=ms"));
        assertTrue(WeatherFetcher.requestUrl("-90", "180").contains("latitude=-90.0"));
    }

    @Test public void invalidCoordinatesCannotReachTheNetwork() {
        for (String value : new String[]{"NaN", "Infinity", "91", "0&longitude=1", "bad"}) {
            try {
                WeatherFetcher.requestUrl(value, "0");
                fail(value);
            } catch (IllegalArgumentException expected) { }
        }
        try {
            WeatherFetcher.requestUrl("0", "181");
            fail("Longitude outside range");
        } catch (IllegalArgumentException expected) { }
    }
}
