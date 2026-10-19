# Retro

An open source retro gaming app for Android TV with a tvOS style game browser and a built in NES player.
It is made for low end TVs: it runs well on a 32 bit, 1 GB RAM, GLES 2.0 only television.

## What it does

- **Library**: a hero area for the focused game (cover art, title, author, year, genre, players, description)
  and horizontal shelves below it: **Continue** (recently played), **NES** (everything) and **Favourites**.
  Pitch black background, Outfit type, an ambient glow in the focused game's colour and one glass
  highlight that springs between tiles.
- **Free homebrew catalog**: games listed in `catalog/nes.json` show a **Get** label. Press A to download
  the game from its author's own download link; the file is checked against the catalog's SHA-256 before it
  is stored in app private storage.
  Games hosted on itch.io are fetched the way the page's Download button does it (an empty POST returns a
  short lived signed link), only when you ask, with a back off when itch.io rate limits. If the file no longer
  matches the catalog checksum (the author uploaded a new build), it is shown as "Updated by author, not
  verified" and is not installed. Installed catalog games are checked again before every launch.
- **Your own files**: `.nes` files in `/sdcard/Roms/NES/` show up automatically with a generated cover. A file
  that matches a catalog entry is used directly, without a download.
- **Built in player**: [LibretroDroid](https://github.com/Swordfish90/LibretroDroid) with the
  [fceumm](https://github.com/libretro/libretro-fceumm) libretro core. 4:3 or pixel perfect, sharp or smooth
  filtering, save states, battery saves, and an automatic save when you leave a game so the next launch
  continues where you stopped.

## Controls

### Library

| Action | Xbox controller | TV remote |
| --- | --- | --- |
| Move | D-pad or left stick | D-pad |
| Play / Get | A | OK |
| Favourite | Y | Menu, or hold OK |
| Back / exit | B | Back |

### In game

The mapping is positional (Nintendo layout): the bottom and left face buttons are NES **B**, the right and top
face buttons are NES **A**, just like the B and A buttons sit on an NES pad.

| NES | Xbox controller | TV remote |
| --- | --- | --- |
| D-pad | D-pad or left stick | D-pad |
| A | B or Y | OK |
| B | A or X | |
| Start | Start (Menu button) | Menu |
| Select | Select (View button) | |
| In-game menu | Guide, or Select + Start together | Back |

Each additional controller plays as the next player (player 2 is the second pad).

The in-game menu has **Resume**, **Save state**, **Load state**, **Reset**, **Aspect** (4:3 or pixel
perfect), **Filter** (sharp or smooth) and **Quit to library**. Leaving a game in any way (Quit, Home,
switching apps) saves an automatic state; the game's tile then says **Continue**.

### Display

Overscan is not cropped, so the NES picture's 240 lines scale exactly 3x to 720 lines on a 720p TV. In 4:3 mode
the picture is 960x720, centred; in pixel perfect mode it is 768x720 (exact 3x both ways). The sharp filter keeps
pixels crisp at the non integer horizontal scale of 4:3.

## Known limitations

- **RAR downloads are not supported.** Sir Ababol is only distributed as a RAR archive, and the available
  pure Java RAR readers carry the UnRAR license restriction, which does not fit GPL-3.0. The game is listed
  with a link to the author's page: download it there and copy the `.nes` to `/sdcard/Roms/NES/`. Its
  checksum matches the catalog entry, so it then appears as installed.
- itch.io downloads use the same endpoint as the site's Download button, which is not a documented API. It may
  rate limit or change.

## Module card

Retro shows as a module card in the [tv-vibehack](https://github.com/Gathin23/tv-vibehack) launcher: a card
named **Retro** with the caption "NES games" in amber. The launcher asks before it shows the card.

The declaration is two parts of `app/src/main/AndroidManifest.xml`, both on `LibraryActivity` (the exported
activity that already has `LEANBACK_LAUNCHER`):

```xml
<intent-filter>
    <action android:name="android.intent.action.MAIN" />
    <category android:name="com.gathin.tvhome.category.DOCK_CARD" />
</intent-filter>
<meta-data
    android:name="com.gathin.tvhome.card"
    android:resource="@xml/module_card" />
```

and `app/src/main/res/xml/module_card.xml`:

```xml
<card spec="1" label="Retro" caption="NES games" accent="#E8A33D" />
```

### Verifying a release APK

Before installing a release APK, check that it is the published file and that it is signed with Retro's release
key. Each release lists the APK's SHA-256 and the signer digest in its release notes.

```sh
shasum -a 256 retro-release.apk                 # or: sha256sum retro-release.apk
apksigner verify -print-certs retro-release.apk  # from the Android SDK build-tools
```

The first value must match the release's APK SHA-256, and the `Signer #1 certificate SHA-256 digest` line must
read:

```
0202281bb726dbc12b82622dee50a278656128a82204db3cf055f8f5c006e405
```

Every release of Retro is signed with this same key. Do not install an APK signed with any other key as Retro.

## Legal policy

- **No commercial ROMs.** This project does not ship, link to or help find copyrighted games.
- The catalog only lists **free homebrew** whose authors allow free distribution. The app does not host or
  bundle any game: it downloads each one from the author's own `download_url` and verifies it against the
  checksum in the catalog. Each entry records its license and source page.
- You can add **your own dumps** of cartridges you own to `/sdcard/Roms/NES/`.
- ROM files are never committed to this repository and never packaged in the APK.

## Repository layout

```
LICENSE                 GPL-3.0
catalog/nes.json        game catalog (metadata only), bundled into the APK at build time when present
app/                    the Android app (Gradle project)
  cores/fetch-cores.sh  downloads the fceumm core from the libretro buildbot
  cores/SHA256SUMS      checksums of the cores used by the last build
  src/main/res/xml/module_card.xml   the module card for the tv-vibehack launcher
```

### Catalog schema

`catalog/nes.json` is an array (or `{"games": [...]}`) of objects:

`id`, `title`, `author`, `year`, `genre`, `players`, `description`, `license`, `redistribution`,
`source_page`, `download_url`, `zip_entry` (optional: the `.nes` inside a zip download), `rom_sha256`,
`rom_crc32`, `mapper`, `cover_url`.

## Building

Requirements: JDK 17 and the Android SDK (platform 34, build tools 34).

```sh
cd app
cores/fetch-cores.sh            # fetch the fceumm core for armeabi-v7a and arm64-v8a
./gradlew assembleDebug         # app/build/outputs/apk/debug/retro-debug.apk
```

Install with `adb install -r`.

Debug builds are signed with a local debug key (`app/keystore/debug.keystore`, generated on the first build and
never committed). Release builds (`./gradlew assembleRelease`) are signed
with a private key that is not part of this repository. Point the build at it with `app/keystore.properties`
(ignored by git):

```properties
storeFile=/path/outside/the/repo/release.jks
storePasswordFile=/path/outside/the/repo/release.password
keyAlias=retro-release
```

or with the `RETRO_RELEASE_STORE_FILE`, `RETRO_RELEASE_STORE_PASSWORD_FILE` and `RETRO_RELEASE_KEY_ALIAS`
environment variables. A release build without them stops with an error instead of producing an APK signed
with another key.

The APK contains native code for `armeabi-v7a` (32 bit ARM, the target TV) and `arm64-v8a`.

## Tested device

Xiaomi Mi TV (MiTV-AXSO0): Android TV 9 (API 28), Amlogic txlx, 4x Cortex-A53 at 1.4 GHz, 32 bit only,
Mali-450 (OpenGL ES 2.0), 1 GB RAM, 1280x720 UI. Xbox controller over USB or Bluetooth, plus the TV remote.

## Credits

- [LibretroDroid](https://github.com/Swordfish90/LibretroDroid) by Filippo Scognamiglio, GPL-3.0: the libretro
  frontend that powers Lemuroid.
- [FCEUmm libretro core](https://github.com/libretro/libretro-fceumm), GPL-2.0-or-later, built by the
  [libretro buildbot](https://buildbot.libretro.com/).
- [Outfit](https://github.com/Outfitio/Outfit-Fonts) typeface, SIL Open Font License 1.1
  (see `app/FONT-LICENSE-OFL.txt`).
- Visual design and the glass highlight are adapted from
  [tv-vibehack](https://github.com/Gathin23/tv-vibehack) by Gathin23 (MIT).
- Every catalog game belongs to its author; see each entry's `license` and `source_page`.

## License

GPL-3.0. See [LICENSE](LICENSE).
