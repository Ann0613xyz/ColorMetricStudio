# ColorMetric Studio

Version 0.1.0 · English interface · Windows x64 · Embedded ImageJ 1.54g 

An offline desktop tool for manual polygon ROIs, longitudinal ROI transfer, and image-derived color and affected-area measurements in PL, Redness, Brown and Inflammation exports. Measurement ROIs can cover any visible skin area; the application is not restricted to the eyes.

**Research prototype.** Technical checks do not establish clinical validity. Default thresholds are exploratory starting values. Area is reported as a percentage; no mm² conversion is performed without physical calibration. Color results are not melanin or hemoglobin concentrations.

Chinese filenames and folder names are supported without renaming. Buttons, menus and help remain in English. Older .pips projects can be opened. Images with multiple reference ROIs require you to keep one and delete the extras before analysis. Legacy reference links are ignored.

## Install and start

- Run `ColorMetricStudio-0.1.0.exe`, choose the installation folder and finish. ImageJ and Java are included; no separate installation or internet connection is required.
- Start ColorMetric Studio from the desktop or Start menu.
- Alternatively, extract the complete portable ZIP and run `ColorMetric Studio.exe` inside its folder. Keep `app` and `runtime` together with the executable.
- Use **File → Open synthetic demo** to practice without patient photographs.

The installer is unsigned. Windows may display an unknown-publisher prompt. Uninstall through Windows Settings → Apps. Saved project and result files remain wherever you saved them.

## Folder navigation

Every file browser has a **Folder** address field at the top. Type or paste an existing folder path, then press **Enter** or click **Go**. This opens the folder without importing or saving a file. Use **Ctrl+L** to select the address field. Chinese folder names, spaces, quoted paths and relative paths are supported. You can still browse by clicking folders. Save dialogs retain the proposed filename when you change folders; the **File name** field is separate. Invalid paths show an English message and leave the current folder unchanged.

## Project tabs

Click **New project** or **Open project** to add a tab. The file chooser supports selecting multiple project files. Click a top tab to switch; images, ROIs, parameters, results, selected image and unfinished polygon vertices stay with that project. Reopening an already open file selects its existing tab. Save applies to the active tab. Export can include the current project or all open projects. Close a tab with its × button or **File → Close current project**; unsaved changes prompt you to save. Exit checks every unsaved project. Switching and closing are blocked during analysis.

## 1. Import images

Use one project per subject and enter an anonymous Subject ID. Click **Apply subject ID**. Import images for one visit at a time, or edit visit labels afterward. PL, Redness, Brown and Inflammation modes are inferred from recognizable filenames; verify them in Image details and click **Apply image details**. Unknown filenames default to PL and require manual verification. Supported inputs: JPG, PNG and TIFF supported by the bundled reader. The input limit is 40 megapixels and 60 MB per image.

Use original device exports. Maintain equipment, software version, exposure, pose and export settings across visits. Do not analyze screenshots with burned-in boundaries, annotations or color bars covering skin. User filenames and entered labels are preserved as entered, including non-English characters.

The Import images browser starts in the last folder visited, including after closing and restarting the application. Import history is independent from save/export history and is stored in your local user settings, not in projects. Canceling the chooser retains its last browsed folder. If a remembered folder no longer exists, the browser falls back safely.

Drag an image up or down in the left **Images** list and drop at the insertion line to change its order. The order is saved in the project and restored on reopening. This keeps images inside their current project; it does not move them between projects. Results retain their separate automatic sort. Reordering preserves existing analysis results.

## 2. Draw reference and measurement ROIs

- Choose **Reference skin** as the ROI role. The ROI name automatically becomes `reference`; no renaming is needed. Then click **Draw polygon**, and click consecutive vertices. Double-click or press Enter to close. While drawing, press Ctrl+Z or Backspace to remove the most recent vertex. Repeat to remove earlier vertices; completed ROIs are not undone.
- Choose **Measurement ROI**, name the region. Every measurement region automatically uses the image's one reference skin ROI; no linking is required. Draw a polygon around a fixed observation area.
- Add as many measurement regions as needed. All use the same reference. Creating a second reference is blocked; edit or delete the existing reference if it needs replacement.
- Add **Exclusion ROI** polygons for hair, lashes, moles, glare or other predefined exclusions. Their union is removed from both targets and references. Overlapping exclusions are counted once. Exclusions may cross an ROI boundary: only overlapping pixels are removed. Portions outside all measurement and reference ROIs have no effect.

Reference skin should be appropriate to the anatomical site, unaffected by the target condition, and stable across visits. A target may not overlap the shared reference. Define beforehand whether freckles, discrete brown spots and fold shadows belong to the outcome.

## Optional pure-white reference

In **ROI definition**, select **Use pure white as reference** to use sRGB `#FFFFFF` (RGB 255/255/255) instead of drawing reference skin. This choice belongs to the **current image** and is saved in its project. Every measurement region in that image uses the same choice. Reference skin cannot be chosen as a new ROI role while this option is active. Existing reference polygons are retained but inactive; deselect the option to use them again. Toggling clears unfinished vertices and computed results; reanalyze afterward.

The existing color definitions remain: darkness = reference L* minus pixel L*; redness = pixel a* minus reference a*. Pure white is converted with the same native ImageJ sRGB/D65 method, using its L* or a* channel as appropriate. It does not set both channels to 255 or switch redness to darkness. The threshold, affected-pixel-only intensity and affected-area percentage rules are unchanged. A pure-white signal is not normalized to the patient's surrounding skin and may identify more affected pixels. Use a consistent reference method across visits.

When copying ROIs into an image using pure white, source reference polygons are skipped; its reference choice is not overwritten. Existing inactive references remain unless all ROIs are explicitly replaced. Excel and CSV append **Reference type** (`PURE_WHITE` / `SKIN_ROI`) and record the actual reference channel mean. The manifest also records the choice for each image.

ColorMetric Studio 0.1.0 opens legacy schema-1 and schema-2 projects. Newly saved projects use schema 2 and retain the per-image reference choice. Reopen them with ColorMetric Studio.

## Inflammation images

Select **Inflammation** for the device inflammation / red-area export. New imports recognize filenames containing the device red-area or inflammation names and English `inflammation` / `red_area`. It is a separate mode from **Redness**, which continues to represent vascular / red-blood-vessel exports. Already imported images keep their saved mode; change Image mode to Inflammation and click Apply image details if required.

The default signal is target-pixel a* minus shared-reference mean a*, using native ImageJ CIELAB conversion. The initial threshold is 3 relative Lab units, with independent parameters; the existing project-wide lock applies to all modes. Compute mean signal over detected pixels and affected area percentage exactly as for other modes. No detected pixels yields N/A intensity and 0% area. The result quantifies displayed relative redness in this enhanced image, not a clinical diagnosis or biological inflammation level.

## 3. Inspect, edit and transfer ROIs

Scroll to zoom around the pointer; right-drag or middle-drag to pan. Click **Fit image** to reset the view. In **Edit / move** mode, drag inside an ROI to select it and translate the whole polygon without changing shape or size. Near a boundary point, dragging edits the nearest point, even when the pointer is just inside the ROI. Away from points, dragging inside moves the whole ROI. The crosshair cursor indicates point editing; the move cursor indicates whole-ROI movement. You can select an ROI by dragging near its point. Small ROIs use a smaller grab radius so their centers remain available for whole-shape movement. Zoom in for dense points or very small regions. Point dragging preserves the initial click offset to avoid a jump. Editing invalidates computed results.

After a whole-ROI drag or boundary-point adjustment, press **Ctrl+Z** with the canvas focused in **Edit / move** to restore its previous coordinates. Each completed drag counts as one step, regardless of mouse movement frames. Repeated presses undo mixed movement and point edits in reverse order, independently for each project. Undo may select the image containing that edit. Undo invalidates cached measurements, marks the project unsaved and records history; reanalyze as needed. Up to 100 steps are kept per open project during this session and are not saved in .pips files. Copying or replacing an image's ROIs clears its obsolete history. In Draw polygon, Ctrl+Z continues to remove unfinished vertices; Backspace keeps that same drawing behavior.

| Transfer option | What it does | Required check |
| --- | --- | --- |
| Copy exact coordinates to current | Copies every vertex unchanged, including reference and exclusion ROIs. | Identical pixel dimensions do not prove identical anatomical coverage. |
| Copy source ROIs to other images | Choose any source project and image, then select multiple source ROIs and destination images across open projects with Ctrl / Shift. Copy only the selected polygons at exact coordinates; update matching ROI IDs and retain other ROIs. A selected reference replaces the destination reference. | Verify every target separately. |
| Transfer using two landmarks | Translation and rotation using two corresponding points. Optional uniform scaling is explicitly recorded. | Use the same anatomical positions and point order. A rigid transfer reports residual mismatch at the second point. |

For **Copy source ROIs to other images**, first open the source and destination projects in top tabs. In the copy dialog, choose **Source project** and **Source image with ROIs**; any image can be used, regardless of import order. Select source ROIs and destination images with Ctrl / Shift, then confirm. The target list includes all open projects, identified by project number/name and image metadata. The source image itself is excluded. Copying does not switch the active tab. Only destination projects become unsaved and lose cached results; untouched projects retain their results. All ROI roles can be copied. If you omit the reference, the destination reference stays. Copied vertices are independent of the source and other destinations. Move copied ROIs in **Edit / move** by dragging their interiors; then verify each destination.

Select a source image with ROIs. For landmark transfer, mark two corresponding points on both images using **Mark two landmarks on current image**. Leave **Allow uniform scaling** unchecked to preserve geometric size. Rotation may slightly change the rasterized pixel count; this is not a physical area calibration. No deformable or automatic facial registration is performed. Large pose or eyelid-closure changes may be unsuitable for comparison.

Before analysis, inspect boundaries and image modes. No position or mode confirmation checkbox is required. Self-intersecting polygons, out-of-bounds ROIs, empty valid regions, missing references, and reference overlap are rejected.

## 4. Define and lock measurement parameters

| Mode | Default relative signal at each target pixel | Exploratory threshold |
| --- | --- | --- |
| PL | Mean L* of reference skin − target-pixel L* | 5 |
| Redness | Target-pixel a* − mean a* of reference skin | 3 |
| Brown | Mean L* of reference skin − target-pixel L* on the enhanced Brown image | 5 |
| Inflammation | Target-pixel a* − mean a* of reference skin on the enhanced red-area image | 3 |

ImageJ's native ColorSpaceConverter converts RGB to CIELAB assuming sRGB and D65. Enhanced images lack a validated physical color/chromophore calibration. The Brown metric measures displayed relative darkness, not pigment concentration or depth. PL darkness can contain structural or vascular contributions.

Click **Apply parameters** after editing. Parameters are shared by all images in that mode across visits. Lock them after validating the method on independent images. Do not independently choose thresholds for baseline and follow-up or tune them to treatment outcomes. Changing applied parameters invalidates cached results.

## 5. Analyze and interpret the two primary outputs

Click **Analyze current** or **Analyze all**. The first primary result is **Relative color intensity**: the mean relative signal over affected pixels only. Affected pixels are valid measurement pixels whose signal meets or exceeds the applied threshold. Pixels below the threshold do not contribute to this intensity value. If none are detected, intensity is **N/A** and affected area is **0%**.

The second is **Affected area (%)**:

100 × number of valid target pixels with relative signal at or above the threshold ÷ number of valid target pixels.

The threshold comparison uses the float signal also supplied to ImageJ statistics. Red segmentation overlays show classified pixels. The whole-ROI mean is retained only as an auxiliary export column. The primary affected-pixel mean can increase when faint margins disappear, even while area decreases; interpret intensity and area together. With a positive threshold, a detected-pixel intensity cannot be negative.

Each ROI produces two primary values per mode, or eight across four modes. Do not add the four mode values. Percentages are not automatically comparable when anatomical coverage, exclusions, pose or segmentation rules differ. No physical area is derived from JPEG dpi.

**Version change:** The current release uses algorithm `affected-lab-threshold-v2`. Earlier primary intensities used the whole ROI. Reanalyze all visits with this version and the same parameters; do not combine old and new primary intensities.

## 6. Save and export

Save and export dialogs share the last browsed output folder across all project tabs and application restarts. This covers .pips projects, current/all-project Excel, result ZIPs and ImageJ ROI ZIPs. It also applies after canceling a dialog; an approved file selection records its actual parent folder. Import navigation remains independent. Saving an already saved project still writes to its existing file; use File → Save project as to choose a new location.

**Save project** creates a portable `.pips` file including original image bytes, SHA256 checksums, visit and mode labels, polygon vertices, reference roles, exclusions, landmarks, alignment records, applied parameters and audit history. Opening it on another computer requires no original image paths. Re-run analysis after opening. Older projects gain default Inflammation settings without changing existing labels or parameters. ColorMetric Studio supports all four modes and both legacy project schemas.

The Results table groups records by **region → Baseline first → numeric label → mode**. Use consistent ROI names across visits. Baseline is recognized in the Visit label (case-insensitive, including the Chinese baseline label) and comes before all other records, even Visit 0. Other labels use their first number: for example W2, 6w, 10 and After 18 sort as 2, 6, 10, 18; decimal numbers are supported. If a non-baseline Visit has no number, the first number in its filename is used. Records with no number follow numbered records. Mode breaks numeric ties; otherwise order is stable. Excel and CSV use the same sort independently of the left image-list order. Apply the intended Visit label in Image details to avoid interpreting acquisition timestamps as visit numbers.

**Export results** offers **Current project (Excel)** and **All open projects (Excel)**. Each option saves one `.xlsx` file with a **Results** sheet containing every cached row shown in the relevant projects' Results tables, and a **Projects** sheet listing every included project. Project numbers distinguish duplicate names. Projects without results are explicitly listed; analyze them first if their measurements should be included. An entirely empty export is rejected. Opening a saved project requires analysis to regenerate results; the export does not automatically analyze images.

**Current project (ZIP package)** remains available for overlays, ROI previews and reproducibility records. It creates a ZIP containing:

- `results.xlsx`: numeric values, English headers, filtering and frozen header row.
- `results.csv`: UTF-8 BOM for Excel; text beginning with formula-like characters is escaped.
- `analysis-manifest.xml`: reproducibility metadata and operation history.
- `overlays/<source-filename>`: source-named image previews with boundaries and affected pixels. Exact duplicate or case-colliding source names use separate `<image-id>` subfolders to prevent overwriting.
- `imagej-rois/`: ImageJ-compatible ROI files with role properties, plus `<image-id>/<source-filename>`: a full-resolution original-color preview showing all ROI boundaries and names, without any analysis segmentation tint. Measurement outlines are cyan, reference outlines green, and exclusions orange. One preview is written for each image represented in the exported results. Both preview folders retain the original filename, Chinese characters, case and JPG/PNG/TIFF extension. Encoded image formats match those extensions. JPG previews use maximum-quality JPEG encoding; PNG/TIFF previews are lossless. Original project images and all measurements are unchanged. Unsafe names in legacy project metadata are made safe for extraction. Legacy reference properties are retained for file compatibility but are not used.

Result exports may contain patient images and filenames. Share only appropriate anonymized exports. The application itself has no upload or telemetry function.

## Technical verification and remaining validation

Checks cover known pixel area fractions, native Lab anchor values, exclusions, reference matching, multiple regions, rigid/scaled transfer, project round trips, image integrity, ROI decoding, spreadsheet output and four-mode calculations. The supplied device JPEGs have passed input compatibility checks. These checks do not establish clinical validity.

Before using the tool as a study endpoint, evaluate repeated tracing, inter-rater reproducibility, repeated acquisition where available, expert agreement on segmentation, and comparison with external color or pigment measurements where available. Keep exploratory image endpoints separate from prespecified clinical endpoints.

## Sources and components

[ImageJ measurement documentation](https://imagej.net/ij/docs/menus/analyze.html) · [ImageJ licensing](https://imagej.net/licensing/) · [Digital image evaluation of pigmentation and erythema](https://pubmed.ncbi.nlm.nih.gov/16522135/) · [Effects of camera distance and angle](https://pubmed.ncbi.nlm.nih.gov/36772956/)

Application source: MIT license. ImageJ disclaimer and reduced OpenJDK runtime licenses accompany the installation. See `app/THIRD-PARTY.txt` and `runtime/legal/`.
