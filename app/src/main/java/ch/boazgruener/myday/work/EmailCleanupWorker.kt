/**
 * WorkManager job that auto-classifies recent inbox mail into junk/promotion/payment and files
 * it accordingly, and harvests correspondent names for speech-recognition biasing.
 */
package ch.boazgruener.myday.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ch.boazgruener.myday.MydayApplication
import ch.boazgruener.myday.anthropic.AnthropicClient
import ch.boazgruener.myday.auth.BackgroundGoogleAuth
import ch.boazgruener.myday.gmail.ClassificationLogEntry
import ch.boazgruener.myday.gmail.ClassificationLogStore
import ch.boazgruener.myday.gmail.ContactHintsStore
import ch.boazgruener.myday.gmail.GmailLabels
import ch.boazgruener.myday.gmail.GmailRepository
import ch.boazgruener.myday.gmail.JunkBlacklistStore
import ch.boazgruener.myday.gmail.MessageMetadata
import ch.boazgruener.myday.gmail.PromotionAllowlistStore
import ch.boazgruener.myday.gmail.extractDisplayName
import ch.boazgruener.myday.gmail.headerValue
import ch.boazgruener.myday.gmail.isAllowlistedSender
import ch.boazgruener.myday.gmail.isBlacklistedSender
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val TAG = "MydayEmailCleanup"

/**
 * Periodically classifies recent inbox mail and files subscriptions/promotions and junk out of
 * the inbox automatically - this is standing background automation the user explicitly asked
 * for, not an interactive voice command, so it doesn't go through the PRD's "confirm non-read
 * actions" rule (that rule is about ad-hoc spoken requests). Every message it looks at gets
 * tagged "Myday/Reviewed" so later runs never re-classify the same mail.
 *
 * Categorization order, most deterministic first: junk blocklist -> payment-keyword subject match
 * -> promotion allowlist -> LLM (promotion or keep only - "junk" used to be an independent LLM
 * guess, which read as redundant with "promotion" and confusing; it's now purely the blocklist, a
 * deliberate user decision rather than a model guess). Payment-keyword match sits ahead of the
 * promotion allowlist and the LLM call since it's an equally deliberate, deterministic user
 * instruction, just keyed on subject text instead of sender - filing something as a payment
 * record doesn't conflict with the allowlist's actual promise ("never treated as promotion/junk").
 */
class EmailCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as MydayApplication).container

        val authResult = try {
            BackgroundGoogleAuth(applicationContext).authorize()
        } catch (e: Exception) {
            Log.w(TAG, "Background auth failed, will retry later", e)
            return Result.retry()
        }
        val token = authResult.accessToken
        if (authResult.hasResolution() || token == null) {
            Log.w(TAG, "Needs interactive re-consent - skipping this run")
            return Result.success(workDataOf(WorkResultKeys.NEEDS_REAUTH to true))
        }

        return try {
            val counts = runCleanup(
                container.gmailRepository,
                container.anthropicClient,
                container.promotionAllowlistStore,
                container.junkBlacklistStore,
                container.classificationLogStore,
                token
            )
            harvestContactHints(container.gmailRepository, container.contactHintsStore, token)
            if (counts.processed > 0) {
                container.activityLogStore.appendEntry(
                    "Checked ${counts.processed} email(s): ${counts.promotions} filed as promotion, " +
                        "${counts.junk} as junk, ${counts.payments} as payment."
                )
            }
            Result.success(
                workDataOf(
                    WorkResultKeys.PROCESSED to counts.processed,
                    WorkResultKeys.PROMOTIONS to counts.promotions,
                    WorkResultKeys.JUNK to counts.junk,
                    WorkResultKeys.PAYMENTS to counts.payments
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup run failed", e)
            Result.retry()
        }
    }

    private data class CleanupCounts(val processed: Int, val promotions: Int, val junk: Int, val payments: Int)

    /**
     * Feeds recent correspondents' names into [ContactHintsStore] so the speech recognizer can
     * be biased toward them - names Boaz has actually emailed with are exactly the ones
     * speech-to-text tends to mangle (uncommon or foreign names in particular).
     */
    private suspend fun harvestContactHints(gmail: GmailRepository, hints: ContactHintsStore, token: String) {
        try {
            val recent = gmail.search(token, query = "newer_than:30d", maxResults = 50)
            val names = recent.mapNotNull { it.headerValue("From") }.mapNotNull { extractDisplayName(it) }
            hints.mergeNames(names)
        } catch (e: Exception) {
            Log.w(TAG, "Contact hint harvesting failed", e)
        }
    }

    private suspend fun runCleanup(
        gmail: GmailRepository,
        anthropic: AnthropicClient,
        promotionAllowlistStore: PromotionAllowlistStore,
        junkBlacklistStore: JunkBlacklistStore,
        classificationLogStore: ClassificationLogStore,
        token: String
    ): CleanupCounts {
        val messages = gmail.search(
            token,
            query = "in:inbox -label:\"${GmailLabels.REVIEWED}\" newer_than:3d",
            maxResults = 25
        )
        if (messages.isEmpty()) return CleanupCounts(0, 0, 0, 0)

        val allowlistedSenders = promotionAllowlistStore.getAllowlistedSenders()
        val blacklistedSenders = junkBlacklistStore.getBlacklistedSenders()
        val reviewedLabelId = gmail.getOrCreateLabel(token, GmailLabels.REVIEWED)
        val promotionsLabelId = gmail.getOrCreateLabel(token, GmailLabels.PROMOTIONS)
        val junkLabelId = gmail.getOrCreateLabel(token, GmailLabels.JUNK)
        val paymentsLabelId = gmail.getOrCreateLabel(token, GmailLabels.PAYMENTS)

        var promotions = 0
        var junk = 0
        var payments = 0
        val logEntries = mutableListOf<ClassificationLogEntry>()
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

        for (message in messages) {
            val from = message.headerValue("From") ?: ""
            val subject = message.headerValue("Subject") ?: "(no subject)"

            val category = when {
                isBlacklistedSender(from, blacklistedSenders) -> Category.JUNK
                isPaymentRelatedSubject(subject) -> Category.PAYMENT
                isAllowlistedSender(from, allowlistedSenders) -> Category.KEEP
                else -> classify(anthropic, message)
            }

            val addLabels = mutableListOf(reviewedLabelId)
            val removeLabels = mutableListOf<String>()
            val categoryLabel = when (category) {
                Category.PROMOTION -> {
                    addLabels.add(promotionsLabelId)
                    removeLabels.add("INBOX")
                    promotions++
                    "Promotion"
                }
                Category.JUNK -> {
                    addLabels.add(junkLabelId)
                    removeLabels.add("INBOX")
                    junk++
                    "Junk"
                }
                Category.PAYMENT -> {
                    addLabels.add(paymentsLabelId)
                    removeLabels.add("INBOX")
                    payments++
                    "Payment"
                }
                Category.KEEP -> "Kept" // just marked reviewed, left in the inbox
            }
            gmail.modifyLabels(token, message.id, addLabelIds = addLabels, removeLabelIds = removeLabels)
            logEntries.add(ClassificationLogEntry(from, subject, categoryLabel, now))
        }
        classificationLogStore.appendEntries(logEntries)
        return CleanupCounts(messages.size, promotions, junk, payments)
    }

    private enum class Category { PROMOTION, JUNK, PAYMENT, KEEP }

    /** Deliberately just these three words on the subject line, not an LLM guess - Boaz asked for
     * exactly this, and financial mail (invoices/receipts/payment confirmations) is exactly the
     * kind of thing that shouldn't depend on a model's judgment call to end up somewhere findable. */
    private val PAYMENT_KEYWORDS = listOf("payment", "invoice", "receipt")

    private fun isPaymentRelatedSubject(subject: String): Boolean =
        PAYMENT_KEYWORDS.any { subject.contains(it, ignoreCase = true) }

    private suspend fun classify(anthropic: AnthropicClient, message: MessageMetadata): Category {
        val from = message.headerValue("From") ?: ""
        val subject = message.headerValue("Subject") ?: ""
        val prompt = """
            Classify this email by its From and Subject only. Reply with exactly one word:
            "promotion" for newsletters, marketing, subscriptions, sales, or bulk automated mail;
            "keep" for anything personal, important, or that you're unsure about.
            From: $from
            Subject: $subject
        """.trimIndent()

        val answer = try {
            anthropic.testMessage(prompt).trim().lowercase()
        } catch (e: Exception) {
            Log.w(TAG, "Classification call failed, defaulting to keep", e)
            return Category.KEEP
        }

        return if (answer.startsWith("promotion")) Category.PROMOTION else Category.KEEP
    }
}
