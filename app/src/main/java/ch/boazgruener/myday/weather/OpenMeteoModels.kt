package ch.boazgruener.myday.weather

import com.google.gson.annotations.SerializedName

data class ForecastResponse(
    @SerializedName("current") val current: CurrentWeather
)

data class CurrentWeather(
    @SerializedName("temperature_2m") val temperatureC: Double,
    @SerializedName("weather_code") val weatherCode: Int,
    @SerializedName("wind_speed_10m") val windSpeedKmh: Double,
    @SerializedName("relative_humidity_2m") val relativeHumidityPercent: Int
)

/**
 * Human-readable text for WMO weather codes (used by Open-Meteo's "current" fields).
 * https://open-meteo.com/en/docs#weathervariables
 */
fun weatherCodeDescription(code: Int): String = when (code) {
    0 -> "Clear sky"
    1 -> "Mainly clear"
    2 -> "Partly cloudy"
    3 -> "Overcast"
    45, 48 -> "Fog"
    51, 53, 55 -> "Drizzle"
    56, 57 -> "Freezing drizzle"
    61, 63, 65 -> "Rain"
    66, 67 -> "Freezing rain"
    71, 73, 75 -> "Snow"
    77 -> "Snow grains"
    80, 81, 82 -> "Rain showers"
    85, 86 -> "Snow showers"
    95 -> "Thunderstorm"
    96, 99 -> "Thunderstorm with hail"
    else -> "Unknown conditions"
}
