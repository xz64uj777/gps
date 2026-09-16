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
    )

    data class State(
        val ready: Boolean,
        val muted: Boolean,
        val voices: List<VoiceOption>,
        val selectedVoiceId: String?,
    )

    private enum class VoiceGender {
        FEMALE,
        MALE,
        UNKNOWN,
    }

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
        muted = prefs.getBoolean(KEY_MUTED, false),
        voices = voiceOptions,
        selectedVoiceId = prefs.getString(KEY_VOICE_ID, DEFAULT_VOICE_ID),
    )

    fun setMuted(muted: Boolean) {
        prefs.edit().putBoolean(KEY_MUTED, muted).apply()
        if (muted) tts.stop()
        publishState()
    }

    fun selectVoice(id: String?) {
        val selected = id?.takeIf { it.isNotBlank() } ?: DEFAULT_VOICE_ID
        prefs.edit().putString(KEY_VOICE_ID, selected).apply()
        if (ready) {
            if (selected == DEFAULT_VOICE_ID) {
                tts.language = Locale.US
                tts.defaultVoice?.let { tts.voice = it }
            } else {
                findVoice(selected)?.let { tts.voice = it }
            }
        }
        publishState()
    }

    fun resetRoute() {
        promptGate.reset()
        arrivalSpoken = false
    }

    fun announceRerouting() {
        speak("Rerouting.", "rerouting")
    }

    fun onRouteStarted(route: OpenRouteClient.RouteSummary) {
        resetRoute()
        maybeSpeak(route, force = true)
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
                speak("You have arrived at your destination.", "arrival")
            }
            return
        }

        val maneuver = route.nextManeuver.trim()
        if (maneuver.isBlank()) return
        val road = route.nextRoad.trim()
        val upcoming = route.maneuvers.firstOrNull {
            it.routeIndex >= route.progressIndex && it.label == maneuver && it.road == road
        }
        val maneuverKey = "$maneuver|$road|${upcoming?.lat}|${upcoming?.lon}"
        if (promptGate.shouldSpeak(
                maneuverKey,
                route.nextManeuverDistanceMeters,
                SystemClock.elapsedRealtime(),
                force,
                speedMps,
            )
        ) {
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
            speak(
                instruction,
                "maneuver-${maneuverKey.hashCode()}-${if (route.nextManeuverDistanceMeters <= 55.0) 0 else 1}",
            )
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
        if (!ready || state().muted) return
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        NavigationVoiceTelemetryRuntime.publishSpeakAttempt(text, result)
    }

    private fun refreshVoices() {
        val available = tts.voices.orEmpty()
            .filter { voice ->
                val locale = voice.locale ?: return@filter false
                val country = locale.country.uppercase(Locale.US)
                val language = locale.language.lowercase(Locale.US)
                country !in HIDDEN_COUNTRIES && language in SUPPORTED_VOICE_LANGUAGES
            }
            .sortedWith(
                compareBy<Voice>(
                    { voicePriority(it.locale) },
                    { it.isNetworkConnectionRequired },
                    { it.locale.toLanguageTag() },
                    { it.name },
                )
            )

        // Android does not expose standardized gender metadata for TTS voices.
        // When an engine includes gender in its voice name/features we use it;
        // otherwise the voice remains neutral. Either way, cap each nationality
        // at eight useful choices so the menu stays readable instead of dumping
        // every engine variant onto the driver.
        val curated = available
            .groupBy { voiceGroupKey(it.locale) }
            .entries
            .sortedWith(
                compareBy<Map.Entry<String, List<Voice>>>(
                    { voicePriority(it.value.firstOrNull()?.locale) },
                    { it.key },
                )
            )
            .flatMap { curateLocaleVoices(it.value) }

        val labelCounters = mutableMapOf<String, Int>()
        voiceOptions = buildList {
            add(VoiceOption(DEFAULT_VOICE_ID, "System default"))
            curated.forEach { voice ->
                val locale = voice.locale
                val languageLabel = when (locale.language.lowercase(Locale.US)) {
                    "pl" -> "Polish"
                    else -> "English"
                }
                val country = locale.getDisplayCountry(Locale.US).ifBlank {
                    locale.country.ifBlank { "International" }
                }
                val source = if (voice.isNetworkConnectionRequired) "online" else "device"
                val gender = inferGender(voice)
                val bucket = "${voiceGroupKey(locale)}:${gender.name}"
                val number = (labelCounters[bucket] ?: 0) + 1
                labelCounters[bucket] = number
                val voiceLabel = when (gender) {
                    VoiceGender.FEMALE -> "Female $number"
                    VoiceGender.MALE -> "Male $number"
                    VoiceGender.UNKNOWN -> "Voice $number"
                }
                add(
                    VoiceOption(
                        voice.name,
                        "$languageLabel · $country · $voiceLabel · $source",
                    )
                )
            }
        }
    }

    private fun curateLocaleVoices(voices: List<Voice>): List<Voice> {
        val sorted = voices.sortedWith(
            compareBy<Voice>(
                { it.isNetworkConnectionRequired },
                { it.name },
            )
        )
        val female = sorted.filter { inferGender(it) == VoiceGender.FEMALE }.take(MAX_PER_GENDER)
        val male = sorted.filter { inferGender(it) == VoiceGender.MALE }.take(MAX_PER_GENDER)

        val selected = LinkedHashSet<Voice>()
        repeat(MAX_PER_GENDER) { index ->
            female.getOrNull(index)?.let(selected::add)
            male.getOrNull(index)?.let(selected::add)
        }
        sorted.forEach { voice ->
            if (selected.size < MAX_PER_LOCALE) selected.add(voice)
        }
        return selected.take(MAX_PER_LOCALE)
    }

    private fun inferGender(voice: Voice): VoiceGender {
        val signature = buildString {
            append(voice.name)
            append(' ')
            append(voice.features.orEmpty().joinToString(" "))
        }.lowercase(Locale.US)

        return when {
            FEMALE_HINT.containsMatchIn(signature) -> VoiceGender.FEMALE
            MALE_HINT.containsMatchIn(signature) -> VoiceGender.MALE
            else -> VoiceGender.UNKNOWN
        }
    }

    private fun voiceGroupKey(locale: Locale?): String {
        if (locale == null) return "zz"
        return "${locale.language.lowercase(Locale.US)}-${locale.country.uppercase(Locale.US)}"
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
        if (id == DEFAULT_VOICE_ID) {
            tts.defaultVoice?.let { tts.voice = it }
            return
        }
        val optionStillVisible = voiceOptions.any { it.id == id }
        val saved = if (optionStillVisible) findVoice(id) else null
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
        private const val MAX_PER_GENDER = 4
        private const val MAX_PER_LOCALE = 8
        private val HIDDEN_COUNTRIES = setOf("IN", "NG")
        private val SUPPORTED_VOICE_LANGUAGES = setOf("en", "pl")
        private val FEMALE_HINT = Regex(
            "(^|[^a-z])female([^a-z]|$)|(^|[^a-z])woman([^a-z]|$)|(^|[^a-z])girl([^a-z]|$)",
        )
        private val MALE_HINT = Regex(
            "(^|[^a-z])male([^a-z]|$)|(^|[^a-z])man([^a-z]|$)|(^|[^a-z])boy([^a-z]|$)",
        )
    }
}
