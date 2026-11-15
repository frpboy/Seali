# Seali Privacy Policy

**Last updated: 15 November 2026**

This policy describes the Android application **Seali** maintained in the [frpboy/Seali repository](https://github.com/frpboy/Seali).

## Summary

- Seali does not require an account and does not provide a developer-operated cloud account or sync service.
- The current Android implementation does not include advertising or usage-analytics SDKs.
- Seali does not automatically upload your download history, saved cookies, or crash reports to the developer.
- Media downloads and optional update checks require network connections to the relevant third-party services. Those services have their own privacy practices.

## Information handled on your device

Seali processes information needed to provide its features, including:

- Media URLs that you paste, share with Seali, or enter.
- Download titles, formats, status, timestamps, and local file paths used for download history.
- Your preferences, command templates, and selected download locations.
- Cookies that you choose to use through Seali's built-in WebView or cookie feature.
- Diagnostic information such as app version details and stack traces when an error occurs.

Download history and application preferences are stored locally in the app's data area. Downloaded media is saved to the directory you select. Seali's crash screen and download-failure notifications can display diagnostic information, which may include web addresses or account-related details, and offer a copy action; this information stays on your device unless you choose to share it. Seali does not automatically transmit these reports to the developer.

## Network connections and third-party services

### Media downloads

When you start a download, Seali uses its downloader components (including yt-dlp and FFmpeg where applicable) to contact the media provider or other services required to retrieve the content. Those services may receive your IP address, requested URL, user-agent and other request metadata. Their own privacy policies govern how they handle that information.

If you save cookies (through the built-in sign-in page or by pasting them), Seali stores them in its private app database. When you start a download, Seali writes your saved cookies to a plain-text cookie file in the app's private storage so the download engine (yt-dlp) can use them. That file can include cookies for more than one site you have saved, not only the site being downloaded. Cookies can identify or authenticate you to the sites they belong to. Only use cookies for accounts you control, and do not share cookie data with other people.

### Update checks

Depending on the distribution channel and your settings, non-Google-Play builds may contact GitHub's API for Seali release information and the yt-dlp release service for extractor updates. GitHub and the relevant release service receive ordinary connection metadata such as your IP address and request headers. The Google Play build is configured not to download yt-dlp at runtime; app updates for that build are delivered through its distribution channel.

### External links

The About screen may open external services such as GitHub or the Seali support channel. When you follow an external link, that service's privacy policy applies to your activity there.

## Data sharing

The developer does not receive your download history, saved cookies, or crash reports through an automatic Seali upload service. Information is sent to a media provider when you request a download, and to update services when an update check or download is performed. You may also choose to copy or share diagnostic text yourself; the destination you choose then handles that information.

Seali does not sell personal information.

## Retention and deletion

Application settings, download history, and saved cookies remain in the app's local storage until you delete them. You can remove individual cookie profiles or clear all cookies in Settings, under Network, Cookies. Clearing the app's storage or uninstalling Seali removes the app's local data. The temporary cookie file can also be removed when the app's cache is cleared. Downloaded files are stored in the location you selected and are not necessarily deleted when the app is uninstalled; remove those files separately if you no longer want them.

Seali excludes its app database, which holds download history and saved cookie profiles, from Android cloud backups and device-to-device transfer. Other app settings may be included in backups; this is controlled by Android and your device's backup settings.

## Children's privacy

Seali is a general-purpose media utility and is not specifically directed at children. It does not provide a child account or profile system.

## Security

Seali uses Android's application storage and platform security controls. No method of local storage or network communication can be guaranteed to be completely secure. Keep your device updated and avoid sharing authentication cookies or diagnostic reports that contain information you do not intend to disclose.

## Changes to this policy

This policy may be updated when Seali's behavior or distribution channels change. The revision date at the top indicates the latest published version.

## Contact

For privacy questions or requests, open an issue in the [Seali GitHub repository](https://github.com/frpboy/Seali/issues). Do not post passwords, cookies, access tokens, or other private information in a public issue.
