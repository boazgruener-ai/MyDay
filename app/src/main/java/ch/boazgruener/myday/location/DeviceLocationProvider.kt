package ch.boazgruener.myday.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "MydayLocationProvider"

data class DeviceLocation(val latitude: Double, val longitude: Double)

/**
 * Device location for weather and travel-time features. Actively requests a fresh fix via the
 * Fused Location Provider rather than relying only on the OS's passive "last known location"
 * cache - that cache is only refreshed when some app (including this one, in the foreground)
 * has recently triggered GPS/network location, so a background WorkManager job running hours
 * after the last foreground use would otherwise reliably come back empty (confirmed in testing:
 * a manual in-app check worked right after using Google Maps, but two unattended background
 * runs 15 and 30 minutes later both found no cached fix at all). getCurrentLocation() works from
 * a background context with just the foreground location permission we already request, since
 * it's a one-off request rather than continuous background tracking.
 *
 * Falls back to the plain LocationManager cache if the active request times out or fails, on
 * the theory that a possibly-stale fix beats none at all for a travel-time estimate; returns
 * null (callers fall back to HomeLocation) only if neither yields anything. Every failure path
 * logs why, since a silent null here is otherwise indistinguishable between "permission
 * missing," "Location toggled off at the OS level," "timed out," and "genuine error."
 */
class DeviceLocationProvider(private val context: Context) {
    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    suspend fun getLastKnownLocation(): DeviceLocation? {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "Neither ACCESS_FINE_LOCATION nor ACCESS_COARSE_LOCATION granted")
            return null
        }
        // PRIORITY_HIGH_ACCURACY below only actually gets GPS-backed precision with FINE granted -
        // COARSE alone silently degrades every request to Android's approximate-location tier
        // (Android 12+), which is meaningfully less reliable in a moving vehicle. Not a hard
        // blocker (COARSE-only still returns something workable at home/stationary), just worth
        // knowing which tier a given failure came from.
        if (!hasFine) Log.w(TAG, "Only ACCESS_COARSE_LOCATION granted - requests will use the degraded approximate-location tier")

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val locationEnabled = manager?.isLocationEnabled ?: false
        if (!locationEnabled) {
            Log.w(TAG, "Location is toggled OFF at the OS level - no fix is possible regardless of app permission")
        }

        val fresh = try {
            withTimeoutOrNull(25_000) {
                fusedClient.getCurrentLocation(
                    // HIGH_ACCURACY (GPS-backed) rather than BALANCED_POWER (network/cell-based)
                    // - this runs at most once per 15-minute WorkManager window, so the extra
                    // battery cost is negligible next to actually getting a fix. Confirmed via
                    // logcat that BALANCED_POWER with a 10s timeout was timing out from a
                    // background/idle context with an empty passive cache too, silently
                    // skipping travel-time alerts.
                    Priority.PRIORITY_HIGH_ACCURACY,
                    CancellationTokenSource().token
                ).await()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Active getCurrentLocation() threw", e)
            null
        }
        if (fresh != null) {
            Log.d(TAG, "Active fix succeeded: ${fresh.latitude},${fresh.longitude}")
            return DeviceLocation(fresh.latitude, fresh.longitude)
        }
        Log.w(TAG, "Active getCurrentLocation() returned no result (timed out or no provider available)")

        val cached = getCachedLocation()
        if (cached == null) {
            Log.w(TAG, "Passive cache fallback also empty - giving up for this call")
        } else {
            Log.d(TAG, "Passive cache fallback succeeded: ${cached.latitude},${cached.longitude}")
        }
        return cached
    }

    private fun getCachedLocation(): DeviceLocation? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return manager.getProviders(true)
            .mapNotNull { provider -> manager.getLastKnownLocation(provider) }
            .maxByOrNull { it.time }
            ?.let { DeviceLocation(it.latitude, it.longitude) }
    }
}
