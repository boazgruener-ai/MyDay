/** Gson response models for the Google Maps Distance Matrix and Geocoding APIs. */
package ch.boazgruener.myday.maps

import com.google.gson.annotations.SerializedName

data class DistanceMatrixResponse(
    @SerializedName("rows") val rows: List<DistanceMatrixRow> = emptyList(),
    @SerializedName("status") val status: String? = null,
    @SerializedName("error_message") val errorMessage: String? = null
)

data class DistanceMatrixRow(
    @SerializedName("elements") val elements: List<DistanceMatrixElement> = emptyList()
)

data class DistanceMatrixElement(
    @SerializedName("status") val status: String? = null,
    @SerializedName("duration") val duration: DurationValue? = null,
    /** Traffic-aware duration - only present when a departure_time was supplied. */
    @SerializedName("duration_in_traffic") val durationInTraffic: DurationValue? = null
)

data class DurationValue(
    @SerializedName("value") val seconds: Long,
    @SerializedName("text") val text: String
)

data class GeocodingResponse(
    @SerializedName("results") val results: List<GeocodingResult> = emptyList(),
    @SerializedName("status") val status: String? = null,
    @SerializedName("error_message") val errorMessage: String? = null
)

data class GeocodingResult(
    @SerializedName("formatted_address") val formattedAddress: String? = null
)
