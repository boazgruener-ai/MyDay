/**
 * App entry point and the entire Compose UI: hamburger-drawer navigation, bottom nav bar, the
 * home screen (status/weather/summary counts/activity feed), the Permissions screen, and every
 * Settings/Manual-Runs dialog. No ViewModel layer - state lives directly in MainScreen's
 * composable scope, reading from AppContainer's repositories/stores.
 */
package ch.boazgruener.myday

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ch.boazgruener.myday.activitylog.ActivityLogEntry
import ch.boazgruener.myday.anthropic.AnthropicClient
import ch.boazgruener.myday.auth.BackgroundGoogleAuth
import ch.boazgruener.myday.backup.SettingsBackup
import ch.boazgruener.myday.calendar.isPast
import ch.boazgruener.myday.location.DeviceLocationProvider
import ch.boazgruener.myday.location.NamedLocationsStore
import ch.boazgruener.myday.auth.GoogleAuthManager
import ch.boazgruener.myday.briefing.DailyBriefingUseCase
import ch.boazgruener.myday.calendar.CalendarEvent
import ch.boazgruener.myday.calendar.CalendarRepository
import ch.boazgruener.myday.gmail.ClassificationLogEntry
import ch.boazgruener.myday.gmail.ClassificationLogStore
import ch.boazgruener.myday.gmail.GmailRepository
import ch.boazgruener.myday.gmail.JunkBlacklistStore
import ch.boazgruener.myday.gmail.MessageMetadata
import ch.boazgruener.myday.gmail.PromotionAllowlistStore
import ch.boazgruener.myday.gmail.headerValue
import ch.boazgruener.myday.notifications.isNotificationAccessGranted
import ch.boazgruener.myday.voice.TtsSpeaker
import ch.boazgruener.myday.voice.ACTION_STOP_SPEAKING
import ch.boazgruener.myday.voice.VoiceState
import ch.boazgruener.myday.voice.WakeWordForegroundService
import ch.boazgruener.myday.work.EmailCleanupWorker
import ch.boazgruener.myday.work.MeetingEmailCleanupWorker
import ch.boazgruener.myday.work.MeetingTravelWorker
import ch.boazgruener.myday.work.WorkResultKeys
import ch.boazgruener.myday.weather.CurrentWeather
import ch.boazgruener.myday.weather.HomeLocation
import ch.boazgruener.myday.weather.OpenMeteoClient
import ch.boazgruener.myday.weather.weatherCodeDescription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Enqueues [request] under [uniqueName] (KEEP policy) and suspends until a finished result is
 * available. Using unique-work-by-name rather than plain enqueue()+getWorkInfoByIdFlow(id)
 * matters here: pressing the same button twice before the first run finishes used to start a
 * second, fully overlapping pass - both reading the same "in:inbox" search results before either
 * had finished archiving anything, so the same messages got processed twice (and the run itself
 * took minutes since Gmail's API doesn't batch these calls). KEEP makes the second press attach
 * to the already-running instance instead of starting a duplicate.
 */
private suspend fun runWorkAndAwaitResult(context: Context, uniqueName: String, request: OneTimeWorkRequest): WorkInfo? {
    val workManager = WorkManager.getInstance(context)
    workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
    return workManager.getWorkInfosForUniqueWorkFlow(uniqueName)
        .mapNotNull { infos -> infos.firstOrNull { it.state.isFinished } }
        .first()
}

private fun networkConstraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

private const val REAUTH_MESSAGE = "Needs re-authorization - open Myday and tap \"Authorize Gmail + Calendar\"."

private fun formatEmailCleanupResult(info: WorkInfo?): String = when {
    info == null -> "No result received."
    info.state == WorkInfo.State.FAILED -> "Cleanup failed - check logs."
    info.outputData.getBoolean(WorkResultKeys.NEEDS_REAUTH, false) -> REAUTH_MESSAGE
    else -> {
        val processed = info.outputData.getInt(WorkResultKeys.PROCESSED, 0)
        val promotions = info.outputData.getInt(WorkResultKeys.PROMOTIONS, 0)
        val junk = info.outputData.getInt(WorkResultKeys.JUNK, 0)
        val payments = info.outputData.getInt(WorkResultKeys.PAYMENTS, 0)
        val jobs = info.outputData.getInt(WorkResultKeys.JOBS, 0)
        val googleNotifications = info.outputData.getInt(WorkResultKeys.GOOGLE_NOTIFICATIONS, 0)
        "Checked $processed email(s): $promotions filed as promotion, $junk as junk, $payments as payment, " +
            "$jobs as job, $googleNotifications as Google notification."
    }
}

private fun formatMeetingAlertResult(info: WorkInfo?): String = when {
    info == null -> "No result received."
    info.state == WorkInfo.State.FAILED -> "Check failed - check logs."
    info.outputData.getBoolean(WorkResultKeys.NEEDS_REAUTH, false) -> REAUTH_MESSAGE
    else -> {
        val alerts = info.outputData.getInt(WorkResultKeys.ALERTS_SENT, 0)
        val total = info.outputData.getInt(WorkResultKeys.TOTAL_EVENTS, 0)
        val hasFix = info.outputData.getBoolean(WorkResultKeys.HAS_LOCATION_FIX, false)
        if (alerts == 0) {
            "Ran successfully - considered $total event(s) today, location fix: $hasFix, no qualifying alert right now."
        } else {
            "Sent $alerts alert(s) (out of $total event(s) considered)."
        }
    }
}

private fun formatMeetingEmailCleanupResult(info: WorkInfo?): String = when {
    info == null -> "No result received."
    info.state == WorkInfo.State.FAILED -> "Cleanup failed - check logs."
    info.outputData.getBoolean(WorkResultKeys.NEEDS_REAUTH, false) -> REAUTH_MESSAGE
    else -> {
        val archived = info.outputData.getInt(WorkResultKeys.ARCHIVED, 0)
        val candidates = info.outputData.getInt(WorkResultKeys.CANDIDATE_EMAILS, 0)
        val unparseable = info.outputData.getInt(WorkResultKeys.UNPARSEABLE, 0)
        "Archived $archived of $candidates calendar email(s) in inbox" +
            if (unparseable > 0) " ($unparseable couldn't be parsed for a date)." else "."
    }
}

private const val TAG = "MydayAuth"
/** How long a manual run gets to finish silently before the "working on it" dialog appears -
 * long enough that a normal (well under a second) run never flashes it, short enough that a
 * genuinely slow run still gets feedback promptly. */
private const val MANUAL_RUN_LOADING_GRACE_MS = 1500L

class MainActivity : ComponentActivity() {
    private lateinit var ttsSpeaker: TtsSpeaker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MydayApplication).container
        val openMeteoClient = container.openMeteoClient
        val calendarRepository = container.calendarRepository
        val gmailRepository = container.gmailRepository
        val anthropicClient = container.anthropicClient
        val dailyBriefingUseCase = container.dailyBriefingUseCase
        val authManager = GoogleAuthManager(this)
        ttsSpeaker = TtsSpeaker(this)

        setContent {
            val resolutionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartIntentSenderForResult()
            ) { result ->
                authManager.onResolutionResult(result.data)
            }

            MaterialTheme {
                MainScreen(
                    openMeteoClient = openMeteoClient,
                    calendarRepository = calendarRepository,
                    gmailRepository = gmailRepository,
                    anthropicClient = anthropicClient,
                    dailyBriefingUseCase = dailyBriefingUseCase,
                    ttsSpeaker = ttsSpeaker,
                    authManager = authManager,
                    resolutionLauncher = resolutionLauncher
                )
            }
        }
    }

    override fun onDestroy() {
        ttsSpeaker.shutdown()
        super.onDestroy()
    }
}

private enum class DrawerPane { Main, Settings, ManualRuns }

private sealed interface WeatherUiState {
    data object Loading : WeatherUiState
    data class Loaded(val weather: CurrentWeather) : WeatherUiState
    data class Error(val message: String) : WeatherUiState
}

private sealed interface AuthUiState {
    data object NotAuthorized : AuthUiState
    data object Authorizing : AuthUiState
    data class Authorized(val grantedScopes: List<String>) : AuthUiState
    data class Error(val message: String) : AuthUiState
}

private sealed interface CalendarUiState {
    data object Idle : CalendarUiState
    data object Loading : CalendarUiState
    data class Loaded(val events: List<CalendarEvent>) : CalendarUiState
    data class Error(val message: String) : CalendarUiState
}

private sealed interface GmailUiState {
    data object Idle : GmailUiState
    data object Loading : GmailUiState
    data class Loaded(val messages: List<MessageMetadata>) : GmailUiState
    data class Error(val message: String) : GmailUiState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    openMeteoClient: OpenMeteoClient,
    calendarRepository: CalendarRepository,
    gmailRepository: GmailRepository,
    anthropicClient: AnthropicClient,
    dailyBriefingUseCase: DailyBriefingUseCase,
    ttsSpeaker: TtsSpeaker,
    authManager: GoogleAuthManager,
    resolutionLauncher: ActivityResultLauncher<IntentSenderRequest>
) {
    var weatherState by remember { mutableStateOf<WeatherUiState>(WeatherUiState.Loading) }
    var authState by remember { mutableStateOf<AuthUiState>(AuthUiState.NotAuthorized) }
    var calendarState by remember { mutableStateOf<CalendarUiState>(CalendarUiState.Idle) }
    var gmailState by remember { mutableStateOf<GmailUiState>(GmailUiState.Idle) }
    var accessToken by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var micPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    // Gated on FINE specifically, not just COARSE - the app asks the Fused Location Provider for
    // PRIORITY_HIGH_ACCURACY (GPS-backed) fixes for travel-time estimates, but since Android 12
    // a COARSE-only grant silently caps every request to the OS's degraded "approximate location"
    // tier regardless of the priority requested.
    var locationPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var contactsPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var wakeWordListening by remember { mutableStateOf(false) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        micPermissionGranted = results[Manifest.permission.RECORD_AUDIO] == true
        locationPermissionGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        contactsPermissionGranted = results[Manifest.permission.READ_CONTACTS] == true
    }
    val locationProvider = remember { DeviceLocationProvider(context) }
    val promotionAllowlistStore = remember { PromotionAllowlistStore(context) }
    val junkBlacklistStore = remember { JunkBlacklistStore(context) }
    val classificationLogStore = remember { ClassificationLogStore(context) }
    val namedLocationsStore = remember { NamedLocationsStore(context) }
    val appContainer = remember { (context.applicationContext as MydayApplication).container }
    val voiceStateStore = remember { appContainer.voiceStateStore }
    val voiceState by voiceStateStore.state.collectAsState()
    val apiKeyStore = remember { appContainer.apiKeyStore }
    val settingsBackupRepository = remember { appContainer.settingsBackupRepository }
    val hasApiKey by apiKeyStore.hasApiKeyFlow().collectAsState(initial = false)
    val activityLogStore = remember { appContainer.activityLogStore }
    val activityLogEntries by activityLogStore.entriesFlow().collectAsState(initial = emptyList())

    var apiKeyInput by remember { mutableStateOf("") }
    var isEditingApiKey by remember { mutableStateOf(false) }
    var apiKeyMaskedDisplay by remember { mutableStateOf<String?>(null) }
    // XxxText holds the last-saved value; XxxDraft is a working copy the dialog edits, so
    // Cancel can discard it untouched and Save is the only path that overwrites XxxText/the
    // store. XxxEditMode toggles each dialog between a scrollable read-only list view and the
    // multi-line text editor - see ListEditorDialog.
    var allowlistText by remember { mutableStateOf("") }
    var allowlistDraft by remember { mutableStateOf("") }
    var allowlistEditMode by remember { mutableStateOf(false) }
    var isEditingAllowlist by remember { mutableStateOf(false) }
    var blacklistText by remember { mutableStateOf("") }
    var blacklistDraft by remember { mutableStateOf("") }
    var blacklistEditMode by remember { mutableStateOf(false) }
    var isEditingBlacklist by remember { mutableStateOf(false) }
    var namedLocationsText by remember { mutableStateOf("") }
    var namedLocationsDraft by remember { mutableStateOf("") }
    var namedLocationsEditMode by remember { mutableStateOf(false) }
    var isEditingNamedLocations by remember { mutableStateOf(false) }
    var classificationLogEntries by remember { mutableStateOf<List<ClassificationLogEntry>>(emptyList()) }
    var showClassificationLog by remember { mutableStateOf(false) }
    var showPermissions by remember { mutableStateOf(false) }

    // Summary counts for the home screen - populated silently in the background, no interactive
    // sign-in tap required just to show a number.
    var unreadEmailCount by remember { mutableStateOf<Int?>(null) }
    var remainingEventsCount by remember { mutableStateOf<Int?>(null) }
    var newWhatsAppCount by remember { mutableStateOf<Int?>(null) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var drawerPane by remember { mutableStateOf(DrawerPane.Main) }
    fun closeDrawer() {
        drawerPane = DrawerPane.Main
        scope.launch { drawerState.close() }
    }

    // One shared result dialog for every manual test/diagnostic action, instead of each button
    // permanently occupying its own space on the home screen with its own inline result text.
    // manualRunResultText == null (while manualRunResultTitle is set) means "still working" -
    // the dialog shows a spinner + Abort in that state, and the result + OK once it resolves.
    var manualRunResultTitle by remember { mutableStateOf<String?>(null) }
    var manualRunResultText by remember { mutableStateOf<String?>(null) }
    var manualRunJob by remember { mutableStateOf<Job?>(null) }
    var manualRunWorkName by remember { mutableStateOf<String?>(null) }

    fun dismissManualRunDialog() {
        manualRunResultTitle = null
        manualRunResultText = null
        manualRunJob = null
        manualRunWorkName = null
    }

    /**
     * Most manual runs resolve in well under a second - popping up a "working on it" dialog just
     * to immediately replace it with the result reads as a flicker, not useful feedback. So the
     * dialog only appears in its loading state if [action] is still running after
     * [MANUAL_RUN_LOADING_GRACE_MS]; if it finishes before that, the dialog appears once, already
     * showing the result, with no loading flash at all. [workName] (for WorkManager-backed
     * actions) lets Abort actually cancel the underlying background work, not just stop waiting.
     */
    fun runManualAction(title: String, workName: String? = null, action: suspend () -> String) {
        closeDrawer()
        val deferred = scope.async {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Failed: ${e.message ?: "Unknown error"}"
            }
        }
        manualRunJob = deferred
        manualRunWorkName = workName
        scope.launch {
            delay(MANUAL_RUN_LOADING_GRACE_MS)
            if (manualRunJob === deferred && deferred.isActive) {
                manualRunResultTitle = title
                manualRunResultText = null
            }
        }
        scope.launch {
            val result = deferred.await()
            manualRunResultTitle = title
            manualRunResultText = result
        }
    }

    suspend fun backupSettingsToDrive() {
        try {
            val authResult = BackgroundGoogleAuth(context).authorize()
            val token = authResult.accessToken
            if (!authResult.hasResolution() && token != null) {
                settingsBackupRepository.upload(
                    token,
                    SettingsBackup(
                        allowlistedSenders = promotionAllowlistStore.getAllowlistedSenders().toList(),
                        blacklistedSenders = junkBlacklistStore.getBlacklistedSenders().toList(),
                        namedLocations = namedLocationsStore.getAll()
                    )
                )
            }
        } catch (e: Exception) {
            // Best-effort - a failed backup shouldn't block or alarm the user mid-Save.
        }
    }

    LaunchedEffect(Unit) {
        allowlistText = promotionAllowlistStore.getAllowlistedSenders().joinToString("\n")
        blacklistText = junkBlacklistStore.getBlacklistedSenders().joinToString("\n")
        namedLocationsText = namedLocationsStore.getAll().entries.joinToString("\n") { "${it.key} = ${it.value}" }
        apiKeyMaskedDisplay = apiKeyStore.getMaskedApiKey()

        // If any Settings list is empty locally, check for a Drive backup before assuming
        // there's genuinely nothing saved - guards against exactly the Aug 29 2026
        // uninstall/reinstall scenario that silently wiped these without restoring them.
        if (allowlistText.isEmpty() || blacklistText.isEmpty() || namedLocationsText.isEmpty()) {
            try {
                val authResult = BackgroundGoogleAuth(context).authorize()
                val token = authResult.accessToken
                if (!authResult.hasResolution() && token != null) {
                    val backup = settingsBackupRepository.download(token)
                    if (backup != null) {
                        if (allowlistText.isEmpty() && backup.allowlistedSenders.isNotEmpty()) {
                            promotionAllowlistStore.setAllowlistedSenders(backup.allowlistedSenders.toSet())
                            allowlistText = backup.allowlistedSenders.joinToString("\n")
                        }
                        if (blacklistText.isEmpty() && backup.blacklistedSenders.isNotEmpty()) {
                            junkBlacklistStore.setBlacklistedSenders(backup.blacklistedSenders.toSet())
                            blacklistText = backup.blacklistedSenders.joinToString("\n")
                        }
                        if (namedLocationsText.isEmpty() && backup.namedLocations.isNotEmpty()) {
                            namedLocationsStore.setAll(backup.namedLocations)
                            namedLocationsText = backup.namedLocations.entries.joinToString("\n") { "${it.key} = ${it.value}" }
                        }
                    }
                }
            } catch (e: Exception) {
                // Best-effort restore - silently leave local (empty) state if this fails.
            }
        }
    }

    var notificationAccessGranted by remember { mutableStateOf(isNotificationAccessGranted(context)) }
    val notificationSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        notificationAccessGranted = isNotificationAccessGranted(context)
    }

    fun checkDndAccess() =
        (context.getSystemService(NotificationManager::class.java))?.isNotificationPolicyAccessGranted == true
    var dndAccessGranted by remember { mutableStateOf(checkDndAccess()) }
    val dndSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        dndAccessGranted = checkDndAccess()
    }

    LaunchedEffect(Unit) {
        weatherState = try {
            val location = locationProvider.getLastKnownLocation()
            val weather = if (location != null) {
                openMeteoClient.getWeather(location.latitude, location.longitude)
            } else {
                openMeteoClient.getWeather()
            }
            WeatherUiState.Loaded(weather)
        } catch (e: Exception) {
            WeatherUiState.Error(e.message ?: "Unknown error fetching weather")
        }
    }

    // Silent background auth, separate from the interactive "Google Sign-in" grant in
    // PermissionsScreen - so the summary counts can populate even before Boaz has explicitly
    // authorized this session, same silent path EmailCleanupWorker already uses.
    LaunchedEffect(Unit) {
        try {
            val authResult = BackgroundGoogleAuth(context).authorize()
            val token = authResult.accessToken
            if (!authResult.hasResolution() && token != null) {
                try {
                    unreadEmailCount = gmailRepository.search(token, query = "is:unread newer_than:1d", maxResults = 100).size
                } catch (e: Exception) {
                    Log.w(TAG, "Unread count fetch failed", e)
                }
                try {
                    val now = OffsetDateTime.now()
                    remainingEventsCount = calendarRepository.getTodayEvents(token).count { !it.isPast(now) }
                } catch (e: Exception) {
                    Log.w(TAG, "Remaining-events count fetch failed", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Silent background auth for summary counts failed", e)
        }
        try {
            val startOfToday = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            newWhatsAppCount = appContainer.whatsAppRepository
                .query(conversationFilter = null, onlyDirect = false, onlyMentions = false, maxResults = 200)
                .count { it.timestampMillis >= startOfToday }
        } catch (e: Exception) {
            Log.w(TAG, "WhatsApp count fetch failed", e)
        }
    }

    fun requestMicLocationContacts() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        micPermissionLauncher.launch(perms.toTypedArray())
    }

    fun toggleListening() {
        if (!micPermissionGranted || !locationPermissionGranted || !contactsPermissionGranted) {
            requestMicLocationContacts()
        } else if (!hasApiKey) {
            isEditingApiKey = true
        } else if (!wakeWordListening) {
            ContextCompat.startForegroundService(context, Intent(context, WakeWordForegroundService::class.java))
            wakeWordListening = true
        } else {
            context.stopService(Intent(context, WakeWordForegroundService::class.java))
            wakeWordListening = false
        }
    }

    fun authorizeGoogle() {
        authState = AuthUiState.Authorizing
        scope.launch {
            authState = try {
                val result = authManager.authorize(resolutionLauncher)
                Log.d(TAG, "Access token acquired (len=${result.accessToken?.length}), scopes=${result.grantedScopes}")

                val token = result.accessToken
                accessToken = token
                if (token != null) {
                    calendarState = CalendarUiState.Loading
                    calendarState = try {
                        CalendarUiState.Loaded(calendarRepository.getTodayEvents(token))
                    } catch (e: Exception) {
                        Log.e(TAG, "Calendar fetch failed", e)
                        CalendarUiState.Error(e.message ?: "Unknown calendar error")
                    }

                    gmailState = GmailUiState.Loading
                    gmailState = try {
                        GmailUiState.Loaded(gmailRepository.getRecentMessages(token))
                    } catch (e: Exception) {
                        Log.e(TAG, "Gmail fetch failed", e)
                        GmailUiState.Error(e.message ?: "Unknown Gmail error")
                    }
                }

                AuthUiState.Authorized(result.grantedScopes)
            } catch (e: Exception) {
                Log.e(TAG, "Authorization failed", e)
                AuthUiState.Error(e.message ?: "Unknown authorization error")
            }
        }
    }

    fun speakFullBriefing() {
        val token = accessToken
        if (token == null) {
            manualRunResultTitle = "Speak Full Briefing"
            manualRunResultText = "Grant Google Sign-in first (see Authorization)."
            return
        }
        scope.launch {
            voiceStateStore.set(VoiceState.PROCESSING)
            try {
                val text = dailyBriefingUseCase.buildBriefing(token, locationProvider.getLastKnownLocation())
                voiceStateStore.set(VoiceState.SPEAKING)
                ttsSpeaker.speak(text)
            } catch (e: Exception) {
                Log.e(TAG, "Briefing failed", e)
                manualRunResultTitle = "Speak Full Briefing"
                manualRunResultText = "Failed: ${e.message ?: "Unknown briefing error"}"
            } finally {
                voiceStateStore.set(if (wakeWordListening) VoiceState.LISTENING_FOR_WAKE_WORD else VoiceState.STOPPED)
            }
        }
    }

    BackHandler(enabled = drawerState.isOpen) { closeDrawer() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Myday", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp))
                    IconButton(onClick = { closeDrawer() }) {
                        Icon(Icons.Default.Close, contentDescription = "Close menu")
                    }
                }
                when (drawerPane) {
                    DrawerPane.Main -> Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        NavigationDrawerItem(
                            label = { Text(if (wakeWordListening) "Stop Listening" else "Start Myday") },
                            icon = { Icon(if (wakeWordListening) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = null) },
                            selected = false,
                            onClick = { toggleListening(); closeDrawer() },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                        NavigationDrawerItem(
                            label = { Text("Authorization") },
                            icon = { Icon(Icons.Default.Security, contentDescription = null) },
                            selected = false,
                            onClick = { showPermissions = true; closeDrawer() },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                        NavigationDrawerItem(
                            label = { Text("Settings") },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            selected = false,
                            onClick = { drawerPane = DrawerPane.Settings },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                        NavigationDrawerItem(
                            label = { Text("Manual Runs") },
                            icon = { Icon(Icons.Default.BugReport, contentDescription = null) },
                            selected = false,
                            onClick = { drawerPane = DrawerPane.ManualRuns },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                    }

                    DrawerPane.Settings -> Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        NavigationDrawerItem(
                            label = { Text("Back") },
                            icon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) },
                            selected = false,
                            onClick = { drawerPane = DrawerPane.Main },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                        NavigationDrawerItem(
                            label = { Text("Update Allowlist") },
                            selected = false,
                            onClick = {
                                allowlistDraft = allowlistText
                                allowlistEditMode = false
                                isEditingAllowlist = true
                                closeDrawer()
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                        NavigationDrawerItem(
                            label = { Text("Update Blacklist") },
                            selected = false,
                            onClick = {
                                blacklistDraft = blacklistText
                                blacklistEditMode = false
                                isEditingBlacklist = true
                                closeDrawer()
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                        NavigationDrawerItem(
                            label = { Text("Update Named Locations") },
                            selected = false,
                            onClick = {
                                namedLocationsDraft = namedLocationsText
                                namedLocationsEditMode = false
                                isEditingNamedLocations = true
                                closeDrawer()
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                        NavigationDrawerItem(
                            label = { Text(if (hasApiKey) "Update API Key" else "Add API Key") },
                            selected = false,
                            onClick = { isEditingApiKey = true; closeDrawer() },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                    }

                    DrawerPane.ManualRuns -> Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        NavigationDrawerItem(
                            label = { Text("Back") },
                            icon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) },
                            selected = false,
                            onClick = { drawerPane = DrawerPane.Main },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                        NavigationDrawerItem(
                            label = { Text("Test Claude API") },
                            selected = false,
                            onClick = {
                                runManualAction("Test Claude API") {
                                    anthropicClient.testMessage(
                                        "Say hello in one short sentence, and confirm the connection is working."
                                    )
                                }
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                        NavigationDrawerItem(
                            label = { Text("Run Email Cleanup Now") },
                            selected = false,
                            onClick = {
                                runManualAction("Email Cleanup", workName = "email_cleanup_manual") {
                                    val info = runWorkAndAwaitResult(
                                        context, "email_cleanup_manual",
                                        OneTimeWorkRequestBuilder<EmailCleanupWorker>().setConstraints(networkConstraints()).build()
                                    )
                                    formatEmailCleanupResult(info)
                                }
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                        NavigationDrawerItem(
                            label = { Text("Run Meeting Alert Check Now") },
                            selected = false,
                            onClick = {
                                runManualAction("Meeting Alert Check", workName = "meeting_travel_manual") {
                                    val info = runWorkAndAwaitResult(
                                        context, "meeting_travel_manual",
                                        OneTimeWorkRequestBuilder<MeetingTravelWorker>().setConstraints(networkConstraints()).build()
                                    )
                                    formatMeetingAlertResult(info)
                                }
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                        NavigationDrawerItem(
                            label = { Text("Run Meeting Email Cleanup Now") },
                            selected = false,
                            onClick = {
                                runManualAction("Meeting Email Cleanup", workName = "meeting_email_cleanup_manual") {
                                    val info = runWorkAndAwaitResult(
                                        context, "meeting_email_cleanup_manual",
                                        OneTimeWorkRequestBuilder<MeetingEmailCleanupWorker>().setConstraints(networkConstraints()).build()
                                    )
                                    formatMeetingEmailCleanupResult(info)
                                }
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                        NavigationDrawerItem(
                            label = { Text("Show Email Classification Log") },
                            selected = false,
                            onClick = {
                                closeDrawer()
                                scope.launch {
                                    classificationLogEntries = classificationLogStore.getEntries()
                                    showClassificationLog = true
                                }
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Myday") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = wakeWordListening,
                        onClick = { toggleListening() },
                        icon = { Icon(if (wakeWordListening) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = null) },
                        label = { Text(if (wakeWordListening) "Stop Listening" else "Start Myday") }
                    )
                    NavigationBarItem(
                        selected = false,
                        enabled = wakeWordListening,
                        onClick = {
                            context.startService(
                                Intent(context, WakeWordForegroundService::class.java).setAction(ACTION_STOP_SPEAKING)
                            )
                        },
                        icon = { Icon(Icons.AutoMirrored.Filled.VolumeOff, contentDescription = null) },
                        label = { Text("Stop Speaking") }
                    )
                    NavigationBarItem(
                        selected = false,
                        onClick = { speakFullBriefing() },
                        icon = { Icon(Icons.Default.WbSunny, contentDescription = null) },
                        label = { Text("Speak Brief") }
                    )
                }
            }
        ) { innerPadding ->
            Surface(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Top,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "Myday", style = MaterialTheme.typography.headlineLarge)
                    Text(text = HomeLocation.DISPLAY, style = MaterialTheme.typography.bodySmall)
                    when (val s = weatherState) {
                        is WeatherUiState.Loading -> Text("Loading weather…", style = MaterialTheme.typography.bodySmall)
                        is WeatherUiState.Loaded -> {
                            val w = s.weather
                            Text(
                                text = "${w.temperatureC.roundToInt()}°C · ${weatherCodeDescription(w.weatherCode)} · " +
                                    "Humidity ${w.relativeHumidityPercent}% · Wind ${w.windSpeedKmh.roundToInt()} km/h",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        is WeatherUiState.Error -> Text("Weather fetch failed: ${s.message}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        text = "Status: ${voiceState.label}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    Card(modifier = Modifier.padding(top = 20.dp).fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Today", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${unreadEmailCount?.toString() ?: "…"} unread email(s)",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            Text(
                                "${remainingEventsCount?.toString() ?: "…"} meeting(s) remaining today",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "${newWhatsAppCount?.toString() ?: "…"} new WhatsApp message(s)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    Text(
                        text = "Recent activity",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
                    )
                    if (activityLogEntries.isEmpty()) {
                        Text(
                            "Nothing yet - ask Myday something, or wait for the next background check.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().height(220.dp)) {
                            items(activityLogEntries.take(10)) { entry: ActivityLogEntry ->
                                Text(
                                    text = "${entry.timestamp} — ${entry.summary}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                    }

                    // Kept exactly as-is for now (deferred decision) - the one place Boaz can see
                    // his actual Calendar/Gmail data on screen rather than by voice.
                    when (val a = authState) {
                        is AuthUiState.NotAuthorized -> {}
                        is AuthUiState.Authorizing -> CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                        is AuthUiState.Authorized -> Text(
                            text = "Google authorized ✓ scopes: ${a.grantedScopes.joinToString()}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                        is AuthUiState.Error -> Text(
                            text = "Auth failed: ${a.message}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                    when (val c = calendarState) {
                        is CalendarUiState.Idle -> {}
                        is CalendarUiState.Loading -> CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp))
                        is CalendarUiState.Loaded -> {
                            Text(
                                text = "Today's events (${c.events.size}):",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 16.dp)
                            )
                            if (c.events.isEmpty()) {
                                Text("Nothing on the calendar today", style = MaterialTheme.typography.bodySmall)
                            } else {
                                c.events.forEach { event ->
                                    val time = event.start?.dateTime ?: event.start?.date ?: "?"
                                    Text("• ${event.summary ?: "(no title)"} — $time", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        is CalendarUiState.Error -> Text(
                            text = "Calendar fetch failed: ${c.message}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                    when (val g = gmailState) {
                        is GmailUiState.Idle -> {}
                        is GmailUiState.Loading -> CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp))
                        is GmailUiState.Loaded -> {
                            Text(
                                text = "Last 24h emails (${g.messages.size}):",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 16.dp)
                            )
                            if (g.messages.isEmpty()) {
                                Text("No emails in the last 24 hours", style = MaterialTheme.typography.bodySmall)
                            } else {
                                g.messages.forEach { msg ->
                                    val from = msg.headerValue("From") ?: "(unknown sender)"
                                    val subject = msg.headerValue("Subject") ?: "(no subject)"
                                    Text("• $subject — $from", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        is GmailUiState.Error -> Text(
                            text = "Gmail fetch failed: ${g.message}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }
            }
        }
    }

    if (showPermissions) {
        PermissionsScreen(
            onDismiss = { showPermissions = false },
            micLocationContactsGranted = micPermissionGranted && locationPermissionGranted && contactsPermissionGranted,
            onRequestMicLocationContacts = ::requestMicLocationContacts,
            notificationAccessGranted = notificationAccessGranted,
            onOpenNotificationSettings = {
                notificationSettingsLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            dndAccessGranted = dndAccessGranted,
            onOpenDndSettings = {
                dndSettingsLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            },
            googleAuthorized = authState is AuthUiState.Authorized,
            onAuthorizeGoogle = ::authorizeGoogle
        )
    }

    if (manualRunResultTitle != null) {
        val isWorking = manualRunResultText == null
        Dialog(
            // While still working, only Abort dismisses this - an accidental outside tap/back
            // shouldn't silently drop a run that's genuinely still in flight.
            onDismissRequest = { if (!isWorking) dismissManualRunDialog() }
        ) {
            Card {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(manualRunResultTitle ?: "", style = MaterialTheme.typography.titleMedium)
                    if (isWorking) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 16.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                            Text("Working on it…", style = MaterialTheme.typography.bodyMedium)
                        }
                        Button(
                            onClick = {
                                manualRunJob?.cancel()
                                manualRunWorkName?.let { WorkManager.getInstance(context).cancelUniqueWork(it) }
                                dismissManualRunDialog()
                            },
                            modifier = Modifier.padding(top = 16.dp)
                        ) { Text("Abort") }
                    } else {
                        Text(
                            manualRunResultText ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        Button(
                            onClick = { dismissManualRunDialog() },
                            modifier = Modifier.padding(top = 16.dp)
                        ) { Text("OK") }
                    }
                }
            }
        }
    }

    if (showClassificationLog) {
        Dialog(onDismissRequest = { showClassificationLog = false }) {
            Card {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Email Classification Log", style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { showClassificationLog = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                    if (classificationLogEntries.isEmpty()) {
                        Text(
                            "No classification log entries yet - run email cleanup first.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    } else {
                        LazyColumn(modifier = Modifier.height(400.dp).padding(top = 8.dp)) {
                            items(classificationLogEntries) { entry ->
                                Text(
                                    text = "[${entry.category}] ${entry.timestamp} - \"${entry.subject}\" from ${entry.from}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (isEditingAllowlist) {
        ListEditorDialog(
            title = "Promotion filter allowlist (never filed as promotion/junk)",
            hint = "One per line, e.g. MyHeritage / Maven / Jonathan Jobe",
            draftText = allowlistDraft,
            onDraftChange = { allowlistDraft = it },
            isEditMode = allowlistEditMode,
            onEnterEditMode = { allowlistEditMode = true },
            onSave = {
                scope.launch {
                    val names = allowlistDraft.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    promotionAllowlistStore.setAllowlistedSenders(names)
                    allowlistText = names.joinToString("\n")
                    isEditingAllowlist = false
                    backupSettingsToDrive()
                }
            },
            onCancel = { isEditingAllowlist = false }
        )
    }

    if (isEditingBlacklist) {
        ListEditorDialog(
            title = "Junk blacklist (always filed as junk)",
            hint = "One sender name/company per line",
            draftText = blacklistDraft,
            onDraftChange = { blacklistDraft = it },
            isEditMode = blacklistEditMode,
            onEnterEditMode = { blacklistEditMode = true },
            onSave = {
                scope.launch {
                    val names = blacklistDraft.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    junkBlacklistStore.setBlacklistedSenders(names)
                    blacklistText = names.joinToString("\n")
                    isEditingBlacklist = false
                    backupSettingsToDrive()
                }
            },
            onCancel = { isEditingBlacklist = false }
        )
    }

    if (isEditingNamedLocations) {
        ListEditorDialog(
            title = "Named locations (short names for travel-time questions, e.g. \"Home\", \"FCZ Training\")",
            hint = "One per line: Name = Address",
            draftText = namedLocationsDraft,
            onDraftChange = { namedLocationsDraft = it },
            isEditMode = namedLocationsEditMode,
            onEnterEditMode = { namedLocationsEditMode = true },
            onSave = {
                scope.launch {
                    val parsed = namedLocationsDraft.lines().mapNotNull { line ->
                        val idx = line.indexOf('=')
                        if (idx < 0) return@mapNotNull null
                        val name = line.substring(0, idx).trim()
                        val address = line.substring(idx + 1).trim()
                        if (name.isNotEmpty() && address.isNotEmpty()) name to address else null
                    }.toMap()
                    namedLocationsStore.setAll(parsed)
                    namedLocationsText = parsed.entries.joinToString("\n") { "${it.key} = ${it.value}" }
                    isEditingNamedLocations = false
                    backupSettingsToDrive()
                }
            },
            onCancel = { isEditingNamedLocations = false }
        )
    }

    if (isEditingApiKey) {
        Dialog(onDismissRequest = { isEditingApiKey = false }) {
            Card {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Anthropic API Key", style = MaterialTheme.typography.titleMedium)
                    if (!hasApiKey) {
                        Text(
                            "No key set yet — get a free one at console.anthropic.com, then add it below.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    } else {
                        Text(
                            apiKeyMaskedDisplay ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        modifier = Modifier.padding(top = 12.dp),
                        label = { Text("Anthropic API key (sk-ant-…)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    Row(modifier = Modifier.padding(top = 12.dp)) {
                        Button(
                            enabled = apiKeyInput.isNotBlank(),
                            onClick = {
                                scope.launch {
                                    apiKeyStore.setApiKey(apiKeyInput.trim())
                                    apiKeyInput = ""
                                    apiKeyMaskedDisplay = apiKeyStore.getMaskedApiKey()
                                    isEditingApiKey = false
                                }
                            }
                        ) { Text("Save API Key") }
                        if (hasApiKey) {
                            Button(
                                modifier = Modifier.padding(start = 8.dp),
                                onClick = {
                                    scope.launch {
                                        apiKeyStore.clearApiKey()
                                        apiKeyMaskedDisplay = null
                                        isEditingApiKey = false
                                    }
                                }
                            ) { Text("Remove Key") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Shared editor for the three plain-text-list settings (allowlist/blacklist/named locations).
 * Two states, not one unconstrained text field: a scrollable read-only list (tap it to start
 * editing) and a scrollable text-edit view - both height-bounded with their own
 * [androidx.compose.foundation.verticalScroll]/[LazyColumn], so a scroll gesture always scrolls
 * rather than landing on the text field as a selection drag. That's what the single
 * unconstrained-height `OutlinedTextField` this replaced actually did: with no scrollable
 * container of its own, a swipe meant to scroll the list was interpreted as a text-selection
 * drag instead, and could wipe the field's contents outright. [draftText] is a working copy the
 * caller owns separately from the last-saved value, so Cancel can discard it untouched.
 */
@Composable
private fun ListEditorDialog(
    title: String,
    hint: String,
    draftText: String,
    onDraftChange: (String) -> Unit,
    isEditMode: Boolean,
    onEnterEditMode: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = onCancel) {
        Card {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (isEditMode) {
                    Column(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        OutlinedTextField(
                            value = draftText,
                            onValueChange = onDraftChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(hint) }
                        )
                    }
                } else {
                    val lines = draftText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    Column(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .heightIn(max = 360.dp)
                            .clickable { onEnterEditMode() }
                    ) {
                        if (lines.isEmpty()) {
                            Text(
                                "(empty) — tap to add entries",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            LazyColumn {
                                items(lines) { line ->
                                    Text(
                                        line,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                Row(modifier = Modifier.padding(top = 16.dp)) {
                    Button(onClick = onSave) { Text("Save") }
                    Button(onClick = onCancel, modifier = Modifier.padding(start = 8.dp)) { Text("Cancel") }
                }
            }
        }
    }
}
