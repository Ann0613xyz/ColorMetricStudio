# ColorMetric Studio 0.1.0

Quantitative color analysis across imaging modes.

ColorMetric Studio is an English-language Windows x64 desktop application for manual polygon ROI measurements in PL, Redness, Brown and Inflammation image exports. Analysis can be applied to any visible skin region. ImageJ 1.54g and a Java runtime are included; users do not install them separately. All processing is local.

The two primary outcomes are relative color intensity among affected pixels and affected area percentage. Read `UserGuide.html` for instructions and `Design.md` for the exact measurement definitions. The default thresholds are exploratory settings, not clinically validated cutoffs.

## Current features

- Batch image import, Chinese filenames and typed folder addresses.
- Multiple project tabs and image-list drag reordering.
- Multiple polygon measurement ROIs, one shared skin reference or an optional pure-white reference per image, and exclusion ROIs.
- Any-image and cross-project batch ROI copying. Whole-ROI translation preserves shape and size; nearby vertices take priority when editing boundaries.
- Ctrl+Z for unfinished points, whole-ROI moves and individual vertex edits.
- Baseline-first numeric visit sorting in results.
- Current-project or all-open-project Excel export, plus optional result ZIP packages.
- ZIP image previews retain imported source filenames, case, Chinese characters and JPG/PNG/TIFF extensions. Duplicate names remain in separate image-ID folders.
- Independent import and shared save/export folder history persists across restarts. Previous folder settings are migrated automatically on first use when available.
- Portable `.pips` projects retain original image bytes, ROI coordinates, settings and audit records. Existing schema-1/schema-2 projects remain readable. Reanalyze after opening a project.

## Release files

- `ColorMetricStudio-0.1.0.exe`: Windows installer.
- `ColorMetricStudio-0.1.0.msi`: Windows Installer package.
- `ColorMetricStudio-0.1.0-portable.zip`: extract the entire archive and run `ColorMetric Studio/ColorMetric Studio.exe`.
- `ColorMetricStudio-0.1.0-source.zip`: current application source and build instructions.

Keep the portable `app` and `runtime` folders together. The release includes only synthetic demonstration images. The product name and release numbering have been reset; the image-analysis engine and algorithm identifier remain unchanged.

## Build

Requirements: Windows x64, JDK 21 on PATH, bundled `lib/ij.jar`, and WiX 3.14.1 portable binaries in `tools/wix`. The ImageJ and runtime notices must remain with redistributed copies.

```powershell
./tools/Get-BuildTools.ps1
./build.ps1 -Stage all
```

`-Stage image` compiles, runs regression checks and creates `build-0.1.0/app-image/ColorMetric Studio`. `-Stage installer` packages an existing app image. Outputs go to `../../outputs/ColorMetricStudio-0.1.0`. An existing app image is never silently overwritten. Use a fresh build directory for a rebuild.

The Windows display name is `ColorMetric Studio`, version `0.1.0`. The installer uses a new product upgrade identity for this release numbering. Installation does not uninstall a differently named legacy application. Existing projects can be opened directly.

## Verification

```powershell
& './build-0.1.0/app-image/ColorMetric Studio/runtime/bin/java.exe' -jar './build-0.1.0/app-image/ColorMetric Studio/app/ColorMetricStudio.jar' --self-test './validation-output'
```

`ColorMetric Studio.exe --self-test <absolute-output-folder>` also works; check `test-report.txt` because GUI launchers do not show console output. `--preview <png-path>` renders an off-screen synthetic UI preview. Self-tests isolate preference storage and never use patient photographs.

User preferences are stored at `%LOCALAPPDATA%/ColorMetricStudio/settings.properties`, with a `.colormetric-studio` home-directory fallback. Project files do not contain computer-specific folder preferences.

## License

Application code is MIT licensed. ImageJ, the included Java runtime and build tools have separate notices in `THIRD-PARTY.txt` and the installation folder. Technical regression checks do not establish clinical measurement validity.
