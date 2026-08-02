<div align="center">
  <img style="border-radius: 50%" src="assets/logo.png" width="100" alt="FeedFlow app icon">
  <h1>FeedFlow</h1>
  <p><strong>Minimal, fast RSS reading without the clutter.</strong></p>
  <p>
    FeedFlow is an RSS reader project with apps for Android, iOS, macOS, Windows, and Linux.
    It uses Kotlin Multiplatform for shared logic, Compose Multiplatform for Android and Desktop,
    and SwiftUI for iOS-specific UI.
  </p>
  <p>
    It focuses on a clean timeline, flexible reading modes, and control over sync and storage.
  </p>
  <p>
    <a href="https://www.feedflow.dev">Website</a>
    ·
    <a href="https://github.com/prof18/feed-flow/releases/latest">Latest release</a>
    ·
    <a href="https://github.com/prof18/feed-flow/issues">Issues</a>
    ·
    <a href="https://hosted.weblate.org/engage/feedflow/">Translate</a>
  </p>
  <p>
    <img alt="GitHub Release" src="https://img.shields.io/github/v/release/prof18/feed-flow?display_name=release">
    <img alt="License" src="https://img.shields.io/github/license/prof18/feed-flow">
    <img alt="Platforms" src="https://img.shields.io/badge/platform-Android%20%7C%20iOS%20%7C%20macOS%20%7C%20Windows%20%7C%20Linux-2ea44f">
  </p>
</div>

> [!NOTE]
> This repository is a fork of the original [FeedFlow](https://github.com/prof18/feed-flow) project by [Marco Gomiero (prof18)](https://github.com/prof18).  
> It enhances the official FeedFlow app with **AI-powered Article Summarisation** and **Relevance Ranking**, powered by Google Gemini with your own API key.

![FeedFlow banner](assets/banners.png)

## Why FeedFlow

- Read articles the way you want: Reader Mode, the in-app browser, or your preferred browser
- Keep your library local, sync it through cloud storage, or connect directly to reader services
- Organize a busy timeline with bookmarks, filters, and blocked words
- Move data in and out easily with OPML feed import/export and CSV article import/export

## Highlights

- Dedicated apps for Android, iOS, macOS, Windows, and Linux
- Flexible reading modes: Reader Mode, the in-app browser, or your preferred browser
- Flexible sync and storage options: local library, Dropbox, Google Drive, iCloud, FreshRSS, Miniflux, Feedbin, and BazQux Reader
- Offline reading by saving article content during sync
- Timeline, read status, bookmark, source, and category filters
- Blocked words to hide articles containing specific keywords or phrases
- AI article summaries and AI relevance ranking via Google Gemini (Most Relevant feed order), Android only
- Curated feed suggestions across different topics
- Theme modes for system, light, dark, and OLED
- Widgets for Android and iOS

## Download

| Platform | Get FeedFlow |
| --- | --- |
| Android | [Google Play](https://play.google.com/store/apps/details?id=com.prof18.feedflow) or [F-Droid](https://f-droid.org/packages/com.prof18.feedflow) |
| iPhone and iPad | [App Store](https://apps.apple.com/us/app/feedflow-rss-reader/id6447210518) |
| macOS | [Mac App Store](https://apps.apple.com/us/app/feedflow-rss-reader/id6447210518), [Homebrew](https://formulae.brew.sh/cask/feedflow), or [GitHub Releases](https://github.com/prof18/feed-flow/releases/latest) |
| Windows | [Microsoft Store](https://apps.microsoft.com/detail/9N5T1RFBB6V5?mode=direct) or [GitHub Releases](https://github.com/prof18/feed-flow/releases/latest) |
| Linux | [Flathub](https://flathub.org/en/apps/com.prof18.feedflow) or [GitHub Releases](https://github.com/prof18/feed-flow/releases/latest) |

## What You Can Do With FeedFlow

### Read Your Way

FeedFlow does not lock you into a single article view. Open links in Reader Mode, use the in-app browser,
or send them to your preferred browser. Reader Mode also includes extras like opening comments directly
and sharing articles without leaving your reading flow.

### Keep Control Over Sync and Storage

You can keep everything local, use storage backends like Dropbox, Google Drive, or iCloud,
or connect directly to reader services such as FreshRSS, Miniflux, Feedbin, and BazQux Reader.

### Stay on Top of a Busy Timeline

Use bookmarks, read and unread states, timeline filters, source and category views, and blocked words.
FeedFlow also supports auto-saving article content for offline reading and includes cache cleanup tools.

### Summarise Articles & Rank Feeds With AI

Reader Mode can generate a summary of the article you are reading. The summary appears as a
collapsible card in the article itself, just above the body text, and scrolls with the rest of
the page. FeedFlow also supports AI relevance ranking and "Most Relevant" feed ordering to bring
high-interest articles to the top of your timeline. Nothing is sent anywhere until requested, and
summary results are cached locally so reopening an article costs nothing.

This feature uses your own [Google Gemini](https://ai.google.dev) key. FeedFlow ships no key and
adds no service of its own. Gemini is the only supported provider: requests go straight to the
native `generateContent` API rather than through a compatibility shim, which is what lets relevance
scoring use a response schema so a malformed reply cannot happen.

**Setup:** get a key from [Google AI Studio](https://aistudio.google.com/apikey), then open
**Settings → AI** and paste it in. The key is stored encrypted on device and never leaves except
in direct requests to Gemini.

The AI settings screen exposes:

| Setting | What it does |
| --- | --- |
| Enable AI features | Turns summarisation and relevance ranking on or off. Off by default; while off, the summary card is absent from the reader page entirely and "Most Relevant" does not appear in any sort menu. |
| API key | Your Gemini key. One key covers both summaries and relevance ranking. |
| Model | Which Gemini model to call. Defaults to `gemini-3.5-flash-lite`; `gemini-3.5-flash` and `gemini-3.1-flash-lite` also work. Leave it empty to restore the default. |
| Summary prompt | The instruction the model is given. Edit it to change the summary's length, tone, or language. |
| Test Connection | Sends one short request so you can confirm the key and model work before relying on them. |

Changing the model invalidates cached summaries, so the next article is summarised with the new
settings rather than replaying an old result. It also discards existing relevance scores, because
two models do not share a scale and mixing them would sort articles against numbers that mean
different things.

AI features are currently Android-only. iOS and Desktop have no AI UI.

### Discover New Feeds Faster

Feed suggestions are built into the app, with curated sources across ten categories, so a fresh install
does not have to start from zero.

## Building From Source

### Prepare Local Config

Android:

```bash
cp config/dummy-google-services.json androidApp/src/debug/google-services.json
cp config/dummy-google-services.json androidApp/src/release/google-services.json
```

iOS:

```bash
cp config/dummy-google-service.plist iosApp/GoogleService-Info-dev.plist
cp config/dummy-google-service.plist iosApp/GoogleService-Info.plist
cp config/dummy-config.xcconfig iosApp/Assets/Config.xcconfig
brew install xcodegen
cd iosApp && ./.scripts/generate-project.sh
```

If you want to test real iOS sync providers locally, start from `iosApp/Assets/Config.xcconfig.template`
instead of the dummy config and fill in your own keys.

The iOS Xcode project is generated from `iosApp/project.yml` and is not committed.
The generation script also creates the ignored `iosApp/Assets/Config-Debug.xcconfig`
from its tracked template when it is missing.

Regenerate the project whenever `iosApp/project.yml`, iOS source structure, xcconfig files,
entitlements, or SwiftPM dependencies change.

Optional local keys:

- `keystore.properties` for Android/Desktop Dropbox keys
- `desktopApp/src/jvmMain/resources/props.properties` for Desktop Dropbox keys
- `iosApp/Assets/Config.xcconfig` for iOS Google Drive and Dropbox config
- `iosApp/Assets/Config-Debug.xcconfig` for iOS debug Google Drive overrides

## Tech Stack

- Kotlin Multiplatform for shared business logic
- Compose Multiplatform for Android and Desktop UI
- SwiftUI for iOS-specific UI
- SQLDelight-backed local storage
- [RSSParser](https://github.com/prof18/RSS-Parser) for feed parsing

## Translating

If you want to help translate FeedFlow, use [Weblate](https://hosted.weblate.org/engage/feedflow/)
or open a pull request with:

- a new `strings.xml` file under `i18n/src/commonMain/resources/locale/values-<language-code>/`
- matching store copy under `assets/storecopy/<language-code>/`

<div align="center">
  <a href="https://hosted.weblate.org/engage/feedflow/">
    <img src="https://hosted.weblate.org/widget/feedflow/287x66-grey.png" alt="Translation status">
  </a>
</div>

## Contributing

Issues and pull requests are welcome. If you are proposing a larger feature or a platform-specific change,
opening an issue first is usually the fastest way to align on scope.

## License

FeedFlow is released under the [Apache 2.0 License](LICENSE).
