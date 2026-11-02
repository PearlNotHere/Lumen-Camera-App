# Lumen Camera (Android)

A native Android camera built on the Camera2 API. It talks to the sensor directly, so it can do what the browser version could not:

- **RAW (DNG) capture:** the sensor's untouched data, before the phone's image processor. Edit in Lightroom Mobile or Snapseed.
- **Processing switch (PROC):** `PROC OFF` turns noise reduction and sharpening off in the phone's image processor (ISP), `PROC LITE` uses minimal amounts, `PROC HQ` is the phone's usual look.
- **Pro mode:** manual ISO, shutter speed (up to 30s where the sensor allows), manual focus, white-balance presets, EV and zoom, each with AUTO.
- **iPhone-style handling:** tap to focus, drag up or down to change exposure, long-press for AE/AF Lock, pinch to zoom, swipe to change modes.
- **Modes:** Time-lapse, Video (4K / HD / 720p at 24, 30 or 60 fps), Photo, Pro.
- **Tools:** grid, level, live RGB histogram, zebra stripes, focus peaking, 3s or 10s timer, flash (auto / on / torch), lens picker, unprocessed microphone audio.
- Everything saves to `DCIM/Lumen` in your gallery.

## Get the APK (no Android Studio needed)

1. Create a free account at github.com, then create a new **public** repository called `lumen-camera`.
2. On the repository page, click **uploading an existing file** and drag in *everything inside this folder*, including the hidden `.github` folder. (On a laptop, unzip first, then drag the folder contents. If `.github` doesn't show up, enable hidden files in your file manager.)
3. Click **Commit changes**. GitHub starts building automatically.
4. Open the **Actions** tab and wait for the green tick (about 4 to 6 minutes).
5. Open **Releases** on the right side of the repository page, then download **Lumen-Camera.apk** on your phone.
6. Open the APK. Android will ask you to allow installs from your browser or Files app. Allow it, then install.

The build is signed with a temporary key. If you rebuild later, uninstall the old Lumen first, then install the new APK.

## Or build it on your laptop

Open this folder in Android Studio (Hedgehog or newer), let Gradle sync, plug in your phone with USB debugging on, and press Run.

## What to expect

- Check **••• → Camera info** for exactly what each lens exposes: RAW support, ISO and shutter ranges, focus range, max resolution.
- Some lenses (often the ultrawide or front camera) don't offer RAW or manual controls to third-party apps. The app greys those controls out instead of faking them.
- RAW photos look flatter and noisier straight out of camera. That's the point: you get all the data, and you choose the processing.
- `PROC OFF` affects JPEG and video. The phone's tone mapping and colour still apply; only RAW skips the processor entirely.
- Slo-mo isn't included. High-frame-rate capture needs a special camera session that most phones only open to their own camera app.
