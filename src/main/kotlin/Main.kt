import com.wire.sdk.WireAppSdk
import com.wire.sdk.WireEventsHandlerSuspending
import com.wire.sdk.model.WireMessage
import com.wire.sdk.model.QualifiedId
import java.util.UUID
import kotlin.math.round
import kotlinx.coroutines.*

// Use Java's ArrayDeque for push/pop/peek
import java.util.ArrayDeque as JArrayDeque

// Online HTTP (Java 11+)
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// For weather time handling
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

fun main() {
    val wireAppSdk = WireAppSdk(
        applicationId = UUID.randomUUID(),
        apiToken = "myApiToken", // ← replace
        apiHost = "https://staging-nginz-https.zinfra.io",
        cryptographyStoragePassword = "myDummyPasswordOfRandom32BytesCH",
        wireEventsHandler = AppyEventsHandler()
    )
    wireAppSdk.startListening()
}

class AppyEventsHandler : WireEventsHandlerSuspending() {

    // Coroutine scope for background timers
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Offline fallback jokes
    private val jokes = listOf(
        "🤣 There are 10 types of people: those who understand binary and those who don’t.",
        "😅 Why do Java developers wear glasses? Because they can’t C#!",
        "🧠 Debugging: Removing the needles from the haystack.",
        "🤖 I told my computer I needed a break, and it froze!",
        "😂 I would tell you a UDP joke, but you might not get it."
    )

    // HTTP client for online calls (jokes + weather)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .build()

    override suspend fun onMessage(wireMessage: WireMessage.Text) {
        val text = wireMessage.text.trim()
        val parse = parseMentionAndCommand(text) ?: return  // ignore if Appy not mentioned

        val replyText = when (parse.command.lowercase()) {
            "" -> {
                // NEW: if this @Appy is a reply to someone's message → create a conversation with that person
                val replied = extractQuotedMessage(wireMessage)
                val target = replied?.let { extractSenderQualifiedId(it) }

                if (target != null) {
                    if (!isSelf(target)) {
                        val newConvId = createGroupConversationCompat(listOf(target), name = null)
                        if (newConvId != null) {
                            sendKnock(newConvId, hotKnock = false)
                            sendText(newConvId, "👋 I created this conversation so you two can chat here.")
                            "✅ Created a new conversation with that person. Check your chat list."
                        } else {
                            "⚠️ I couldn’t create a new conversation in this environment."
                        }
                    } else {
                        helpMessage()
                    }
                } else {
                    // Not a reply → just show help (existing behavior)
                    helpMessage()
                }
            }

            "help" -> helpMessage()

            "calc" -> {
                val expr = parse.args
                if (expr.isBlank()) "🧮 Usage: `@Appy calc 2+3*4`"
                else try {
                    val result = evalExpression(expr)
                    "🧮 Result: ${formatNumber(result)}"
                } catch (_: Exception) {
                    "⚠️ Error in expression — are you sure that’s math? 🤔"
                }
            }

            "echo" -> {
                val msg = parse.args
                if (msg.isBlank()) "🦜 Nothing to echo!"
                else "🦜 $msg"
            }

            "joke" -> {
                val online = fetchJokeOnline()
                if (online != null) "${online.first}\n\n_Source: ${online.second}_"
                else jokes.random()
            }

            "weather" -> {
                val query = parse.args.trim()
                if (query.isBlank()) {
                    "🌤️ Usage: `@Appy weather <city>`\nExample: `@Appy weather Berlin`"
                } else {
                    val report = fetchWeatherReport(query)
                    report ?: "🌤️ I couldn’t fetch weather for “$query”. Try a larger city name (e.g., `Berlin`, `Munich`, `Hamburg`)."
                }
            }

            "timer" -> {
                val spec = parseTimerArgs(parse.args)
                if (spec == null) {
                    "⏱️ Usage: `@Appy timer 30s [optional label]`\n" +
                            "Examples: `@Appy timer 1 min tea break`, `@Appy timer 2 days report`"
                } else {
                    // Ack immediately
                    val ack = if (spec.label.isEmpty())
                        "⏳ Timer started for ${spec.human}."
                    else
                        "⏳ Timer started for ${spec.human} — ${spec.label}."
                    // Schedule the real reminder
                    val convoId: QualifiedId = wireMessage.conversationId
                    scope.launch {
                        delay(spec.millis)
                        // 1) Knock/Ping the conversation (your working implementation)
                        sendKnock(convoId, hotKnock = false)
                        // 2) Text confirmation (fallback + clarity)
                        val doneText = if (spec.label.isEmpty())
                            "⏰ Timer done: ${spec.human}!"
                        else
                            "⏰ Timer done: ${spec.human} — ${spec.label}!"
                        sendText(convoId, doneText)
                    }
                    ack
                }
            }

            else -> "🤨 I don’t recognize that command, but I do recognize great taste in bots.\n\n${helpMessage()}"
        }

        // Reply to the triggering message (so the user gets a quote context)
        sendReply(wireMessage, replyText)
    }

    // ---------- Utilities: send replies / text / knocks ----------

    private fun sendReply(original: WireMessage.Text, text: String) {
        val message = WireMessage.Text.createReply(
            conversationId = original.conversationId,
            text = text,
            originalMessage = original,
            mentions = original.mentions
        )
        manager.sendMessage(message)
    }

    private fun sendText(conversationId: QualifiedId, text: String) {
        val msg = WireMessage.Text.create(
            conversationId = conversationId,
            text = text
        )
        manager.sendMessage(msg)
    }

    private fun sendKnock(conversationId: QualifiedId, hotKnock: Boolean) {
        try {
            val knock = WireMessage.Knock.create(
                conversationId = conversationId,
                hotKnock = hotKnock
            )
            manager.sendMessage(knock)
        } catch (_: Throwable) {
            // Some environments may block knocks; ignore silently.
        }
    }

    // ---------- Online joke helpers ----------

    private fun fetchJokeOnline(): Pair<String, String>? {
        fetchJokeFromJokeApi()?.let { return it }   // JokeAPI first
        fetchJokeFromIcanHaz()?.let { return it }   // then icanhazdadjoke
        return null
    }

    private fun fetchJokeFromJokeApi(): Pair<String, String>? {
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("https://v2.jokeapi.dev/joke/Programming?type=single&safe-mode"))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() !in 200..299) return null
            val body = res.body()
            val joke = extractJsonString(body, "joke") ?: return null
            "😂 $joke" to "JokeAPI — https://v2.jokeapi.dev"
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchJokeFromIcanHaz(): Pair<String, String>? {
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("https://icanhazdadjoke.com/"))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET()
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() !in 200..299) return null
            val body = res.body()
            val joke = extractJsonString(body, "joke") ?: return null
            "😂 $joke" to "icanhazdadjoke — https://icanhazdadjoke.com"
        } catch (_: Exception) {
            null
        }
    }

    // ---------- Weather (Open-Meteo, no API key) ----------

    private data class Geo(val lat: Double, val lon: Double, val name: String, val tz: String?)

    private data class WeatherResult(
        val currentLine: String?,
        val laterTodayLines: List<String>,   // Afternoon / Evening / Night (remaining parts today)
        val dailyLines: List<String>         // 10-day forecast with weekday
    )

    private fun fetchWeatherReport(query: String): String? {
        val geo = geocodeCity(query) ?: return null
        val forecast = fetchOpenMeteo(geo.lat, geo.lon, geo.tz)
            ?: return "🌤️ Couldn’t fetch forecast for ${geo.name}."

        val sb = StringBuilder()
        sb.appendLine("🌤️ *Weather for ${geo.name}*")
        forecast.currentLine?.let { sb.appendLine(it) }

        if (forecast.laterTodayLines.isNotEmpty()) {
            sb.appendLine("")
            sb.appendLine("🕒 *Later today*")
            forecast.laterTodayLines.forEach { sb.appendLine(it) }
        }

        sb.appendLine("")
        sb.appendLine("📅 *10-day forecast*")
        forecast.dailyLines.forEach { sb.appendLine(it) }

        sb.appendLine("")
        sb.append("_Source: Open-Meteo — https://open-meteo.com_")
        return sb.toString()
    }

    private fun geocodeCity(query: String): Geo? {
        return try {
            val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
                    java.net.URLEncoder.encode(query, Charsets.UTF_8) +
                    "&count=1&language=en&format=json"
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() !in 200..299) return null
            val body = res.body()

            // {"results":[{"name":"Berlin","latitude":52.52,"longitude":13.405,"timezone":"Europe/Berlin",...}]}
            val first = extractFirstObjectFromArray(body, "results") ?: return null
            val name = extractJsonString(first, "name") ?: query
            val lat = extractJsonNumber(first, "latitude") ?: return null
            val lon = extractJsonNumber(first, "longitude") ?: return null
            val tz  = extractJsonString(first, "timezone")
            Geo(lat, lon, name, tz)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchOpenMeteo(lat: Double, lon: Double, tz: String?): WeatherResult? {
        return try {
            val zone = (tz?.takeIf { it.isNotBlank() } ?: "auto")
            val tzParam = if (zone == "auto") "auto" else java.net.URLEncoder.encode(zone, Charsets.UTF_8)

            // Hourly added for afternoon/evening/night
            val url = buildString {
                append("https://api.open-meteo.com/v1/forecast?")
                append("latitude=$lat&longitude=$lon")
                append("&current=temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m,wind_gusts_10m,wind_direction_10m,relative_humidity_2m")
                append("&hourly=temperature_2m,precipitation_probability,precipitation,weather_code,wind_speed_10m,relative_humidity_2m")
                append("&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,wind_speed_10m_max,sunrise,sunset")
                append("&forecast_days=10")
                append("&timezone=$tzParam")
            }

            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() !in 200..299) return null
            val body = res.body()

            // Local day/hour handling
            val zoneId: ZoneId = runCatching { ZoneId.of(tz ?: "UTC") }.getOrDefault(ZoneId.of("UTC"))
            val todayIsoDate = LocalDate.now(zoneId).toString()
            val nowLocalHour = java.time.ZonedDateTime.now(zoneId).hour

            // ----- Current -----
            val current = extractJsonObject(body, "current")
            val curTemp = current?.let { extractJsonNumber(it, "temperature_2m") }
            val curFeels = current?.let { extractJsonNumber(it, "apparent_temperature") }
            val curHum = current?.let { extractJsonNumber(it, "relative_humidity_2m")?.toInt() }
            val curWind = current?.let { extractJsonNumber(it, "wind_speed_10m") }
            val curPrec = current?.let { extractJsonNumber(it, "precipitation") }
            val curWc   = current?.let { extractJsonNumber(it, "weather_code")?.toInt() }

            val currentLine = if (curTemp != null) {
                val parts = mutableListOf<String>()
                parts += "Now: ${curTemp.round1()}°C"
                if (curFeels != null) parts += "(feels ${curFeels.round1()}°C)"
                if (curHum != null) parts += "💧${curHum}%"
                if (curWind != null) parts += "💨${curWind.round1()} m/s"
                if (curPrec != null && curPrec > 0.0) parts += "🌧️ ${curPrec.round1()} mm"
                val desc = curWc?.let { " – ${weatherCodeToEmoji(it)}" } ?: ""
                "• ${parts.joinToString("  ")}$desc"
            } else null

            // ----- Hourly for “later today” -----
            val hourly = extractJsonObject(body, "hourly")
            val hourlyTimes  = hourly?.let { extractJsonArrayStrings(it, "time") } ?: emptyList()
            val hourlyTemp   = hourly?.let { extractJsonArrayNumbers(it, "temperature_2m") } ?: emptyList()
            val hourlyProb   = hourly?.let { extractJsonArrayNumbers(it, "precipitation_probability") } ?: emptyList()
            val hourlyPrec   = hourly?.let { extractJsonArrayNumbers(it, "precipitation") } ?: emptyList()
            val hourlyWcode  = hourly?.let { extractJsonArrayInts(it, "weather_code") } ?: emptyList()

            data class Slot(val label: String, val hour: Int)
            val slots = listOf(Slot("Afternoon", 12), Slot("Evening", 18), Slot("Night", 22))

            val laterTodayLines = mutableListOf<String>()
            for (slot in slots) {
                if (slot.hour <= nowLocalHour) continue // only future parts today
                // find exact slot for *today* at HH:00
                val idx = hourlyTimes.indexOfFirst { iso ->
                    // iso like 2025-11-11T12:00 (local to requested tz)
                    iso.startsWith(todayIsoDate) && iso.substringAfter('T').take(2).toIntOrNull() == slot.hour
                }
                if (idx >= 0) {
                    val t  = hourlyTemp.getOrNull(idx)?.round1()
                    val pp = hourlyProb.getOrNull(idx)?.toInt()
                    val ps = hourlyPrec.getOrNull(idx)
                    val wc = hourlyWcode.getOrNull(idx)?.let { weatherCodeToEmoji(it) } ?: ""
                    val parts = mutableListOf<String>()
                    if (t != null) parts += "🌡️ ${t}°C"
                    if (pp != null) parts += "☔ ${pp}%"
                    if (ps != null && ps > 0.0) parts += "🌧️ ${ps.round1()} mm"
                    laterTodayLines += "• ${slot.label}: $wc  ${parts.joinToString("  ")}"
                }
            }

            // ----- Daily 10-day -----
            val daily = extractJsonObject(body, "daily")
                ?: return WeatherResult(currentLine, laterTodayLines, emptyList())

            val days  = extractJsonArrayStrings(daily, "time") ?: emptyList()
            val tMax  = extractJsonArrayNumbers(daily, "temperature_2m_max") ?: emptyList()
            val tMin  = extractJsonArrayNumbers(daily, "temperature_2m_min") ?: emptyList()
            val pSum  = extractJsonArrayNumbers(daily, "precipitation_sum") ?: emptyList()
            val pProb = extractJsonArrayNumbers(daily, "precipitation_probability_max") ?: emptyList()
            val wCode = extractJsonArrayInts(daily, "weather_code") ?: emptyList()

            val n = listOf(days.size, tMax.size, tMin.size).maxOrNull() ?: 0
            val dailyLines = ArrayList<String>(n)
            for (i in 0 until n) {
                val dIso = days.getOrNull(i) ?: continue
                val dow  = weekdayAbbrev(dIso, zoneId) // Mon/Tue/...
                val max = tMax.getOrNull(i)?.round1()
                val min = tMin.getOrNull(i)?.round1()
                val ps  = pSum.getOrNull(i)
                val pp  = pProb.getOrNull(i)?.toInt()
                val wc  = wCode.getOrNull(i)?.let { weatherCodeToEmoji(it) } ?: ""

                val parts = mutableListOf<String>()
                if (min != null && max != null) parts += "🌡️ ${min}–${max}°C" else if (max != null) parts += "🌡️ max ${max}°C"
                if (pp != null) parts += "☔ ${pp}%"
                if (ps != null && ps > 0.0) parts += "🌧️ ${ps.round1()} mm"

                val line = "• $dow $dIso $wc  ${parts.joinToString("  ")}"
                dailyLines += line
            }

            WeatherResult(currentLine, laterTodayLines, dailyLines)
        } catch (_: Exception) {
            null
        }
    }

    private fun Double.round1(): String = (kotlin.math.round(this * 10.0) / 10.0).toString()

    private fun weekdayAbbrev(isoDate: String, zoneId: ZoneId): String {
        return try {
            val ld = LocalDate.parse(isoDate)
            ld.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) // Mon, Tue, ...
        } catch (_: Exception) {
            "Day"
        }
    }

    private fun weatherCodeToEmoji(code: Int): String = when (code) {
        0 -> "☀️ Clear"
        1, 2 -> "🌤️ Mostly clear"
        3 -> "☁️ Cloudy"
        45, 48 -> "🌫️ Fog"
        51, 53, 55 -> "🌦️ Drizzle"
        61, 63, 65 -> "🌧️ Rain"
        66, 67 -> "🌧️🌡️ Freezing rain"
        71, 73, 75 -> "🌨️ Snow"
        77 -> "❄️ Snow grains"
        80, 81, 82 -> "🌦️ Showers"
        85, 86 -> "🌨️ Snow showers"
        95 -> "⛈️ Thunderstorm"
        96, 99 -> "⛈️⚠️ Thunderstorm (hail)"
        else -> "🌍 Weather"
    }

    // ---------- Minimal JSON helpers (strings, numbers, arrays, objects) ----------

    // Get top-level or nested object by key name
    private fun extractJsonObject(json: String, field: String): String? {
        val key = "\"$field\""
        val i = json.indexOf(key)
        if (i == -1) return null
        val colon = json.indexOf(':', i + key.length); if (colon == -1) return null
        val start = json.indexOf('{', colon + 1); if (start == -1) return null
        var depth = 0
        var j = start
        while (j < json.length) {
            val ch = json[j]
            if (ch == '{') depth++
            if (ch == '}') {
                depth--
                if (depth == 0) return json.substring(start, j + 1)
            }
            j++
        }
        return null
    }

    private fun extractFirstObjectFromArray(json: String, field: String): String? {
        val key = "\"$field\""
        the_loop@ run {
            val i = json.indexOf(key)
            if (i == -1) return null
            val colon = json.indexOf(':', i + key.length); if (colon == -1) return null
            val start = json.indexOf('[', colon + 1); if (start == -1) return null
            var j = start + 1
            while (j < json.length) {
                when (val ch = json[j]) {
                    '{' -> {
                        var depth = 0
                        var k = j
                        while (k < json.length) {
                            val c = json[k]
                            if (c == '{') depth++
                            if (c == '}') {
                                depth--
                                if (depth == 0) return json.substring(j, k + 1)
                            }
                            k++
                        }
                        return null
                    }
                    ']' -> return null
                }
                j++
            }
        }
        return null
    }

    private fun extractJsonString(json: String, field: String): String? {
        val key = "\"$field\""
        val i = json.indexOf(key)
        if (i == -1) return null
        val colon = json.indexOf(':', i + key.length); if (colon == -1) return null
        val firstQuote = json.indexOf('"', colon + 1); if (firstQuote == -1) return null
        var j = firstQuote + 1
        val sb = StringBuilder()
        var esc = false
        while (j < json.length) {
            val ch = json[j]
            if (esc) {
                when (ch) {
                    '"', '\\', '/' -> sb.append(ch)
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (j + 4 < json.length) {
                            val hex = json.substring(j + 1, j + 5)
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                            j += 4
                        }
                    }
                    else -> sb.append(ch)
                }
                esc = false
            } else {
                if (ch == '\\') esc = true
                else if (ch == '"') break
                else sb.append(ch)
            }
            j++
        }
        return sb.toString()
    }

    private fun extractJsonNumber(json: String, field: String): Double? {
        val key = "\"$field\""
        val i = json.indexOf(key)
        if (i == -1) return null
        val colon = json.indexOf(':', i + key.length); if (colon == -1) return null
        var j = colon + 1
        while (j < json.length && json[j].isWhitespace()) j++
        val start = j
        while (j < json.length && "-+.0123456789eE".contains(json[j])) j++
        return json.substring(start, j).toDoubleOrNull()
    }

    private fun extractJsonArrayStrings(json: String, field: String): List<String>? {
        val raw = extractJsonArrayRaw(json, field) ?: return null
        val out = mutableListOf<String>()
        var i = 0
        while (i < raw.length) {
            while (i < raw.length && raw[i].isWhitespace()) i++
            if (i >= raw.length) break
            if (raw[i] == '"') {
                i++
                val sb = StringBuilder()
                var esc = false
                while (i < raw.length) {
                    val ch = raw[i]
                    if (esc) {
                        when (ch) {
                            '"', '\\', '/' -> sb.append(ch)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 < raw.length) {
                                    val hex = raw.substring(i + 1, i + 5)
                                    sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                                    i += 4
                                }
                            }
                            else -> sb.append(ch)
                        }
                        esc = false
                    } else {
                        if (ch == '\\') esc = true
                        else if (ch == '"') break
                        else sb.append(ch)
                    }
                    i++
                }
                out += sb.toString()
                i++
                while (i < raw.length && (raw[i].isWhitespace() || raw[i] == ',')) i++
            } else {
                while (i < raw.length && raw[i] != ',') i++
                if (i < raw.length && raw[i] == ',') i++
            }
        }
        return out
    }

    private fun extractJsonArrayNumbers(json: String, field: String): List<Double>? {
        val raw = extractJsonArrayRaw(json, field) ?: return null
        return raw.split(',').mapNotNull { it.trim().toDoubleOrNull() }
    }

    private fun extractJsonArrayInts(json: String, field: String): List<Int>? {
        val raw = extractJsonArrayRaw(json, field) ?: return null
        return raw.split(',').mapNotNull { it.trim().toIntOrNull() }
    }

    private fun extractJsonArrayRaw(json: String, field: String): String? {
        val key = "\"$field\""
        val i = json.indexOf(key)
        if (i == -1) return null
        val colon = json.indexOf(':', i + key.length); if (colon == -1) return null
        val start = json.indexOf('[', colon + 1); if (start == -1) return null
        var depth = 0
        var j = start
        while (j < json.length) {
            val ch = json[j]
            if (ch == '[') depth++
            if (ch == ']') {
                depth--
                if (depth == 0) return json.substring(start + 1, j)
            }
            j++
        }
        return null
    }

    // ---------- Mention + command parsing ----------

    private data class Parsed(val command: String, val args: String)

    /**
     * Accepts @Appy, @Appy🤣, @ApPy🤪 etc., then optional "command args".
     * Returns null when the message doesn't address Appy first.
     */
    private fun parseMentionAndCommand(raw: String): Parsed? {
        val s = raw.trim()
        if (!s.startsWith("@")) return null

        val firstTokenEnd = s.indexOfFirst { it.isWhitespace() }.let { if (it == -1) s.length else it }
        val mentionToken = s.substring(0, firstTokenEnd)

        val normalizedName = mentionToken
            .removePrefix("@")
            .lowercase()
            .takeWhile { !it.isWhitespace() }
            .filter { it.isLetter() } // drop emoji/symbols

        if (normalizedName != "appy") return null

        val remainder = s.substring(firstTokenEnd).trim()
        if (remainder.isBlank()) return Parsed("", "")

        val firstSpace = remainder.indexOf(' ')
        return if (firstSpace == -1) Parsed(remainder, "")
        else Parsed(remainder.substring(0, firstSpace), remainder.substring(firstSpace + 1))
    }

    // ---------- Help text ----------
    private fun helpMessage(): String = """
🎩 *Hi, I’m Appy – your tiny sidekick in this conversation!*

I only react when you **mention** me first.

**How to use me**
1. Start your message with `@Appy`  
2. Add a command (optional)  
3. Send it – I’ll reply to your message so it’s clear I’m talking to **you** 🤝

---

🧭 **Quick actions**

• `@Appy`  
  → Show this help.  
  → If you *reply* to someone else’s message with only `@Appy`,  
    I’ll try to create a new conversation just for you and that person.

---

🔢 **Calculator**

`@Appy calc <expression>`

Let me do the boring math for you – I support `+  -  *  /` and parentheses.

Examples:
• `@Appy calc 2+2*3`  
• `@Appy calc (2+3*4)/5`  
• `@Appy calc -5.5 + 3`

---

🦜 **Echo**

`@Appy echo <text>`

I simply repeat what you say (great for quick tests or fun).

Example:
• `@Appy echo Appy is the best bot 🎉`

---

😂 **Jokes**

`@Appy joke`

I fetch a fresh programming / dad joke from the internet (with a source link).  
If I’m offline, I’ll use my built-in joke stash instead.

---

🌤️ **Weather**

`@Appy weather <city>`

I show:
• **Now** → temperature, feels-like, wind, rain  
• **Later today** → Afternoon / Evening / Night  
• **10-day forecast** → weekday, min/max temp, rain chances

Examples:
• `@Appy weather Berlin`  
• `@Appy weather Munich`

Tip: Use larger city names if a small village doesn’t work.

---

⏱️ **Timers & reminders**

`@Appy timer <duration> [label]`

I wait, then ping this conversation with a knock + reminder message.

✅ Supported units:
• `s` / `sec` / `seconds`  
• `min` / `mins` / `minutes`  
• `h` / `hr` / `hours`  
• `d` / `day` / `days`  
• `w` / `week` / `weeks`  
• `mo` / `month` / `months` (approx. 30 days)

Examples:
• `@Appy timer 30s`  
• `@Appy timer 1 min tea break`  
• `@Appy timer 2 days project report`  

I’ll answer like:
> ⏳ Timer started for 2 days — project report.  

and later:
> ⏰ Timer done: 2 days — project report!

---

💡 *P.S.: If I don’t understand a command, I’ll say so and show this help again.*
""".trimIndent()

    // ---------- Timer parsing ----------
    private data class TimerSpec(val millis: Long, val human: String, val label: String)

    /**
     * Parses durations like:
     *  "30s", "1 min", "20 hrs", "2 days", "1 week", "5 months"
     * Optional label after the duration: "30s tea break"
     */
    private fun parseTimerArgs(args: String): TimerSpec? {
        if (args.isBlank()) return null

        val tokens = args.trim().split(Regex("\\s+"), limit = 2)
        val durToken = tokens[0]
        val label = if (tokens.size > 1) tokens[1].trim() else ""

        val m = Regex(
            """^(\d+)\s*(s|sec|secs|second|seconds|m|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days|w|wk|wks|week|weeks|mo|mon|mons|month|months)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(durToken) ?: return null

        val value = m.groupValues[1].toLong()
        val unit = m.groupValues[2].lowercase()

        val millis: Long = when (unit) {
            "s", "sec", "secs", "second", "seconds" -> value * 1_000L
            "m", "min", "mins", "minute", "minutes" -> value * 60_000L
            "h", "hr", "hrs", "hour", "hours" -> value * 3_600_000L
            "d", "day", "days" -> value * 86_400_000L
            "w", "wk", "wks", "week", "weeks" -> value * 7 * 86_400_000L
            "mo", "mon", "mons", "month", "months" -> value * 30 * 86_400_000L // simple approx
            else -> return null
        }

        val human = when (unit) {
            "s", "sec", "secs", "second", "seconds" -> "$value second${if (value == 1L) "" else "s"}"
            "m", "min", "mins", "minute", "minutes" -> "$value minute${if (value == 1L) "" else "s"}"
            "h", "hr", "hrs", "hour", "hours" -> "$value hour${if (value == 1L) "" else "s"}"
            "d", "day", "days" -> "$value day${if (value == 1L) "" else "s"}"
            "w", "wk", "wks", "week", "weeks" -> "$value week${if (value == 1L) "" else "s"}"
            "mo", "mon", "mons", "month", "months" -> "$value month${if (value == 1L) "" else "s"}"
            else -> "$value units"
        }

        return TimerSpec(millis = millis, human = human, label = label)
    }

    // ---------- Expression evaluator (no JS engine) ----------
    private fun evalExpression(exprInput: String): Double {
        var expr = exprInput
            .replace('−', '-') // unicode minus
            .replace('×', '*')
            .replace('÷', '/')
            .replace('–', '-') // en dash
            .replace('—', '-') // em dash

        data class Tok(val type: String, val value: String)
        val tokens = mutableListOf<Tok>()
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            when {
                c.isWhitespace() -> i++

                c.isDigit() || c == '.' -> {
                    val start = i
                    i++
                    while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) i++
                    tokens += Tok("num", expr.substring(start, i))
                }

                c == '+' || c == '-' || c == '*' || c == '/' || c == '(' || c == ')' -> {
                    if (c == '-' && (tokens.isEmpty() || tokens.last().type in setOf("op", "lpar"))) {
                        val start = i
                        i++
                        if (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) {
                            i++
                            while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) i++
                            tokens += Tok("num", expr.substring(start, i))
                        } else {
                            tokens += Tok("op", "-")
                        }
                    } else {
                        when (c) {
                            '(' -> tokens += Tok("lpar", "(")
                            ')' -> tokens += Tok("rpar", ")")
                            else -> tokens += Tok("op", c.toString())
                        }
                        i++
                    }
                }

                else -> throw IllegalArgumentException("Invalid character: '$c'")
            }
        }

        val out = mutableListOf<Tok>()
        val ops = JArrayDeque<Tok>()
        fun prec(op: String) = if (op == "+" || op == "-") 1 else 2

        for (t in tokens) {
            when (t.type) {
                "num" -> out += t
                "op" -> {
                    while (ops.isNotEmpty() && ops.peek().type == "op" &&
                        prec(ops.peek().value) >= prec(t.value)) {
                        out += ops.pop()
                    }
                    ops.push(t)
                }
                "lpar" -> ops.push(t)
                "rpar" -> {
                    while (ops.isNotEmpty() && ops.peek().type != "lpar") {
                        out += ops.pop()
                    }
                    if (ops.isEmpty() || ops.peek().type != "lpar") {
                        throw IllegalArgumentException("Mismatched parentheses")
                    }
                    ops.pop()
                }
            }
        }

        while (ops.isNotEmpty()) {
            val opTok = ops.pop()
            if (opTok.type == "lpar" || opTok.type == "rpar") {
                throw IllegalArgumentException("Mismatched parentheses")
            }
            out += opTok
        }

        val st = JArrayDeque<Double>()
        for (t in out) {
            when (t.type) {
                "num" -> st.push(t.value.toDouble())
                "op" -> {
                    val b = st.popOrThrow()
                    val a = st.popOrThrow()
                    val v = when (t.value) {
                        "+" -> a + b
                        "-" -> a - b
                        "*" -> a * b
                        "/" -> a / b
                        else -> error("Unknown op")
                    }
                    st.push(v)
                }
            }
        }
        if (st.size != 1) throw IllegalArgumentException("Invalid expression")
        return st.pop()
    }

    private fun <T> JArrayDeque<T>.popOrThrow(): T {
        if (isEmpty()) throw IllegalArgumentException("Invalid expression")
        return pop()
    }

    private fun formatNumber(num: Double): String {
        return if (num % 1.0 == 0.0) num.toInt().toString()
        else (round(num * 100) / 100.0).toString()
    }

    // ---------- NEW: Reflection helpers for reply→new conversation ----------

    // Try to get the quoted/original message this text was replying to
    private fun extractQuotedMessage(msg: WireMessage.Text): Any? {
        val candidates = listOf("repliedTo", "inReplyTo", "quotedMessage", "originalMessage", "replyTo")
        for (f in candidates) {
            try {
                val fld = msg::class.java.getDeclaredField(f).apply { isAccessible = true }
                val v = fld.get(msg)
                if (v != null) return v
            } catch (_: Throwable) { /* ignore */ }
        }
        return null
    }

    // Try to extract a sender QualifiedId from a message-like object
    private fun extractSenderQualifiedId(messageObj: Any): QualifiedId? {
        val directFields = listOf("senderId", "authorId", "from", "userId", "sender")
        for (f in directFields) {
            try {
                val fld = messageObj::class.java.getDeclaredField(f).apply { isAccessible = true }
                val v = fld.get(messageObj)
                when (v) {
                    is QualifiedId -> return v
                    else -> {
                        val idFld = v?.javaClass?.declaredFields?.firstOrNull { it.name == "id" }?.apply { isAccessible = true }
                        val idVal = idFld?.get(v)
                        if (idVal is QualifiedId) return idVal
                    }
                }
            } catch (_: Throwable) { /* continue */ }
        }
        val nested = listOf("user", "sender", "author")
        for (f in nested) {
            try {
                val fld = messageObj::class.java.getDeclaredField(f).apply { isAccessible = true }
                val v = fld.get(messageObj) ?: continue
                val idFld = v.javaClass.declaredFields.firstOrNull { it.name in listOf("qualifiedId", "id") }?.apply { isAccessible = true }
                val idVal = idFld?.get(v)
                if (idVal is QualifiedId) return idVal
            } catch (_: Throwable) { /* ignore */ }
        }
        return null
    }

    // Best-effort check if id is our own bot id
    private fun isSelf(id: QualifiedId): Boolean {
        return try {
            val fields = listOf("selfId", "botId", "userId", "ownId", "me")
            for (f in fields) {
                try {
                    val fld = manager::class.java.getDeclaredField(f).apply { isAccessible = true }
                    val v = fld.get(manager)
                    if (v is QualifiedId && v == id) return true
                } catch (_: Throwable) { /* try next */ }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    // Create a group conversation with participants via reflection (SDKs differ)
    private fun createGroupConversationCompat(participants: List<QualifiedId>, name: String?): QualifiedId? {
        findAndInvokeCreateConversation(manager, participants, name)?.let { return it }

        val nestedHolders = listOf("client", "events", "api", "service")
        for (holder in nestedHolders) {
            try {
                val fld = manager::class.java.getDeclaredField(holder).apply { isAccessible = true }
                val obj = fld.get(manager) ?: continue
                findAndInvokeCreateConversation(obj, participants, name)?.let { return it }
            } catch (_: Throwable) { /* continue */ }
        }
        return null
    }

    private fun findAndInvokeCreateConversation(target: Any, participants: List<QualifiedId>, name: String?): QualifiedId? {
        val methods = target::class.java.methods
        for (m in methods) {
            val n = m.name.lowercase()
            if (("create" in n || "new" in n) && "conversation" in n) {
                val p = m.parameterTypes
                // (List<QualifiedId>, String)
                if (p.size == 2 && java.util.Collection::class.java.isAssignableFrom(p[0]) && p[1] == String::class.java) {
                    try {
                        val res = m.invoke(target, participants, name ?: "") ?: continue
                        if (res is QualifiedId) return res
                        val idFld = res.javaClass.declaredFields.firstOrNull { it.name in listOf("id", "conversationId") }?.apply { isAccessible = true }
                        val idVal = idFld?.get(res)
                        if (idVal is QualifiedId) return idVal
                    } catch (_: Throwable) { /* next */ }
                }
                // (List<QualifiedId>)
                if (p.size == 1 && java.util.Collection::class.java.isAssignableFrom(p[0])) {
                    try {
                        val res = m.invoke(target, participants) ?: continue
                        if (res is QualifiedId) return res
                        val idFld = res.javaClass.declaredFields.firstOrNull { it.name in listOf("id", "conversationId") }?.apply { isAccessible = true }
                        val idVal = idFld?.get(res)
                        if (idVal is QualifiedId) return idVal
                    } catch (_: Throwable) { /* next */ }
                }
                // (QualifiedId...) varargs/array
                if (p.size == 1 && p[0].isArray && p[0].componentType == QualifiedId::class.java) {
                    try {
                        val arr = java.lang.reflect.Array.newInstance(QualifiedId::class.java, participants.size) as Array<Any?>
                        participants.forEachIndexed { i, q -> arr[i] = q }
                        val res = m.invoke(target, arr) ?: continue
                        if (res is QualifiedId) return res
                        val idFld = res.javaClass.declaredFields.firstOrNull { it.name in listOf("id", "conversationId") }?.apply { isAccessible = true }
                        val idVal = idFld?.get(res)
                        if (idVal is QualifiedId) return idVal
                    } catch (_: Throwable) { /* next */ }
                }
            }
        }
        return null
    }
}

