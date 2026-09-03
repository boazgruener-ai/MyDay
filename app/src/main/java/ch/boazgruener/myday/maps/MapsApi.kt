/** Retrofit interface for the Google Maps Distance Matrix and Geocoding REST endpoints. */
package ch.boazgruener.myday.maps

import retrofit2.http.GET
import retrofit2.http.Query

interface MapsApi {
    @GET("maps/api/distancematrix/json")
    suspend fun getDistanceMatrix(
        @Query("origins") origins: String,
        @Query("destinations") destinations: String,
        @Query("mode") mode: String,
        /**
         * Unix seconds, or "now" - enables traffic-aware duration_in_traffic for driving, and
         * picks which scheduled trip to estimate for transit. Not meaningful for walking/
         * bicycling, so callers pass null there and Retrofit omits the query param entirely.
         */
        @Query("departure_time") departureTime: String?,
        /**
         * ccTLD-style region bias (e.g. "ch") for ambiguous free-text place names - without it,
         * a bare name like "Sonnenberg" (multiple towns share it, including outside Switzerland)
         * can geocode to the wrong one entirely, unlike Google Maps' own app which biases toward
         * the device's actual location.
         */
        @Query("region") region: String?,
        @Query("key") apiKey: String
    ): DistanceMatrixResponse

    @GET("maps/api/geocode/json")
    suspend fun geocode(
        @Query("address") address: String,
        /**
         * "south,west|north,east" viewport around Boaz's actual current location - a soft bias
         * (not a hard filter) so a same-named place close by outranks a far-away namesake, e.g.
         * preferring the local Sonnenberg or Bethlehem over ones in another canton or country.
         */
        @Query("bounds") bounds: String?,
        @Query("region") region: String?,
        @Query("key") apiKey: String
    ): GeocodingResponse
}
