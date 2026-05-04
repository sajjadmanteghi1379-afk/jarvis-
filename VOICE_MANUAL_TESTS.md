# Voice Wake Manual Test Checklist

- Say "hey jarvis" -> Jarvis says "Yes, sir?" and waits; no AI fallback.
- Say "hey jarvis open instagram" -> opens Instagram once.
- Say "open telegram" without wake while conversation mode is inactive -> ignored.
- Say "hey jarvis", then "open telegram" -> opens Telegram once.
- Say "hey jarvis", then "what is the capital of Japan" -> one AI response only.
- Say "open what's up" after wake -> opens WhatsApp.
- Say "goodbye jarvis" -> exits conversation mode and returns to wake-only listening.
- TTS should not trigger its own commands.
- Notification access logs should not spam every second.
