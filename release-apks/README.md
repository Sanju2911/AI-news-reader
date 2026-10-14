# APK downloads

Prepared from the current source on 14 October 2026.

| APK | Use |
| --- | --- |
| [Phone debug, credentials removed](NewsReader-Phone-debug-no-credentials.apk) | Current phone build. Online providers require your own configuration and rebuild. |
| [Wear OS debug, current source](NewsReader-WearOS-debug-current.apk) | Current watch build; requires paired phone. |
| [Wear OS v1.0](NewsReader-WearOS-v1.0.apk) | Historical supplied watch artifact. |
| [Wear OS v1.1](NewsReader-WearOS-v1.1.apk) | Historical supplied watch artifact. |
| [Wear OS v1.2 filename](NewsReader-WearOS-v1.2.apk) | Historical supplied artifact; embedded app version is 1.1. |

Original phone APKs are not included because credential scanning found embedded provider keys. Existing historical watch APKs passed the known-credential scan. Current debug APKs were rebuilt from the sanitized publication copy and their signatures verified. No phone runtime or paired-device test was performed on these builds.

Debug and historical APKs may have different signing identities. Android will reject an in-place update if the installed app is signed with another identity.
