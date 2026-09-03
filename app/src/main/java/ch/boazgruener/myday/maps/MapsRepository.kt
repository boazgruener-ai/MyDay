/**
 * Wraps the Google Maps Distance Matrix and Geocoding APIs to estimate travel time and
 * disambiguate free-text place names toward wherever Boaz actually is.
 */
package ch.boazgruener.myday.maps

import android.util.Log
import ch.boazgruener.myday.BuildConfig
import ch.boazgruener.myday.location.DeviceLocation
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Half-width/height (degrees) of the soft-bias viewport used in [MapsRepository.resolveNearestAddress]
 * - roughly 165km, wide enough to cover any realistic Swiss driving destination while still
 * clearly favoring Boaz's home region over a same-named place in another country.
 */
private const val LOCATION_BIAS_BOX_DEGREES = 1.5
/** Boaz is based in Switzerland - biases ambiguous geocoding here as a first-pass filter. */
private const val HOME_REGION = "ch"

private const val TAG = "MydayMapsRepository"

/**
 * The Maps API key is restricted to "Android apps" in Cloud Console, which Google verifies via
 * these two request headers rather than the request's origin - without them, Google rejects
 * every call (including from the real app) with REQUEST_DENIED, since a plain Retrofit/OkHttp
 * client doesn't send them automatically the way Google's own Maps SDK would. Verified directly
 * against the live API before shipping.
 */
private const val ANDROID_PACKAGE = "ch.boazgruener.myday"
private const val ANDROID_CERT_SHA1 = "0BC9F077148D8BA568BCB01245E78638F125E446"

class MapsRepository {
    private val api: MapsApi = Retrofit.Builder()
        .baseUrl("https://maps.googleapis.com/")
        .client(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(Interceptor { chain ->
                    val request = chain.request().newBuilder()
                        .addHeader("X-Android-Package", ANDROID_PACKAGE)
                        .addHeader("X-Android-Cert", ANDROID_CERT_SHA1)
                        .build()
                    chain.proceed(request)
                })
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(MapsApi::class.java)

    /**
     * Travel time from [origin] to [destinationAddress] (either can be a lat,lng pair or a
     * free-text address) by the given [mode] - "driving" (traffic-aware), "walking",
     * "bicycling", or "transit". Traffic-aware duration_in_traffic is only meaningful (and only
     * returned by the API) for driving; other modes use the plain duration.
     */
    suspend fun getTravelDuration(
        origin: String,
        destinationAddress: String,
        mode: String = "driving"
    ): Duration? {
        if (BuildConfig.GOOGLE_MAPS_API_KEY.isBlank()) return null

        val response = try {
            api.getDistanceMatrix(
                origins = origin,
                destinations = destinationAddress,
                mode = mode,
                departureTime = if (mode == "driving" || mode == "transit") "now" else null,
                region = HOME_REGION,
                apiKey = BuildConfig.GOOGLE_MAPS_API_KEY
            )
        } catch (e: Exception) {
            Log.e(TAG, "Distance Matrix request failed", e)
            return null
        }

        if (response.status != "OK") {
            Log.w(TAG, "Distance Matrix returned status=${response.status}: ${response.errorMessage}")
            return null
        }
        val element = response.rows.firstOrNull()?.elements?.firstOrNull() ?: return null
        if (element.status != "OK") {
            Log.w(TAG, "Distance Matrix element status=${element.status} for \"$destinationAddress\"")
            return null
        }

        val durationValue = if (mode == "driving") element.durationInTraffic ?: element.duration else element.duration
        val seconds = durationValue?.seconds ?: return null
        return Duration.ofSeconds(seconds)
    }

    /**
     * Disambiguates a free-text place name (e.g. "Sonnenberg", "Bethlehem") toward whichever
     * same-named place is actually near Boaz, before it's ever handed to Distance Matrix -
     * Google's own Maps app does this automatically using the device's location; a raw
     * Distance Matrix call does not, so without this an ambiguous name can silently resolve to
     * a namesake on the other side of the country, or the world. Falls back to returning
     * [placeName] unchanged on any failure, so callers never need a null-handling path just for
     * this best-effort step.
     */
    suspend fun resolveNearestAddress(bias: DeviceLocation, placeName: String): String {
        if (BuildConfig.GOOGLE_MAPS_API_KEY.isBlank()) return placeName

        val bounds = "${bias.latitude - LOCATION_BIAS_BOX_DEGREES},${bias.longitude - LOCATION_BIAS_BOX_DEGREES}" +
            "|${bias.latitude + LOCATION_BIAS_BOX_DEGREES},${bias.longitude + LOCATION_BIAS_BOX_DEGREES}"
        val response = try {
            api.geocode(
                address = placeName,
                bounds = bounds,
                region = HOME_REGION,
                apiKey = BuildConfig.GOOGLE_MAPS_API_KEY
            )
        } catch (e: Exception) {
            Log.w(TAG, "Geocoding lookup failed for \"$placeName\", using it as-is", e)
            return placeName
        }
        if (response.status != "OK") {
            Log.w(TAG, "Geocoding returned status=${response.status} for \"$placeName\" " +
                "(${response.errorMessage}), using it as-is")
            return placeName
        }
        val resolved = response.results.firstOrNull()?.formattedAddress ?: return placeName
        Log.d(TAG, "Resolved \"$placeName\" -> \"$resolved\" (biased near ${bias.latitude},${bias.longitude})")
        return resolved
    }
}
