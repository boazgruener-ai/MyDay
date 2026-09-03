package ch.boazgruener.myday.briefing

import ch.boazgruener.myday.anthropic.AnthropicClient
import ch.boazgruener.myday.calendar.CalendarEvent
import ch.boazgruener.myday.calendar.CalendarRepository
import ch.boazgruener.myday.calendar.isPast
import ch.boazgruener.myday.gmail.GmailRepository
import ch.boazgruener.myday.gmail.MessageMetadata
import ch.boazgruener.myday.gmail.headerValue
import ch.boazgruener.myday.location.DeviceLocation
import ch.boazgruener.myday.weather.CurrentWeather
import ch.boazgruener.myday.weather.HomeLocation
import ch.boazgruener.myday.weather.OpenMeteoClient
import ch.boazgruener.myday.weather.weatherCodeDescription
import java.time.OffsetDateTime

/**
 * Orchestrates the daily brief: fetch weather + today's calendar + last-24h Gmail, then have
 * Claude synthesize it into a natural spoken script. Read-only - no email classification/filing
 * actions here. Never includes a greeting itself - that's spoken separately by whoever calls
 * this (WakeWordForegroundService greets on every wake regardless of whether a brief follows),
 * so a brief requested mid-conversation doesn't repeat a "Good evening, Boaz" that already happened.
 */
class DailyBriefingUseCase(
    private val openMeteoClient: OpenMeteoClient,
    private val calendarRepository: CalendarRepository,
    private val gmailRepository: GmailRepository,
    private val anthropicClient: AnthropicClient
) {
    suspend fun buildBriefing(accessToken: String, location: DeviceLocation? = null): String {
        val weather = if (location != null) {
            openMeteoClient.getWeather(location.latitude, location.longitude)
        } else {
            openMeteoClient.getWeather()
        }
        val events = calendarRepository.getTodayEvents(accessToken)
        val messages = gmailRepository.getRecentMessages(accessToken)

        return anthropicClient.generateBriefing(buildPrompt(weather, events, messages, isHomeLocation = location == null))
    }

    private fun buildPrompt(
        weather: CurrentWeather,
        events: List<CalendarEvent>,
        messages: List<MessageMetadata>,
        isHomeLocation: Boolean
    ): String {
        val weatherLine = "${weather.temperatureC.toInt()}°C, ${weatherCodeDescription(weather.weatherCode)}, " +
            "humidity ${weather.relativeHumidityPercent}%, wind ${weather.windSpeedKmh.toInt()} km/h"

        val eventsBlock = buildEventsBlock(events)

        val emailsBlock = if (messages.isEmpty()) {
            "No emails in the last 24 hours."
        } else {
            messages.joinToString("\n") { m ->
                val from = m.headerValue("From") ?: "unknown sender"
                val subject = m.headerValue("Subject") ?: "no subject"
                "- Subject: \"$subject\" From: $from"
            }
        }

        return """
            You are Myday, a personal voice assistant giving Boaz his daily brief - this can
            happen at any time of day, whenever he asks for it, not just mornings. Don't open
            with a greeting - Boaz has already been greeted separately, before this was even
            requested.
            Speak naturally, as if reading this aloud - no headers, no markdown, no bullet points, no bold text.
            Keep it concise, roughly 30-45 seconds of spoken audio.
            Only state facts present in the data below - never invent details.
            Structure: first the weather, then today's calendar, then anything genuinely
            important from the recent emails (skip routine newsletters/promotions/automated notifications
            unless something looks time-sensitive or personal).

            Weather ${if (isHomeLocation) "at home in ${HomeLocation.CITY}" else "at Boaz's current location"}: $weatherLine

            $eventsBlock

            Emails from the last 24 hours:
            $emailsBlock
        """.trimIndent()
    }

    /**
     * Splits today's events into an "already happened" count (details deliberately omitted -
     * Boaz doesn't want every already-passed meeting read out individually, just how many there
     * were) and a detailed list of what's still ahead.
     */
    private fun buildEventsBlock(events: List<CalendarEvent>): String {
        if (events.isEmpty()) return "No events on the calendar today."

        val now = OffsetDateTime.now()
        val (past, upcoming) = events.partition { it.isPast(now) }
        return buildString {
            if (past.isNotEmpty()) {
                append("Boaz already had ${past.size} meeting${if (past.size != 1) "s" else ""} earlier today ")
                append("(details deliberately omitted - just mention the count, don't list them individually).\n")
            }
            if (upcoming.isEmpty()) {
                append("No more meetings remaining today.")
            } else {
                append(if (past.isNotEmpty()) "Remaining meetings today:\n" else "Today's calendar events:\n")
                append(upcoming.joinToString("\n") { e ->
                    val time = e.start?.dateTime ?: e.start?.date ?: "?"
                    "- ${e.summary ?: "(untitled)"} at $time"
                })
            }
        }
    }
}
