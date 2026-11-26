# UI restyle to the Figma design

The web UI used Material 3's default colour schemes and font. The Figma file
(https://www.figma.com/design/QgRQevknzGb74dkcz4FfAN/ControlLima) draws every page with the Pyrlyn UI kit:
neutral surfaces, a blue accent, IBM Plex Mono everywhere, hairline-bordered cards and compact controls.
This change brings the shared look of the app in line with that file.

## Scope

- Theme: colour tokens for dark and light, IBM Plex Mono typography, shapes. Material components keep
  working because the Material colour scheme is built from the same tokens.
- Shell: sidebar with the brand and the server host, nav grouped into Inventory / Observe / Manage with
  icons, the session and shortcut hints at the bottom.
- Shared widgets in `Ui.kt`: page header (caption on the title line), section card, table header and rows,
  buttons, small actions, picker, chips, search field, tags, status marks, usage bars, banners.
- Page layouts stay as they are; pages pick up the new look through the shared widgets.
- Docs: the Figma URL in `AGENTS.md`, and a `Figma:` link to each page's frame in `docs/pages/*.md`.

Out of scope: per-page layout changes, Kubernetes cluster chips in the header, helper captions the design
adds under some titles.

## Decisions

- IBM Plex Mono (SIL OFL 1.1) ships with the app through Compose resources (`components-resources`,
  part of Compose Multiplatform), so the browser and the desktop app render the same glyphs and the app
  never fetches a font from a third party (CSP). Regular, Medium and Bold are bundled; the design's
  Roboto Mono table headers use Plex Mono Bold instead, to ship one family.
- Icons in the nav are drawn on a Canvas, like the status glyphs, so no icon library is added.
- Token values come from the Figma file's variables (Desktop · Color, Dark and Light modes).

## Steps

1. `gradle/libs.versions.toml`, `web/build.gradle.kts`: add `components-resources`; fonts and their
   licence under `web/src/commonMain/composeResources/font/`.
2. `Theme.kt`: tokens, `LimaTheme`, typography; `App.kt` uses it.
3. `App.kt`: the new sidebar; `Platform.kt` gets `serverHost()` (browser: page host; desktop: base URL).
4. `Ui.kt`: restyle the shared widgets.
5. `server` `StaticFiles`: serve `.ttf` as `font/ttf`.
6. Docs: `AGENTS.md`, `docs/pages/*.md`.
7. Check: `./gradlew build`, then the app in a browser in dark and light themes.
