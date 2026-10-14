# NewsReader source and documentation snapshot

Includes Android phone, Wear OS companion and web source, SQL schema, Gradle wrapper, full Markdown and offline HTML documentation, ten screenshots, and an opt-in watch screenshot fixture.

APK assets include a newly built phone debug APK with provider credentials removed, a current watch debug APK, and three historical watch APKs. Both current modules built successfully and debug signatures were verified. The original phone APKs contained credentials and are excluded; signing keystores and API credentials are also excluded.

The phone APK needs your own provider configuration and rebuild for online news. The watch requires a paired phone. Populated watch screenshots use labeled sample state; paired-phone playback is not verified.

See `docs/PROJECT_DOCUMENTATION.md`, `docs/GITHUB_PUBLICATION.md`, and `release-apks/README.md` for setup, findings and distribution details.
