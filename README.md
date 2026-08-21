# S21 Ultra Tiny QR & Data Matrix Scanner

**Optimized Android app** for Samsung Galaxy S21 Ultra to scan very small (< 5mm) and low-contrast **QR codes** and **Data Matrix** codes (including DPM, laser-etched, and reflective metal marks).

---

## Key Features

- **Tiny Code Viewfinder & Reticle**: High-precision reticle and corner brackets designed for framing < 5mm target codes.
- **Tap-to-Focus with Visual Indicator**: Tap anywhere on the live viewfinder to trigger instant macro metering and auto-focus with an animated focus ring.
- **S21 Ultra Macro Lens Default**: Automatically identifies the 12MP Ultrawide lens with Dual Pixel PDAF (~2cm close-focus capability).
- **Smooth Close-Macro Slider**: Real-time Camera2 lens focus distance control without restarting the camera pipeline.
- **Gallery / Local Image Scanning**:
  - **Auto-Detection**: Scans picked images with multi-scale crops, quadrant partitioning, and DPM contrast enhancement.
  - **Interactive Manual Region Selection**: If auto-detection struggles with a complex or tiny code, open the Crop/Zoom tool to pinch, zoom, and select the exact bounding box for instant decoding.
- **DPM & Low-Contrast Enhancement Pipeline**:
  - Multi-pass binarization (Hybrid, Global Histogram, Inverted for laser marks).
  - CLAHE local contrast normalization.
  - Unsharp mask deblurring & Laplacian edge boost.
  - Morphological black-hat filtering for dark dots on metallic textures.
  - Sub-pixel soft matrix sampling and ECC200 error correction.
- **Actionable Results**:
  - Format badges (`[QR Code]`, `[Data Matrix]`, `[Aztec]`).
  - One-tap Copy to Clipboard, Open URL, and Share.
  - Haptic feedback vibration on successful decode.

---

## How to Build

Download the debug APK from GitHub Actions:
1. Go to the **Actions** tab in this repository.
2. Select the latest workflow run.
3. Download the `TinyQR-Scanner-Debug` artifact.
