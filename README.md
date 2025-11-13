# Appy – A Lightweight Assistant Bot for Wire

Appy is a friendly helper bot built using the **Wire Apps JVM SDK**.  
Mention `@Appy` inside any Wire conversation to access quick utilities such as weather forecasts, jokes, calculations, timers, echoes, and more.

Appy demonstrates how to build interactive assistant-style features inside the Wire secure messaging ecosystem.

---

## ✨ Features

### 🧮 Calculator
Evaluate mathematical expressions using:
- `+`, `-`, `*`, `/`
- Parentheses  
- Negative numbers

Example:
@Appy calc (2+3*4)/5
---

### 😂 Programming & Dad Jokes
Fetches fresh jokes from online APIs (with offline fallback jokes included).

Example:
@Appy joke
---

### 🌤️ Weather Forecasts  
Appy provides:
- Current weather conditions  
- Afternoon / Evening / Night forecast  
- 10-day forecast with weekday names  

Powered by **Open-Meteo** (no API key required).

Example:
@Appy weather Berlin
---

### ⏱️ Timers & Reminders
Set countdown timers that notify you when they’re finished.

Supported units:
- seconds (s, sec)
- minutes (m, min)
- hours (h, hr)
- days (d)
- weeks (w)
- months (mo, approx. 30 days)

Examples:
@Appy timer 30s
@Appy timer 1 min tea break
@Appy timer 2 days project submission
---

### 🦜 Echo
Appy repeats back exactly what you say.

Example:
@Appy echo Hello Appy!
---

## 📋 Commands Overview

| Command | Description |
|--------|-------------|
| `@Appy` | Show help message |
| `@Appy calc <expression>` | Evaluate a math expression |
| `@Appy joke` | Fetch an online joke |
| `@Appy weather <city>` | Show detailed weather forecast |
| `@Appy timer <duration> [label]` | Set a reminder |
| `@Appy echo <text>` | Repeat your text |

---

## 🛠️ Requirements

- **Kotlin/JVM 17+**
- **Wire Apps JVM SDK** (`com.wire:wire-apps-jvm-sdk`)
- Internet connection (for weather & jokes)
- Optional: Java 11+ `HttpClient` for external requests

---

## 🚀 Running Appy

Configure your Wire application credentials:

```kotlin
val wireAppSdk = WireAppSdk(
    applicationId = UUID.randomUUID(),
    apiToken = "<your-api-token>",
    apiHost = "<wire-environment-host>",
    cryptographyStoragePassword = "<32-byte-password>",
    wireEventsHandler = AppyEventsHandler()
)
wireAppSdk.startListening()
