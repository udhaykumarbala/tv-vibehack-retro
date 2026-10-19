# Retro v0.1.0

First release of Retro, an open source NES game browser and player for Android TV, made for low end TVs
(32 bit ARM, 1 GB RAM, OpenGL ES 2.0).

## What is in it

- Library with a hero area and Continue, NES and Favourites shelves.
- Free homebrew catalog: each game is downloaded from its author's own link and checked against the
  catalog's SHA-256 before it is stored.
- Your own `.nes` files from `/sdcard/Roms/NES/`.
- Built in player (LibretroDroid with the fceumm core): save states, battery saves and an automatic save when
  you leave a game.
- Module card for the tv-vibehack launcher: label "Retro", caption "NES games", accent `#E8A33D`.
- All downloads use HTTPS; cleartext traffic is not allowed.

Requires Android 8.0 (API 26) or newer. Native code for `armeabi-v7a` and `arm64-v8a`.

## Download and verify

File: `retro-release.apk`

| | |
| --- | --- |
| APK SHA-256 | `a3bcf445e1062d9e905b1d21b347236a6831e9ace6bfa1e76f9d154acf2d9db8` |
| Signer #1 certificate SHA-256 digest | `0202281bb726dbc12b82622dee50a278656128a82204db3cf055f8f5c006e405` |
| Signer certificate DN | `CN=Retro, O=tv-vibehack-retro` |

```sh
shasum -a 256 retro-release.apk
apksigner verify -print-certs retro-release.apk
```

Both values must match the table. Every release of Retro is signed with the same key.

## Installing

```sh
adb install retro-release.apk
```

If an earlier build signed with the debug key is installed, uninstall it first (`adb uninstall
io.github.udhaykumarbala.retro`): Android refuses an update signed with a different key. Save states and
downloaded games of that build are removed with it.
