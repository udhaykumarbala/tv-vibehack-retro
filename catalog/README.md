# NES homebrew catalog

Curated list of free, legal NES homebrew games for tv-vibehack-retro. Machine-readable data lives in [`nes.json`](nes.json).

Last verified: 2026-10-19. Every `download_url` was fetched end to end, the `.nes` was extracted where needed, checked for the `NES\x1a` header, and matched against `rom_sha256`.

## Policy

- **Never host or bundle commercial ROMs.** No Nintendo or other commercial titles, no ROM-site mirrors, no archive.org dumps. Only games the author themselves offers as a free download.
- **`redistributable`**: the author granted a license that allows sharing (open source, Creative Commons, WTFPL). The app may cache or mirror these, keeping the license and attribution. Several are NonCommercial (CC BY-NC / BY-NC-SA), so redistribution is only fine while the app stays free and non-commercial.
- **`free-download-only`**: free from the author, but no redistribution grant. The app must download these from the author's URL at runtime, on the user's request, and must never bundle or re-host them.
- Always verify the downloaded `.nes` against `rom_sha256` before launching. On mismatch (the author shipped an update), show the game as "updated upstream" and do not launch until the catalog is refreshed.
- `rom_crc32` and `rom_sha256` are computed over the whole `.nes` file, header included.

## Included games

| Title | Author | Players | License | Redistribution | Mapper | Source |
|---|---|---|---|---|---|---|
| Nova the Squirrel | NovaSquirrel | 1 | GPL-3.0+ code, CC BY-NC-SA 4.0 assets | redistributable | 1 (MMC1) | GitHub release |
| Super Tilt Bro. | sgadrat | 1-2 | WTFPL | redistributable | 2 (UNROM, 512 KB) | itch.io |
| Lizard (Demo) | Brad Smith | 1 | freeware demo, all rights reserved | free-download-only | 34 (BNROM) | itch.io |
| Spacegulls | Morphcat Games | 1-2 | freeware ($0 name your own price) | free-download-only | 2 (UNROM) | itch.io |
| Super Bat Puncher (Demo) | Morphcat Games | 1-2 | freeware demo ($0 name your own price) | free-download-only | 1 (MMC1) | itch.io (zip) |
| Mosh Lift | Morphcat Games | 1-2 | freeware ($0 name your own price) | free-download-only | 34 (BNROM) | itch.io |
| Driar | Stefan Adolfsson, David Eriksson | 1 | CC BY-NC 3.0 | redistributable | 1 (MMC1) | driar.se (zip) |
| Cheman | The Mojon Twins | 1 | LGPL-3.0 | redistributable | 0 (NROM) | mojontwins.com |
| Espitenen und Herr Pimponen | The Mojon Twins | 1-2 | LGPL-3.0 | redistributable | 3 (CNROM) | mojontwins.com (zip) |
| Sir Ababol | The Mojon Twins | 1 | CC BY-NC-SA 3.0 (site license) | redistributable | 0 (NROM) | mojontwins.com (RAR) |
| The Cowlitz Gamers' 2nd Adventure | Lukasz Kur, M-Tee | 1-2 | freeware (author accepts no payment) | free-download-only | 2 (UNROM) | itch.io (zip) |

All mappers above are supported by the fceumm core.

## Excluded

| Game | Reason |
|---|---|
| Alter Ego (Shiru) | Author site `shiru.untergrund.net` unreachable (connection timeout) on 2026-10-19, so no live official download. The author also noted there is no formal license (it is a licensed port of Denis Grachev's game). Recheck later. |
| Lan Master (Shiru) | Reported as CC0 in a nesdev list, but the official download site is unreachable, so the download could not be verified from the author. Good candidate to re-add if the site returns. |
| Blade Buster (High Level Challenge) | Free ROM on the author's site, but no license, and the author's page implies the music is not original and the game contains bonus references to other games. Third-party IP unclear. |
| Lizard (full game) | Paid ($10). Only the free demo is included. |
| Micro Mages, Böbl (Morphcat) | Paid. |
| Espitene (Mojon Twins) | Free, but it is a Sonic fan game (Somari parody), so it relies on third-party IP. |
| Cheril the Goddess (NES, Mojon Twins) | No official NES download found (game card 404, `GoddessR` repo holds only a README). |
| Mojon Twins dev ROMs on GitHub (Cave_NES, SuperUwol_NES, Journey_NES, ninnin64) | ROMs exist in unlicensed repos as work-in-progress builds; no release page or license. Possible future free-download-only entries after checking with the authors. |

## Fetching from itch.io (app implementation note)

itch.io `download_url` values have the form `https://<author>.itch.io/<game>/file/<upload_id>?source=game_download`. They are not direct file links:

1. Send an HTTP `POST` (empty body) to that URL.
2. The response is JSON: `{"url": "<signed CDN URL>", "external": false}`. The signed URL expires after about 60 seconds.
3. `GET` the signed URL immediately, then extract `zip_entry` if present.

This works for games whose minimum price is $0 (free or name your own price). It is the same request the itch.io page makes when you click Download, but it is not a documented public API: itch.io rate limits it (HTTP 429), and `upload_id` changes when an author uploads a new build. Only fetch on explicit user action, cache the verified ROM locally, and back off on 429.

## Archive notes

- `zip_entry` names the file inside the archive for zip downloads.
- Sir Ababol is distributed as a **RAR** (v4) archive; `zip_entry` holds the RAR entry name. The app needs a RAR reader for this one, or, since it is redistributable under CC BY-NC-SA, it may be mirrored as a plain `.nes` with attribution and license.

## Local test files

`roms/` holds the downloaded `.nes` files for local testing only. It is gitignored and must never be committed.
