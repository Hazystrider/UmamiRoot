# Changelog

All notable changes to **UmamiRoot** are documented in this file.
Versioning follows [Semantic Versioning](https://semver.org/) (`MAJOR.MINOR.PATCH`).

## [6.2.0] - 2026-11-28

### Added
- **YukiSU** flavor wiring in the 2×2 `FlavorSwitch` (`KernelSU` | `KSU Next` / `SukiSU-Nova` | `YukiSU`), `YukisuProfiles.kt`, and `DfKsudUpdater.Flavor.YUKISU` (targets `yukisu-kdp-*` releases on `Hazystrider/UmamiRoot` with upstream `Rouyashiki/YukiSU` fallback and isolated release matching).
- `DFBridge.load("yukisu")` with safe `libexpyukisu.so` → `libexp.so` fallback and `YukisuProfiles` staging.
- GKI 2.0 (`5.10`–`6.18`) supported models & kernel compatibility table in `README.md`.
- Unit tests covering `YukisuProfiles` ID parsing and isolated SukiSU-Nova vs YukiSU release asset selection.

## [6.1.0] - 2026-11-27

### Added
- First-class **SukiSU-Nova** flavor tab alongside **KernelSU** and **KSU Next** in the 3-way `FlavorSwitch`.
- Bundled Samsung KDP/RKP/DEFEX SukiSU-Nova profile (`assets/df/ksud-sukisu/3.0.0-kdp-samsung`, from `sukisu-kdp-20261127-030955`).
- Unified one-tap SukiSU-Nova updater in `DfKsudUpdater` (pulls Samsung KDP releases from `Hazystrider/UmamiRoot`, with upstream `SukiSU-Nova/SukiSU-Nova` fallback + byte-equality deduplication).
- `libexpsukisu.so` native library loading in `DFBridge.load(String flavor)` and `DfEngine`.
- UniMount-inspired Cool Slate + Jade Mint theme (`umamiDarkColorScheme` / `umamiLightColorScheme` + synced XML colors).
- Custom hero SVG banner (`assets/banner.svg`) and rewritten `README.md`.
- Unit tests for SukiSU-Nova profile ID parsing and release asset selection.

### Fixed
- `ManagerReleaseFetcher` now scans up to 10 GitHub releases for a selectable `ksud` asset so APK-only releases do not block updates.

## [6.0.0] - 2026-11-26

### Added
- Initial UmamiRoot V6 base on top of UniRoot with custom adaptive Jade Mint ghost-unlock icon.
- KernelSU 3.4.0 alignment with `shedowe19`, rewired device identification extended to Galaxy Note 20, and soft-reboot root state persistence without `su` permissions.
- SukiSU-Nova Samsung KDP/RKP/DEFEX kernel patch and automated GitHub Actions build workflow (`build-sukisu-nova-kdp.yml`).
