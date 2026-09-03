package ch.boazgruener.myday

import android.app.Application
import androidx.room.Room
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ch.boazgruener.myday.activitylog.ActivityLogStore
import ch.boazgruener.myday.anthropic.AnthropicClient
import ch.boazgruener.myday.anthropic.ApiKeyStore
import ch.boazgruener.myday.backup.SettingsBackupRepository
import ch.boazgruener.myday.briefing.BriefingStateStore
import ch.boazgruener.myday.briefing.DailyBriefingUseCase
import ch.boazgruener.myday.calendar.CalendarRepository
import ch.boazgruener.myday.command.CommandExecutor
import ch.boazgruener.myday.contacts.ContactsRepository
import ch.boazgruener.myday.gmail.ClassificationLogStore
import ch.boazgruener.myday.gmail.ContactHintsStore
import ch.boazgruener.myday.gmail.GmailRepository
import ch.boazgruener.myday.gmail.JunkBlacklistStore
import ch.boazgruener.myday.gmail.PromotionAllowlistStore
import ch.boazgruener.myday.location.DeviceLocationProvider
import ch.boazgruener.myday.location.NamedLocationsStore
import ch.boazgruener.myday.maps.MapsRepository
import ch.boazgruener.myday.voice.CloudSttListener
import ch.boazgruener.myday.voice.VoiceStateStore
import ch.boazgruener.myday.weather.OpenMeteoClient
import ch.boazgruener.myday.whatsapp.WHATSAPP_MIGRATION_1_2
import ch.boazgruener.myday.whatsapp.WhatsAppDatabase
import ch.boazgruener.myday.whatsapp.WhatsAppReplySender
import ch.boazgruener.myday.whatsapp.WhatsAppRepository
import ch.boazgruener.myday.work.EmailCleanupWorker
import ch.boazgruener.myday.work.MeetingEmailCleanupWorker
import ch.boazgruener.myday.work.MeetingTravelWorker
import ch.boazgruener.myday.work.SchedulerStateStore
import java.util.concurrent.TimeUnit

/**
 * Holds app-wide singletons via a plain manual container (no DI framework - see the
 * scaffolding plan's reasoning: single module, ~6-8 singletons, not worth Hilt for V1).
 * Repositories/clients (Gmail, Calendar, Anthropic, Open-Meteo, auth) get added here as
 * each build-order slice introduces them.
 */
class MydayApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        scheduleBackgroundWork()
    }

    private fun scheduleBackgroundWork() {
        val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val emailCleanup = PeriodicWorkRequestBuilder<EmailCleanupWorker>(6, TimeUnit.HOURS)
            .setConstraints(networkConstraints)
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("email_cleanup", ExistingPeriodicWorkPolicy.KEEP, emailCleanup)

        val meetingTravelCheck = PeriodicWorkRequestBuilder<MeetingTravelWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints)
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("meeting_travel_check", ExistingPeriodicWorkPolicy.KEEP, meetingTravelCheck)

        val meetingEmailCleanup = PeriodicWorkRequestBuilder<MeetingEmailCleanupWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints)
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("meeting_email_cleanup", ExistingPeriodicWorkPolicy.KEEP, meetingEmailCleanup)
    }
}

class AppContainer(private val app: Application) {
    val openMeteoClient: OpenMeteoClient by lazy { OpenMeteoClient() }
    val calendarRepository: CalendarRepository by lazy { CalendarRepository() }
    val gmailRepository: GmailRepository by lazy { GmailRepository() }
    val apiKeyStore: ApiKeyStore by lazy { ApiKeyStore(app) }
    val anthropicClient: AnthropicClient by lazy { AnthropicClient(apiKeyStore) }
    val mapsRepository: MapsRepository by lazy { MapsRepository() }
    val dailyBriefingUseCase: DailyBriefingUseCase by lazy {
        DailyBriefingUseCase(openMeteoClient, calendarRepository, gmailRepository, anthropicClient)
    }
    val briefingStateStore: BriefingStateStore by lazy { BriefingStateStore(app) }
    val schedulerStateStore: SchedulerStateStore by lazy { SchedulerStateStore(app) }
    val contactHintsStore: ContactHintsStore by lazy { ContactHintsStore(app) }
    val promotionAllowlistStore: PromotionAllowlistStore by lazy { PromotionAllowlistStore(app) }
    val junkBlacklistStore: JunkBlacklistStore by lazy { JunkBlacklistStore(app) }
    val classificationLogStore: ClassificationLogStore by lazy { ClassificationLogStore(app) }
    val activityLogStore: ActivityLogStore by lazy { ActivityLogStore(app) }
    val contactsRepository: ContactsRepository by lazy { ContactsRepository(app) }
    val locationProvider: DeviceLocationProvider by lazy { DeviceLocationProvider(app) }
    val namedLocationsStore: NamedLocationsStore by lazy { NamedLocationsStore(app) }
    val settingsBackupRepository: SettingsBackupRepository by lazy { SettingsBackupRepository() }
    val voiceStateStore: VoiceStateStore by lazy { VoiceStateStore() }
    val cloudSttListener: CloudSttListener by lazy { CloudSttListener(app) }
    val whatsAppDatabase: WhatsAppDatabase by lazy {
        Room.databaseBuilder(app, WhatsAppDatabase::class.java, "whatsapp_messages.db")
            .addMigrations(WHATSAPP_MIGRATION_1_2)
            .build()
    }
    val whatsAppRepository: WhatsAppRepository by lazy { WhatsAppRepository(whatsAppDatabase.whatsAppMessageDao()) }
    val whatsAppReplySender: WhatsAppReplySender by lazy { WhatsAppReplySender(whatsAppRepository) }
    val commandExecutor: CommandExecutor by lazy {
        CommandExecutor(
            calendarRepository, gmailRepository, anthropicClient, contactHintsStore,
            contactsRepository, mapsRepository, locationProvider, namedLocationsStore,
            dailyBriefingUseCase, whatsAppRepository, whatsAppReplySender, activityLogStore
        )
    }
}
