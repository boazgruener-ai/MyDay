/** Google OAuth scopes and the two authorization clients that request them - [GoogleAuthManager]
 * for the interactive (Activity-driven) consent flow, [BackgroundGoogleAuth] for silent
 * background use once scopes are already granted. */
package ch.boazgruener.myday.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Scopes needed for Calendar (read + RSVP write) and Gmail (read + label/archive for inbox
 * cleanup). No Credential Manager / second "Web application" OAuth client needed here - this
 * app has no backend, so AuthorizationClient alone (against the existing Android OAuth client)
 * is sufficient. See the scaffolding plan for why this was chosen over Credential Manager.
 */
object GoogleScopes {
    val GMAIL_MODIFY = Scope("https://www.googleapis.com/auth/gmail.modify")
    /**
     * Read/write access to events (accept/decline/tentative RSVPs) - supersedes the old
     * calendar.readonly scope, which can't be used for the write-mode RSVP feature. Since this
     * is a broader scope than what was previously granted, existing users need to re-authorize
     * once via the interactive flow in MainActivity before write actions will work; the
     * background-only path (BackgroundGoogleAuth) can't obtain fresh consent by itself.
     */
    val CALENDAR_EVENTS = Scope("https://www.googleapis.com/auth/calendar.events")
    /** App-private Drive storage (invisible in the user's normal Drive UI) - used to back up
     * the Settings-menu lists so an uninstall can't silently erase them. */
    val DRIVE_APPDATA = Scope("https://www.googleapis.com/auth/drive.appdata")
}

class GoogleAuthManager(activity: Activity) {
    private val client = Identity.getAuthorizationClient(activity)

    private var resolutionContinuation: ((AuthorizationResult) -> Unit)? = null

    /** Call this from the launcher's ActivityResult callback once the consent UI returns. */
    fun onResolutionResult(data: Intent?) {
        val cont = resolutionContinuation ?: return
        resolutionContinuation = null
        cont(client.getAuthorizationResultFromIntent(data ?: Intent()))
    }

    suspend fun authorize(
        resolutionLauncher: ActivityResultLauncher<IntentSenderRequest>
    ): AuthorizationResult {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(
                listOf(GoogleScopes.GMAIL_MODIFY, GoogleScopes.CALENDAR_EVENTS, GoogleScopes.DRIVE_APPDATA)
            )
            .build()

        val result = client.authorize(request).await()
        if (!result.hasResolution()) return result

        // Scopes not yet granted - launch the consent UI and wait for it to come back.
        return suspendCoroutine { cont ->
            resolutionContinuation = { finalResult -> cont.resume(finalResult) }
            val intentSenderRequest =
                IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build()
            resolutionLauncher.launch(intentSenderRequest)
        }
    }
}

/**
 * Context-only client for background use (the wake-word service, which has no Activity to
 * show a consent screen from). Only succeeds silently if scopes were already granted via
 * GoogleAuthManager's interactive flow - if fresh consent is needed, the result's
 * hasResolution() will be true and the caller must handle that itself (e.g. ask the user to
 * open the app), since a service can't launch the consent UI.
 */
class BackgroundGoogleAuth(context: Context) {
    private val client = Identity.getAuthorizationClient(context)

    suspend fun authorize(): AuthorizationResult {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(
                listOf(GoogleScopes.GMAIL_MODIFY, GoogleScopes.CALENDAR_EVENTS, GoogleScopes.DRIVE_APPDATA)
            )
            .build()
        return client.authorize(request).await()
    }
}
