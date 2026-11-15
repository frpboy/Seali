# Seali

<img src="fastlane/metadata/android/en-US/images/icon.png" width="112" height="112" alt="Seali app logo" />

<img src="https://komarev.com/ghpvc/?username=frpboy-seali&amp;label=README%20VIEWS&amp;color=0e75b6&amp;style=for-the-badge" alt="Seali README views" />

<sub>Badge requests counted by Komarev, not unique visitors or historical GitHub traffic.</sub>

**Seali** is an independently maintained Android video and audio downloader.

This repository is maintained by [Rahul Muraleedharan (@frpboy)](https://github.com/frpboy).

> **Project status:** [Seali 2.0.0-alpha.5](https://github.com/frpboy/Seali/releases/tag/v2.0.0-alpha.5) is available as an alpha prerelease. The app uses the Seali identity and `com.frpboy.seali` package. The final cleanup build still needs device testing; it is not a stable or Play-qualified release. Later changes on main are not automatically included in those published APKs.

[![Latest published prerelease: 2.0.0-alpha.5](https://img.shields.io/badge/Latest%20published%20prerelease-2.0.0--alpha.5-00ed9d?style=for-the-badge&logo=android&logoColor=white)](https://github.com/frpboy/Seali/releases/tag/v2.0.0-alpha.5)

<sub>The badge always links the latest published build (alpha.5). alpha.7 is still in development and is not published yet.</sub>

## Why Seali instead of upstream Seal?

Seali is a fork of [Seal](https://github.com/JunkFood02/Seal) with its own identity and active maintenance by [@frpboy](https://github.com/frpboy). What the fork changes:

- **Independent identity**: Seali branding and the `com.frpboy.seali` package, so it installs alongside upstream Seal without replacing it.
- **Current Android platform**: targets API 36 (Android 16) with a minimum of Android 8.0 (API 26). Native libraries are built with 16 KB page-size alignment for current devices; on-device runtime qualification of that support is still pending.
- **Owned, verified engine packaging**: the yt-dlp Android runtime is built from the [frpboy/youtubedl-android](https://github.com/frpboy/youtubedl-android) fork and consumed as pinned, hash-locked Maven Central artifacts instead of inherited opaque binaries.
- **Choice of interface**: switch between the Classic and v2 interfaces in Settings > Appearance.
- **Play policy work**: no battery-exemption request and a published [privacy policy](https://seali.frpboy.in/privacy).

Upstream Seal by [JunkFood02](https://github.com/JunkFood02) remains the base of this project; its GPL license, credits and history are preserved. Report Seali-specific issues here, not upstream.

## Current maintenance and next release

The current source version is **2.0.0-alpha.7**, and no alpha.6 or alpha.7 build has been published yet. The latest public APKs remain alpha.5. Documentation/contact corrections and merged Classic/About maintenance on main do not update an installed alpha.5 APK. See [current status](SEALI_STATUS.md) and the [Seali changelog](CHANGELOG.md#seali-maintenance-unreleased) for completed source changes and release limits.

The About/contact/translation changes in PR30 remain unmerged pending visual checks. Recent merged maintenance (documentation, CI and build-contract fixes) is source maintenance, not a new app release. Native source-build preparation has not replaced the inherited dependencies. A new release needs exact-source signed APKs and the applicable runtime, native/security, source and notice checks; green documentation CI alone is not release qualification.

## Download and use

Download an APK from the [alpha release page](https://github.com/frpboy/Seali/releases/tag/v2.0.0-alpha.5). It provides universal, ARM64, ARMv7, x86 and x86_64 builds. Choose universal if unsure. Android 8.0 (API 26) or newer is required by the current build configuration. Seali installs separately from upstream Seal; upstream app data does not migrate automatically.

Paste a supported video link and tap Download, then choose the desired options. The interface can be switched between Classic and v2 in Settings > Appearance > Interface. Finish or cancel active downloads before switching.

Read [BUILDING.md](BUILDING.md) for the JDK 21/SDK 36 setup, real test commands, flavors and signing requirements. [SEALI_STATUS.md](SEALI_STATUS.md) distinguishes published checks from remaining device, storage and review gates.

## Screenshots

Actual user-device captures of **Seali 2.0.0-alpha.6**, supplied on November 9, 2026. The navigation and configuration views are cropped to Seali-only UI for privacy. No UI has been repainted or recreated. These captures show an installed alpha build, not qualification of the latest source or a new public release.

<p>
  <img src="docs/showcase/alpha6-device/queue.png" width="240" alt="Seali alpha.6 empty download queue, All tab" />
  <img src="docs/showcase/alpha6-device/navigation.png" width="240" alt="Seali alpha.6 navigation drawer, cropped" />
</p>

<p>
  <img src="docs/showcase/alpha6-device/video-options.png" width="300" alt="Seali alpha.6 video configuration controls, cropped" />
  <img src="docs/showcase/alpha6-device/audio-options.png" width="300" alt="Seali alpha.6 audio configuration and additional settings, cropped" />
</p>

Queue and navigation above; video and audio configuration below. The empty queue does not demonstrate a successful download. Personal media, links, notifications and error reports are excluded. [Capture scope and provenance](docs/showcase/README.md#user-device-gallery).

## About

Seali is based on the original Seal application, a Kotlin/Jetpack Compose Android interface for [yt-dlp](https://github.com/yt-dlp/yt-dlp).

The inherited feature set includes:

- Download video and audio from websites supported by yt-dlp.
- Extract audio and embed supported metadata and thumbnails.
- Download playlists.
- Use the bundled aria2c external downloader.
- Embed subtitles in downloaded videos.
- Create and run custom yt-dlp command templates.
- Manage downloads and command templates.
- Material Design 3 interface with dynamic color support.

These are inherited capabilities. Current compatibility and functionality will be revalidated as part of the Seali maintenance work.

## Thanks and credits

Upstream authorship, historical references and required license notices are centralized in [CREDITS.md](CREDITS.md).

## Attribution and project identity

Seali is independently maintained and is not an official upstream release. Please report Seali-specific issues in this repository. See [CREDITS.md](CREDITS.md) for original authorship, history and required notices.

## Development

The project uses Kotlin, Jetpack Compose and Gradle. Owned source packages use `com.frpboy.seali`. The installed application ID remains `com.frpboy.seali`; debug/preview suffixes are unchanged. Minimal legacy type IDs and Android component bridges remain for upgrade compatibility, not branding. See [namespace migration notes](docs/migration/owned-namespace-migration.md) for the remaining qualification gates. See [BUILDING.md](BUILDING.md) before changing build or signing configuration.

Before submitting changes:

1. Read [CONTRIBUTING.md](CONTRIBUTING.md) and follow the repository's contribution conventions.
2. Keep changes focused and include tests where practical.
3. Clearly identify inherited behavior versus new Seali-specific changes.
4. Preserve all third-party copyright and license notices.
5. Do not claim that a feature is supported until it has been tested on the current codebase.

## License

Seali's inherited source code is distributed under the GNU General Public License, version 3. See [LICENSE](LICENSE).

When distributing modified builds, comply with GPL-3.0, including the requirements to provide the corresponding source code and preserve required notices. Modified versions should be clearly identified as modified so problems are not attributed to the original authors.

## Support

For Seali and the maintainer's other projects, use the [FrpBoy Projects Telegram group](https://t.me/FRPboy_Projects). Search existing repository issues before reporting a reproducible bug. Do not send private links, cookies, credentials or logs containing secrets to a public group.

Public maintainer email: [frpboy12@gmail.com](mailto:frpboy12@gmail.com), confirmed by the maintainer. Do not send passwords, cookies or other secrets.

Optional financial support is separate from help requests.

[![Buy Me a Coffee](https://img.shields.io/badge/Buy_Me_a_Coffee-FFDD00?style=for-the-badge&logo=buymeacoffee&logoColor=000000)](https://buymeacoffee.com/frpboy)
[![Support on Patreon](https://img.shields.io/badge/Support_on_Patreon-FF424D?style=for-the-badge&logo=patreon&logoColor=FFFFFF)](https://www.patreon.com/c/FrpBoy)

On main, PR16 keeps these options under the single Sponsor entry. The published alpha predates that cleanup and still has the duplicate About donation rows.

---

**Seali** - continued independently by Rahul Muraleedharan, with upstream attribution preserved in CREDITS.md.
