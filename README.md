# S21 Ultra Tiny QR Scanner

**Optimized Android app** for Samsung Galaxy S21 Ultra to scan very small QR codes (< 5mm), including low-contrast black / gray / white codes.

The stock camera app struggles with focus and resolution on tiny codes. This app forces the **ultrawide camera**, optimizes close-range focus, applies digital zoom + image preprocessing, and uses robust decoding.

## Goals
- Reliable focus at 1–5 cm distance
- Work with low-contrast (gray/white/black) QR codes
- Handle codes smaller than 5 mm
- Use S21 Ultra’s ultrawide lens (best macro capability)

## Tech Stack
- Kotlin + Jetpack Compose
- CameraX + Camera2Interop (manual focus control)
- ML Kit Barcode Scanning
- OpenCV (planned) for contrast enhancement & preprocessing

## Current Status
- Project skeleton created
- Basic CameraX + ML Kit structure ready
- Focus optimization & preprocessing still to implement

## How to run
1. Open this repo in **Android Studio** (or GitHub Codespaces)
2. Sync Gradle
3. Run on a real Galaxy S21 Ultra (emulator is not enough for focus testing)

## Development Notes
- Primary camera: Ultrawide (lowest focal length)
- Continuous AF + manual focus distance fallback
- Auto digital zoom when QR is detected but too small
- Strong contrast preprocessing for gray/white codes

---
Created for S21 Ultra tiny QR scanning use case.