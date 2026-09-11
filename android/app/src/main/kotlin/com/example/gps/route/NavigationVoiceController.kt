package com.example.gps.route

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Conservative turn-by-turn speech layer for LaneGPS.
 *
 * It speaks only when the maneuver changes or the driver crosses useful
 * approach-distance thresholds. Settings are local to the device.
 */
class NavigationVoiceController(
    context: Context,
    private val onStateChanged: (State) -> Unit = {},
) : TextToSpeech.OnInitListener {
    data class VoiceOption(
        val id: String,
        val label: String,
    )

    data class State(
        val ready: Boolean,
        val muted: Boolean,
        val voices: List<VoiceOption>,
        val selectedVoiceId: String?,
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val tts = TextToSpeech(appContext, this)

    private var ready = false
    private var voiceOptions: List<VoiceOption> = emptyList()
    private var lastManeuverKey: String? = null
    private var lastDistanceBucket: Int? = null
    private var arrivalSpoken = false

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.US
            refreshVoices()
            applySavedVoice()
        }
        publishState()
    }

    fun state(): State = State(
        ready = ready,
        muted = prefs.getBoolean(KEY_MUTED, false),
        voices = voiceOptions,
        selectedVoiceId = prefs.getString(KEY_VOICE_ID, null),
    )

    fun setMuted(muted: Boolean) {
        prefs.edit().putBoolean(KEY_MUTED, muted).apply()
        if (muted) tts.stop()
        publishState()
    }

    fun selectVoice(id: String?) {
        if (id.isNullOrBlank()) {
            prefs.edit().remove(KEY_VOICE_ID).apply()
            if (ready) tts.language = Locale.US
        } else {
            prefs.edit().putString(KEY_VOICE_ID, id).apply()
            if (ready) {
                findVoice(id)?.let { tts.voice = it }
            }
        }
        publishState()
    }

    fun resetRoute() {
        lastManeuverKey = null
        lastDistanceBucket = null
        arrivalSpoken = false
    }

    fun announceRerouting() {
        speak("Rerouting.", "rerouting")
    }

    fun onRouteStarted(route: OpenRouteClient.RouteSummary) {
        resetRoute()
        maybeSpeak(route, force = true)
    }

    fun onProgress(route: OpenRouteClient.RouteSummary) {
        maybeSpeak(route, force = false)
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private fun maybeSpeak(route: OpenRouteClient.RouteSummary, force: Boolean) {
        if (!ready || state().muted) return

        if (route.arrived) {
            if (!arrivalSpoken) {
                arrivalSpoken = true
                speak("You have arrived at your destination.", "arrival")
            }
            return
        }

        val maneuver = route.nextManeuver.trim()
        if (maneuver.isBlank()) return
        val road = route.nextRoad.trim()
        val maneuverKey = "$maneuver|$road"
        val changed = maneuverKey != lastManeuverKey
        val bucket = distanceBucket(route.nextManeuverDistanceMeters)
        val crossedThreshold = bucket != null && bucket != lastDistanceBucket

        if (force || changed || crossedThreshold) {
            lastManeuverKey = maneuverKey
            lastDistanceBucket = bucket
            val distance = spokenDistance(route.nextManeuverDistanceMeters)
            val roadPhrase = if (road.isNotBlank() && !maneuver.contains(road, ignoreCase = true)) {
                " onto $road"
            } else {
                ""
            }
            val instruction = when {
                route.nextManeuverDistanceMeters <= 55.0 -> "$maneuver$roadPhrase now."
                distance.isNotBlank() -> "In $distance, $maneuver$roadPhrase."
                else -> "$maneuver$roadPhrase."
            }
            speak(instruction, "maneuver-${maneuverKey.hashCode()}-${bucket ?: -1}")
        }
    }

    /**
     * Buckets only get smaller while approaching a maneuver. A new maneuver key
     * resets the threshold state, so we do not repeat every GPS update.
     */
    private fun distanceBucket(meters: Double): Int? = when {
        !meters.isFinite() -> null
        meters <= 55.0 -> 0
        meters <= 125.0 -> 1
        meters <= 300.0 -> 2
        meters <= 805.0 -> 3
        else -> 4
    }

    private fun spokenDistance(meters: Double): String {
        if (!meters.isFinite() || meters < 0.0) return ""
        val feet = meters * 3.28084
        return when {
            meters < 70.0 -> "about ${(feet / 25.0).roundToInt() * 25} feet"
            meters < 402.0 -> "about ${(feet / 50.0).roundToInt() * 50} feet"
            else -> {
                val miles = meters / 1609.344
                when {
                    miles < 0.75 -> "about half a mile"
                    miles < 1.25 -> "about one mile"
                    else -> "about ${miles.roundToInt()} miles"
                }
            }
        }
    }

    private fun speak(text: String, utteranceId: String) {
        if (!ready || state().muted) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun refreshVoices() {
        val english = tts.voices.orEmpty()
            .filter { it.locale?.language.equals("en", ignoreCase = true) }
            .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { it.locale.toLanguageTag() }, { it.name }))

        voiceOptions = buildList {
            add(VoiceOption(DEFAULT_VOICE_ID, "System default"))
            english.take(MAX_VOICES).forEachIndexed { index, voice ->
                val region = voice.locale.displayCountry.takeIf { it.isNotBlank() }
                val local = if (voice.isNetworkConnectionRequired) "online" else "device"
                val label = buildString {
                    append("Voice ${index + 1}")
                    if (region != null) append(" · $region")
                    append(" · $local")
                }
                add(VoiceOption(voice.name, label))
            }
        }
    }

    private fun applySavedVoice() {
        val id = prefs.getString(KEY_VOICE_ID, null) ?: return
        if (id == DEFAULT_VOICE_ID) return
        findVoice(id)?.let { tts.voice = it }
    }

    private fun findVoice(id: String): Voice? =
        tts.voices.orEmpty().firstOrNull { it.name == id }

    private fun publishState() {
        onStateChanged(state())
    }

    companion object {
        const val DEFAULT_VOICE_ID = "__default__"
        private const val PREFS_NAME = "lane_gps_voice"
        private const val KEY_MUTED = "muted"
        private const val KEY_VOICE_ID = "voice_id"
        private const val MAX_VOICES = 8
    }
}
