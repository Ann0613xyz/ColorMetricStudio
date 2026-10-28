# ColorMetric Studio 0.1.0 validation

Verified on 2026-10-27 using this Windows x64 host and synthetic images.

- 348 checks passed from compiled source and again using the final bundled runtime and JAR. They cover ImageJ measurements, masks, reference choices, threshold behavior, Chinese paths, ROI copying/editing/undo, image ordering, persistent folder history, exports, renamed metadata and legacy compatibility.
- Independent ZIP/Pillow inspection verified source filenames, native image dimensions, JPEG/PNG/TIFF signatures, duplicate-name handling, original-color preview samples and editable ImageJ ROI headers. Result ZIPs omit valid-rois and masks.
- Independent Excel readback verified numerical values, N/A intensity versus zero area, current/all-project coverage, baseline-first numeric visits, filters and headers.
- A saved pre-rename synthetic project opened successfully. Its 16 measurement rows matched the previous results exactly apart from the application version. Original image checksums and measurement parameters were retained.
- The measurement engine SHA256 remains d345216c5ffa58a675177b797991b3cd887883b84afae369cd82fc052f4d87b7. Neither pixel detection nor the quantitative measurement definitions were changed.
- MSI inspection verified ProductName ColorMetric Studio, ProductVersion 0.1.0, English language 1033, new upgrade identity F0C076B3-4BA3-4C74-9E87-D8791F2E6412 and renamed desktop/Start-menu shortcuts. Extracted JAR, user guide and component notices match the tested app image.
- Final application JAR SHA256: a29f6328a66a1dcbe4ee14fe9ddef4db0d53ca67fdb38770e2e5c31c84769c6d.
- The application preview, revised methods documents and vector workflow diagrams were visually inspected. The current source and portable ZIPs passed integrity checks.

The workspace cleanup removed 16 previous release directories, 16 previous build directories, the obsolete release-QA snapshot and nine obsolete upgrade scripts. Research projects and analysis data were retained.

The new .pips metadata root is colormetric-project; loading still accepts the legacy root and schema 1 or 2. The .pips extension and internal Java namespace are retained for compatibility. Legacy preference directory names appear only in the compatibility reader.

A clean-machine interactive install/uninstall cycle was not performed. WiX decompilation emitted the standard jpackage dialog-reference warnings; the generated MSI was not modified. Technical tests do not establish clinical measurement validity.
