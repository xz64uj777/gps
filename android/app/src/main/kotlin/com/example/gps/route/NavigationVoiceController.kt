package com.example.gps.route

import com.example.gps.laneengine.VoicePromptGate
import android.os.SystemClock
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
        val group: String = "Defaults & effects",
    )

    data class State(
        val ready: Boolean,
        val muted: Boolean,
        val voices: List<VoiceOption>,
        val selectedVoiceId: String?,
        val mode: VoiceMode = VoiceMode.NORMAL,
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val tts = TextToSpeech(appContext, this)

    private var ready = false
    private var voiceOptions: List<VoiceOption> = emptyList()
    private val promptGate = VoicePromptGate()
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
        muted = voiceMode() == VoiceMode.MUTE,
        mode = voiceMode(),
        voices = voiceOptions,
        selectedVoiceId = prefs.getString(KEY_VOICE_ID, DEFAULT_VOICE_ID),
    )

    private fun voiceMode(): VoiceMode = runCatching {
        VoiceMode.valueOf(prefs.getString("voice_mode", null)
            ?: if (prefs.getBoolean(KEY_MUTED, false)) "MUTE" else "NORMAL")
    }.getOrDefault(VoiceMode.NORMAL)

    fun setMode(mode: VoiceMode) {
        prefs.edit().putString("voice_mode", mode.name).putBoolean(KEY_MUTED, mode == VoiceMode.MUTE).apply()
        tts.stop()
        publishState()
    }

    fun cycleMode() = setMode(when (voiceMode()) {
        VoiceMode.NORMAL -> VoiceMode.ALERTS_ONLY
        VoiceMode.ALERTS_ONLY -> VoiceMode.MUTE
        VoiceMode.MUTE -> VoiceMode.NORMAL
    })

    fun selectVoice(id: String?) {
        val selected = id?.takeIf { it.isNotBlank() } ?: DEFAULT_VOICE_ID
        prefs.edit().putString(KEY_VOICE_ID, selected).apply()
        if (ready) {
            tts.setPitch(if (selected == CINEMATIC_VOICE_ID) 0.85f else 1f)
            tts.setSpeechRate(if (selected == CINEMATIC_VOICE_ID) 0.92f else 1f)
            if (selected == DEFAULT_VOICE_ID || selected == CINEMATIC_VOICE_ID) {
                tts.language = Locale.US
                tts.defaultVoice?.let { tts.voice = it }
            } else {
                findVoice(selected)?.let { tts.voice = it }
            }
        }
        publishState()
    }

    fun previewSelectedVoice() {
        speak("This is your navigation voice. In one mile, take the next exit.", "voice-preview")
    }

    fun resetRoute() {
        promptGate.reset()
        arrivalSpoken = false
    }

    fun announceRerouting() {
        speak("Rerouting.", "rerouting")
    }

    fun onRouteStarted(route: OpenRouteClient.RouteSummary) {
        promptGate.reset()
        val sameRoute = prefs.getLong("announced_route", -1L) == route.routeStartedAtMillis
        arrivalSpoken = sameRoute && prefs.getBoolean("arrival_spoken", false)
        if (sameRoute) {
            val phases = runCatching { org.json.JSONObject(prefs.getString("announced_phases", "{}") ?: "{}") }
                .getOrDefault(org.json.JSONObject())
            promptGate.restore(phases.keys().asSequence().associateWith { phases.optInt(it, 3) })
        } else {
            prefs.edit().putLong("announced_route", route.routeStartedAtMillis)
                .putString("announced_phases", "{}").putBoolean("arrival_spoken", false).apply()
        }
        maybeSpeak(route, force = false)
    }

    fun onProgress(route: OpenRouteClient.RouteSummary, speedMps: Double = 0.0) {
        maybeSpeak(route, force = false, speedMps = speedMps)
    }

    fun stopSpeaking() { tts.stop() }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private fun maybeSpeak(route: OpenRouteClient.RouteSummary, force: Boolean, speedMps: Double = 0.0) {
        if (!ready || state().muted) return

        if (route.arrived) {
            if (!arrivalSpoken) {
                arrivalSpoken = true
                prefs.edit().putBoolean("arrival_spoken", true).apply()
                speak(route.destinationSide?.let { "Your destination is on the $it." }
                    ?: "You have arrived at your destination.", "arrival")
            }
            return
        }

        if (voiceMode() != VoiceMode.NORMAL) return
        val maneuver = route.nextManeuver.trim()
        if (maneuver.isBlank() || maneuver == "Continue" || maneuver == "Continue straight") return
        val road = route.nextRoad.trim()
        val upcoming = route.maneuvers.filter { it.label == maneuver && it.road == road }
            .minByOrNull { kotlin.math.abs(it.routeIndex - route.progressIndex) }
        val maneuverKey = "$maneuver|$road|${upcoming?.lat}|${upcoming?.lon}"
        if (promptGate.shouldSpeak(maneuverKey, route.nextManeuverDistanceMeters,
                SystemClock.elapsedRealtime(), force, speedMps, highwayExit = "exit" in maneuver.lowercase(Locale.US))) {
            prefs.edit().putString("announced_phases", org.json.JSONObject(promptGate.snapshot()).toString()).apply()
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
            speak(instruction, "maneuver-${maneuverKey.hashCode()}-${if (route.nextManeuverDistanceMeters <= 55.0) 0 else 1}")
        }
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
        if (!ready || !voiceMode().allows(utteranceId)) return
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        NavigationVoiceTelemetryRuntime.publishSpeakAttempt(text, result)
    }

    private fun refreshVoices() {
        val available = tts.voices.orEmpty()
            .filter { voice ->
                val locale = voice.locale ?: return@filter false
                val country = locale.country.uppercase(Locale.US)
                val language = locale.language.lowercase(Locale.US)
                country !in HIDDEN_COUNTRIES && language.isNotBlank()
            }
            .sortedWith(
                compareBy<Voice>(
                    { voicePriority(it.locale) },
                    { it.isNetworkConnectionRequired },
                    { it.locale.toLanguageTag() },
                    { it.name },
                )
            )

        voiceOptions = buildList {
            add(VoiceOption(DEFAULT_VOICE_ID, "System default"))
            add(VoiceOption(CINEMATIC_VOICE_ID, "Cinematic narrator · voice effect"))
            val selected = prefs.getString(KEY_VOICE_ID, DEFAULT_VOICE_ID)
            available.groupBy { it.locale.language to it.locale.country }.values.forEach { group ->
                val chosen = VoiceShortlist.select(group, { it.name }, selected)
                chosen.forEachIndexed { index, voice ->
                    val locale = voice.locale
                    val country = locale.getDisplayCountry(Locale.US).ifBlank { "International" }
                    val language = locale.getDisplayLanguage(Locale.US)
                    val source = if (voice.isNetworkConnectionRequired) "online" else "device"
                    val gender = VoiceShortlist.gender(voice.name)
                    add(VoiceOption(voice.name, "$gender ${index + 1} · $source", "$language · $country"))
                }
            }
        }
    }

    private fun voicePriority(locale: Locale?): Int {
        if (locale == null) return 99
        val country = locale.country.uppercase(Locale.US)
        val language = locale.language.lowercase(Locale.US)
        return when {
            language == "en" && country == "IE" -> 0
            language == "en" && country == "GB" -> 1
            language == "pl" || country == "PL" -> 2
            language == "en" && country == "AU" -> 3
            language == "en" && country == "NZ" -> 4
            language == "en" && country == "CA" -> 5
            language == "en" && country == "US" -> 6
            language == "en" -> 7
            else -> 8
        }
    }

    private fun applySavedVoice() {
        val id = prefs.getString(KEY_VOICE_ID, DEFAULT_VOICE_ID) ?: DEFAULT_VOICE_ID
        if (id == DEFAULT_VOICE_ID || id == CINEMATIC_VOICE_ID) {
            tts.setPitch(if (id == CINEMATIC_VOICE_ID) 0.85f else 1f)
            tts.setSpeechRate(if (id == CINEMATIC_VOICE_ID) 0.92f else 1f)
            tts.defaultVoice?.let { tts.voice = it }
            return
        }
        val saved = findVoice(id)?.takeIf { it.locale.country.uppercase(Locale.US) !in HIDDEN_COUNTRIES }
        if (saved != null) {
            tts.voice = saved
        } else {
            prefs.edit().putString(KEY_VOICE_ID, DEFAULT_VOICE_ID).apply()
            tts.language = Locale.US
            tts.defaultVoice?.let { tts.voice = it }
        }
    }

    private fun findVoice(id: String): Voice? =
        tts.voices.orEmpty().firstOrNull { it.name == id }

    private fun publishState() {
        val current = state()
        val label = current.voices
            .firstOrNull { it.id == current.selectedVoiceId }
            ?.label
            ?: if (current.selectedVoiceId == DEFAULT_VOICE_ID) "System default" else ""
        NavigationVoiceTelemetryRuntime.publishState(
            ready = current.ready,
            muted = current.muted,
            mode = current.mode.name,
            selectedVoiceId = current.selectedVoiceId,
            selectedVoiceLabel = label,
        )
        onStateChanged(current)
    }

    companion object {
        const val DEFAULT_VOICE_ID = "__default__"
        private const val PREFS_NAME = "lane_gps_voice"
        private const val KEY_MUTED = "muted"
        private const val KEY_VOICE_ID = "voice_id"
        private val HIDDEN_COUNTRIES = setOf("IN", "NG")
        const val CINEMATIC_VOICE_ID = "__cinematic_effect__"
    }
}
