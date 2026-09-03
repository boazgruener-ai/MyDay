package ch.boazgruener.myday.weather

import ch.boazgruener.myday.BuildConfig
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/** Home location - a per-device value from local.properties (see app/build.gradle.kts), not
 * hardcoded here, since this is a public repo and where someone actually lives isn't. */
object HomeLocation {
    val LATITUDE: Double = BuildConfig.HOME_LATITUDE
    val LONGITUDE: Double = BuildConfig.HOME_LONGITUDE
    val CITY: String = BuildConfig.HOME_CITY
    val REGION: String = BuildConfig.HOME_REGION
    val COUNTRY: String = BuildConfig.HOME_COUNTRY
    val TIMEZONE: String = BuildConfig.HOME_TIMEZONE
    val DISPLAY: String = BuildConfig.HOME_DISPLAY
}

class OpenMeteoClient {
    private val api: OpenMeteoApi = Retrofit.Builder()
        .baseUrl("https://api.open-meteo.com/")
        .client(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(OpenMeteoApi::class.java)

    /** Falls back to [HomeLocation] when no device location is available (e.g. permission denied). */
    suspend fun getWeather(
        latitude: Double = HomeLocation.LATITUDE,
        longitude: Double = HomeLocation.LONGITUDE
    ): CurrentWeather = api.getCurrentWeather(latitude, longitude).current
}
