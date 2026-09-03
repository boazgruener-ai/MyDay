/** Retrofit interface for Open-Meteo's current-weather forecast endpoint. */
package ch.boazgruener.myday.weather

import retrofit2.http.GET
import retrofit2.http.Query

interface OpenMeteoApi {
    @GET("v1/forecast")
    suspend fun getCurrentWeather(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String =
            "temperature_2m,weather_code,wind_speed_10m,relative_humidity_2m",
        @Query("timezone") timezone: String = "Europe/Zurich"
    ): ForecastResponse
}
