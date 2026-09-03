package ch.boazgruener.myday.work

/**
 * Output-data keys workers use to report what they actually did, so the "Run Now" buttons in
 * MainActivity can show a real result instead of leaving Boaz guessing whether a tap did
 * anything.
 */
object WorkResultKeys {
    /** Boolean - true if the run was skipped because Google auth needs interactive re-consent. */
    const val NEEDS_REAUTH = "needs_reauth"

    // EmailCleanupWorker
    const val PROCESSED = "processed"
    const val PROMOTIONS = "promotions"
    const val JUNK = "junk"
    const val PAYMENTS = "payments"

    // MeetingEmailCleanupWorker
    const val ARCHIVED = "archived"
    const val CANDIDATE_EMAILS = "candidate_emails"
    const val UNPARSEABLE = "unparseable"

    // MeetingTravelWorker
    const val ALERTS_SENT = "alerts_sent"
    const val TOTAL_EVENTS = "total_events"
    const val HAS_LOCATION_FIX = "has_location_fix"
}
