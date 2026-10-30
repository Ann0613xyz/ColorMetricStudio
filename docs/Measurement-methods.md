# ColorMetric Studio 0.1.0 measurement and software design

## Scope

This is an English-language Windows x64 software for reproducible, manual-region color measurements in PL, Redness, Brown and Inflammation image exports. Any visible skin area may be selected. Images are analyzed locally with embedded ImageJ 1.54g; the application includes its own Java 21 runtime.

## Color conversion and reference

Native ImageJ `ColorSpaceConverter("D65")` converts decoded RGB pixels to CIELAB under an sRGB/D65 assumption. Every image could be assigned either one shared reference skin polygon or a pure-white reference. The skin reference must not overlap the valid measurement ROI. Exclusions apply to both the measurement mask and the reference mask.

For skin reference, the selected channel is averaged over valid reference pixels. For pure white, RGB #FFFFFF is converted with the same ImageJ method; its L* or a* value supplies the reference. Pure white does not set channel values to 255 and is not normalized to surrounding skin. While selected, new reference polygons are blocked and existing reference polygons are inactive. The reference choice is retained per image and recorded in exports.

PL and Brown default to relative darkness: reference mean L* minus pixel L*. Redness and Inflammation default to relative redness: pixel a* minus reference mean a*. Each mode has independent settings. Default thresholds are 5, 3, 5 and 3 respectively. They are exploratory starting settings and require validation for the intended application.

## Masks and primary outcomes

ImageJ `PolygonRoi` supplies pixel membership. A valid measurement mask equals its polygon mask minus the union of exclusion polygons. Exclusions need not be wholly inside one measurement ROI; only overlapping pixels are removed. Invalid geometry, self-intersections, duplicate consecutive vertices and empty valid masks are rejected.

A pixel is affected when its reference-relative signal is greater than or equal to the selected positive threshold.

- Relative color intensity is the mean signal among affected pixels only. It is N/A when no affected pixels are present.
- Affected area (%) is 100 times the affected-pixel count divided by the valid measurement-pixel count. It is zero when none are affected.
- Whole-ROI mean signal is exported as an auxiliary value.

The measurement algorithm remains `affected-lab-threshold-v2`.

## ROI interaction

Users draw polygons using consecutive boundary points. Enter, double-click or Close polygon completes them. Ctrl+Z removes unfinished points. In Edit / move, the closest eligible boundary vertex takes priority; dragging the interior away from a vertex translates the whole polygon without changing its shape or size. Vertex and whole-ROI drag actions are fixed until mouse release and preserve the initial pointer offset. Completed point edits and translations can be undone with Ctrl+Z, one gesture at a time.

Scroll to zoom and right-drag to pan. Analysis locking blocks edits. Editing invalidates cached results and marks the affected project unsaved.

## Copying and alignment

The existing copy dialog selects any open source project and source image, multiple source ROIs, and multiple destination images across projects. Exact copy retains original coordinates, shapes and sizes; users may translate copied polygons manually. Matching ROI IDs are updated, other destination polygons are retained, and a copied skin reference replaces the destination skin reference. In a pure-white destination, source reference polygons are skipped and the destination reference method is retained. Only modified destination projects lose cached results. Two-landmark transfer provides translation and rotation, with optional explicitly recorded scaling.

## Projects and file selection

Multiple projects run in tabs. Image import supports multiple files in one folder, Chinese filenames, and manual typing into Folder address with Enter or Go. Ctrl+L focuses the address. Dragging Images-list entries changes their order and persists in the project. Results are sorted independently by region, mode and visit, with Baseline first and numeric visits ascending. Filename numbers are a fallback when the visit contains no number.

Import and shared save/export folder histories are independent and persist across restarts at `%LOCALAPPDATA%/ColorMetricStudio/settings.properties`, or `.colormetric-studio` under the home directory. On first use, valid stored folders from the legacy application are copied. Automated tests use isolated preference files.

Portable `.pips` files contain original image bytes and SHA256, metadata, ROI geometry, landmarks, per-mode parameters, per-image reference choices and audit history. The application writes a `colormetric-project` metadata root, application name ColorMetric Studio, appVersion 0.1.0, and schema 2. Loading also accepts the legacy metadata root and schema 1 or 2. The internal `peri` Java namespace and `.pips` extension are retained for compatibility.

## Results and exports

Analysis runs in a background worker. The application records mode, visit, ROI and image IDs, color metric, reference type and channel mean, threshold, pixel counts, primary and auxiliary intensity, area percentage, alignment history, quality notes, application/ImageJ versions and algorithm identifier.

Current-project and all-open-project Excel export include Results and Projects worksheets. Distinct project numbers disambiguate identical subject labels. Projects without cached results are listed in Projects. Saving preserves geometry and settings; users rerun analysis after reopening a project to regenerate results.

Result ZIP contents:

- results.xlsx and results.csv with numeric measurements.
- analysis-manifest.xml with reproducibility metadata.
- README.txt with measurement and export definitions.
- overlays with affected-pixel segmentation images.
- imagej-rois with editable ImageJ .roi files and original-color, ROI-boundary-only previews.

ROI previews are at imagej-rois/<image-id>/<source-filename>. Encodings match file extensions. JPEG previews use maximum-quality re-encoding; PNG/TIFF previews are lossless.

## Packaging and release verification

The display name is ColorMetric Studio, release 0.1.0. EXE/MSI installers, a self-contained portable archive, current source archive, English user guide, component notices, checksums and automated-test report form the release.

Checks cover native ImageJ measurements, known pixel counts, threshold effects, reference methods, exclusions, missing intensity, Unicode paths, project round trips, cross-project copying, image order, numeric visit sorting, undo, persistent folders, renamed metadata and legacy-project compatibility. Independent archive inspection verifies image encodings, preview filenames, original-color samples and ImageJ ROI headers. 
