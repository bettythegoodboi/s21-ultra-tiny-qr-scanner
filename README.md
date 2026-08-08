# S21 Ultra Tiny QR Scanner

**Optimized Android app** for Samsung Galaxy S21 Ultra to scan very small QR codes (< 5mm), including low-contrast black / gray / white codes.

## Current Status
Basic working scanner with CameraX + ML Kit.

- Live camera preview
- QR code detection
- Permission handling

**Still TODO (next updates):**
- Force Ultrawide camera (best for macro)
- Advanced close-range focus control
- Digital zoom for tiny codes
- Image preprocessing for low contrast

## Download APK

Go to the **Actions** tab → latest successful workflow run → Artifacts → download `TinyQR-Scanner-Debug`.

Or wait for me to confirm when a successful build is ready.

## How to open the project

1. Open in Android Studio, or
2. Use GitHub Codespaces

## Tech
- Kotlin + Jetpack Compose
- CameraX
- ML Kit Barcode Scanning
