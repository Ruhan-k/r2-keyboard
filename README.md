# R2 Keyboard 1.4

A Kotlin Android keyboard with a local FastAPI backend calling Groq's
`openai/gpt-oss-20b`. Package: `com.example.furryreply`. Minimum SDK: 28 (Android 9).

## How it works

1. Copy an incoming message from another app.
2. Open R2 Keyboard and tap **Use Clipboard**. This is the only action that reads
   the clipboard. Text appears under **Copied Message**.
3. Tap **Generate Replies**. Retrofit sends the message to FastAPI, which calls
   Groq. Generate is disabled while **Generating…** is displayed.
4. Three generated buttons appear. Tapping one inserts its exact text via
   `currentInputConnection.commitText()`. Inserting does not send the message.
5. **Clear** clears the displayed message and replies and cancels any request.
   The system clipboard stays unchanged. **Switch Keyboard** opens Android's picker.

The prompt requests short, casual, lowercase-heavy natural texting with light
gen z slang, minimal punctuation, a furry/community-friendly tone, and no forced
commission pitch. It never assumes unknown personal facts. With no memory in this
version, questions such as “you still busy?” receive meaningfully different
possibilities (free, somewhat busy, and briefly available), instead of a fabricated
circumstance. Humor follows the incoming tone: low for serious messages, medium for
ordinary chat, and high for playful messages. It uses quick wit, teasing, absurd
comparisons, deadpan phrasing, and internet humor only when natural.

Version 1.4 stores data locally on the phone in a Room database. Each client has a
separate profile, messages, and saved memory records. Selecting a client in the
keyboard scopes clipboard saves, outgoing-reply saves, and reply-generation context
to that client only. Before generating, the keyboard loads that client’s most recent
20 messages and all saved memory rows into the request. No automatic memory
extraction, summaries, embeddings, vector database, cloud storage, or cross-client
context is used. The backend does not save or log message/reply bodies.
Groq receives the copied message only when Generate is tapped, subject to its
own data policies.

Empty/non-text clipboard content shows **No copied message found** and leaves
Generate disabled. Messages over 10,000 characters show a length error.
Clearing/replacing the message or hiding the keyboard cancels an active request;
late responses cannot populate replies for a replaced message. Cancellation may
not stop a request that has already reached Groq.

## Backend configuration

The backend environment is `backend/.venv` (Python 3.13 on this computer).
Dependencies are pinned in `backend/requirements.txt`.

Set the key only in the backend environment or ignored `backend/.env`:

```dotenv
GROQ_API_KEY=your_key_here
GROQ_MODEL=openai/gpt-oss-20b
```

Get a key from [Groq Console](https://console.groq.com/keys). Edit
`C:\Users\Lenovo\OneDrive\Desktop\keyboard\backend\.env` locally; never put the key
in Kotlin, Android resources, Gradle, source control, or chat messages. The Android
app contains no Groq key. Process environment variables take precedence over
`.env`. Configuration is read on each request, so saving `.env` needs no restart.

To recreate the virtual environment if needed:

```powershell
cd C:\Users\Lenovo\OneDrive\Desktop\keyboard
python -m venv backend\.venv
.\backend\.venv\Scripts\python.exe -m pip install -r backend\requirements.txt
```

To start the server after restarting the computer, leave this terminal open:

```powershell
cd C:\Users\Lenovo\OneDrive\Desktop\keyboard\backend
.\.venv\Scripts\python.exe -m uvicorn main:app --host 127.0.0.1 --port 8000 --no-access-log
```

Alternatively run `backend/start.ps1`. If this backend is already running on
port 8000, do not start a second instance. The instance started during setup runs
in the background. Its operational logs are `backend/server-output.log` and
`backend/server-error.log`.

Open [health](http://127.0.0.1:8000/health) or [interactive API docs](http://127.0.0.1:8000/docs).
Health's `configured: true` means a key exists; it does not prove the key or quota works.

## USB connection

ADB reverse forwarding connects the phone's `127.0.0.1:8000` to the PC's port
8000. The server listens only on loopback; no Wi-Fi address/public hosting is used.

After reconnecting USB or restarting ADB, run:

```powershell
$adb = 'C:\Users\Lenovo\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb devices
& $adb -s 115413742L007974 reverse tcp:8000 tcp:8000
```

The phone must appear as `device`. For `unauthorized`, unlock it and accept the
USB debugging prompt. Keep the computer awake, backend running, and USB connected.
The computer needs internet access to Groq. The debug app permits HTTP only to
`127.0.0.1`; the backend's Groq connection uses HTTPS. This is a local development
setup, not a publicly hosted backend.

## API contract

`POST /generate-replies`

```json
{"message": "just finished drawing my new fox sona wanna see it"}
```

Successful response:

```json
{"replies": ["first generated reply", "second generated reply", "third generated reply"]}
```

Groq uses strict JSON structured output, and FastAPI validates exactly three
nonempty, distinct, single-line replies of at most 160 characters each. Invalid
results produce an error, never hardcoded fallback replies.

| Status | Meaning |
| --- | --- |
| 200 | Three valid replies |
| 422 | Blank, oversized, or invalid input |
| 429 | Groq rate limit or quota reached |
| 502 | Provider connection/generation failure or invalid replies |
| 503 | Missing/invalid key or unavailable model access |
| 504 | Provider timeout |

The keyboard displays errors and lets you retry. There are no automatic retries.
Your Groq account's free-tier limits apply; unlimited free usage is not assumed.

## Build and install

```powershell
cd C:\Users\Lenovo\OneDrive\Desktop\keyboard
$env:JAVA_HOME = 'C:\Users\Lenovo\AppData\Local\Java\jdk-21.0.12.1+1'
.\gradlew.bat assembleDebug lintDebug
$adb = 'C:\Users\Lenovo\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s 115413742L007974 install -r 'C:\Users\Lenovo\OneDrive\Desktop\keyboard\app\build\outputs\apk\debug\app-debug.apk'
& $adb -s 115413742L007974 reverse tcp:8000 tcp:8000
```

APK: `C:\Users\Lenovo\OneDrive\Desktop\keyboard\app\build\outputs\apk\debug\app-debug.apk`

Version code 5, version name 1.4. The existing InputMethodService, service
registration and input-method metadata remain in use. The launcher opens a simple
local client-management screen where you can add, select, delete, and inspect a
client’s saved messages.

## Keyboard redesign — version 1.9

The keyboard now has a dark QWERTY layout with shift (double-tap for caps lock),
numbers/symbols, hold-to-delete, space, and the active app's Enter/Send action.
The client selector and Context / Clipboard / Generate controls stay above the keys.
Generated replies appear in a horizontal strip: swipe for all three, tap to insert,
or hold a long suggestion to read it in full.

Use **•••** for extra context, clearing the preview, saving edited wording for
style learning, client/style management, and switching keyboards. Search and
context editing happen inside the keyboard using its own keys. An app without a
saved client selection asks you to choose a client before generation.

Context capture saves locally. Reply generation and memory extraction start only
after you tap Generate. The upgrade retains the existing Room database.

Verification for this release: debug build and lint passed; installed on the
connected Infinix; checked rendered layout, navigation-bar spacing, typing,
shift, backspace, and client-search isolation in an unsaved form.

## Test on your phone

1. Select R2 Keyboard in a note or chat text field. With no copied message loaded,
   Generate should be disabled and no suggestion buttons should show.
2. Copy `just finished drawing my new fox sona wanna see it` from another app.
3. Return to R2 Keyboard and tap **Use Clipboard**. Confirm the text appears.
4. Tap **Generate Replies**. Confirm **Generating…** appears and Generate disables.
5. Wait for three replies, then tap one. Verify its exact text is inserted.
6. Tap **Clear**: the message and replies should disappear and Generate disable.
7. Clear or replace the message during generation: old replies must not reappear.
8. For a connection-error test, run `adb -s 115413742L007974 reverse --remove tcp:8000`
   and try Generate. Restore the forwarding command above before continuing.

## Main files and verification

- `app/src/main/java/com/example/furryreply/FurryReplyInputMethodService.kt`: UI,
  clipboard, loading/errors, cancellation, and insertion.
- `app/src/main/java/com/example/furryreply/ReplyApi.kt`: Retrofit models/client.
- `app/src/main/res/layout/keyboard_view.xml`: simple UI.
- `app/src/debug/`: debug-only localhost HTTP configuration.
- `backend/main.py`: FastAPI endpoint, Groq request, validation and errors.
- `backend/test_backend.py`: contract/error tests with a simulated Groq server.

Run backend tests without using Groq credits:

```powershell
cd C:\Users\Lenovo\OneDrive\Desktop\keyboard\backend
.\.venv\Scripts\python.exe -m unittest -v test_backend.py
```

Verified: Android build and lint pass with zero errors; all eight backend tests
pass; a live Groq request returned three generated replies.

## References

- [Groq structured outputs](https://console.groq.com/docs/structured-outputs)
- [Groq GPT-OSS 20B](https://console.groq.com/docs/model/openai/gpt-oss-20b)
- [FastAPI request bodies](https://fastapi.tiangolo.com/tutorial/body/)
- [Retrofit](https://github.com/square/retrofit)
- [Android network security](https://developer.android.com/privacy-and-security/security-config)

## Personality, RUDE intent and Starter

- Intents: AUTO, CHILL, FUNNY, CUTE, DRY, FLIRTY, ARTIST, RUDE. RUDE is casual roasting and
  skepticism that tones itself down for serious messages (see `INTENT_GUIDANCE` in `backend/main.py`).
- Texting style (lowercase, minimal punctuation, slang as vocabulary, punctuation/energy mirroring,
  emoji and opener variety, no "I am an AI" answers) lives in `TEXTING_STYLE_RULES` in `backend/main.py`.
  Each request also gets a computed TEXTING STYLE CONTEXT block listing emojis, slang and openers the
  owner recently overused so the model avoids repeating them.
- **💬 Starter** (next to Adjust) calls `POST /generate-starters` and shows three furry conversation
  starters in the suggestion strip. Topic seeds live in `backend/starter_topics.py` (160+ seeds in
  27 categories). The last 30 starters per client are stored locally in the keyboard preferences, the
  last 20 are sent as an exclusion list, and recently used categories and facts already in client
  memory are skipped. The response body is exactly `{"starters": [...]}`; the topics used come back in
  the `X-Starter-Topics` header.
- Backend tests: `cd backend && python -m unittest discover -p "test_*.py"`.
