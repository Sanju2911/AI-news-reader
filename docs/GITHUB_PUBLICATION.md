# Publication notes

Prepared 14 October 2026.

- This repository is a publication copy; the original local project retains its existing configuration.
- API credentials, the original release keystore and its passwords, machine-specific settings, dependencies and build caches are excluded.
- Phone source uses blank GNews, Gemini and default Sarvam keys. Supabase uses a placeholder URL and blank key. Supply your own configuration before rebuilding.
- The web server reads GNEWS_API_KEY, GEMINI_API_KEY, SARVAM_API_KEY, SUPABASE_URL and SUPABASE_KEY from the environment. With a compatible Node version, use `node --env-file=.env server.js` after copying `.env.example` to `.env`; alternatively set environment variables in your shell.
- Release signing settings were removed. Configure your own signing identity for production releases. Debug APKs use a development signature and may not update an installation signed with another key.
- Historical phone APKs are excluded because they can embed the original API credentials. A rebuilt phone debug APK is included instead. Historical watch APKs are included only after credential scanning.
- Documentation reports the original local runtime observations; the publication copy's provider configuration intentionally differs.
- Populated watch screenshots use labeled synthetic local state. They do not demonstrate paired-phone playback.

Build the publication copy with `.\gradlew.bat :app:assembleDebug :wear:assembleDebug` and JDK 17/SDK 35.
