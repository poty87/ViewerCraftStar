# ViewerCraftStar manual

<a href="manuale.md">🇮🇹 Italiano</a>

This manual describes every screen and option of the app. To set up the suite from scratch (NAS, PC, phone, VPN) and
for the overall workflow, start from the **[suite guide](https://github.com/poty87/PresetCraftAppLicator/blob/main/docs/suite-guide.md)**;
for access from outside home see the **[VPN guide](remote-access.md)**.

## Contents

1. [The bottom bar](#1-the-bottom-bar)
2. [Libraries: the gallery](#2-libraries-the-gallery)
3. [Searching and filtering](#3-searching-and-filtering)
4. [Editing several photos at once](#4-editing-several-photos-at-once)
5. [The viewer](#5-the-viewer)
6. [The editor: Develop](#6-the-editor-develop)
7. [Import: to review and sent](#7-import-to-review-and-sent)
8. [Ricoh GR camera and external disk](#8-ricoh-gr-camera-and-external-disk)
9. [Notifications and VPN](#9-notifications-and-vpn)
10. [Settings](#10-settings)
11. [Privacy](#11-privacy)

---

## 1. The bottom bar

| Tab | What it holds |
|---|---|
| **Libraries** | Your LightCraft libraries at home: gallery, search, viewer, editor. |
| **Import** | The phone's new photos: *To review* and *Sent*. |
| **Notifications** | The app's messages (sending, changes applied, errors), with the number of new ones. |
| **VPN** | The shield: turns the VPN on and off; the dot shows its state (red off, yellow connecting, green on). |
| **Settings** | Every option ([chapter 10](#10-settings)). |

At the top of Libraries and Import a line always says whether the NAS can be reached: *home network*, *via VPN*,
*another VPN in the way*, *not reachable*.

---

## 2. Libraries: the gallery

- **The title** is the open library: tap it to switch. Below it: number of photos and stacks, and when it was last
  updated.
- **Refreshing:** pull the gallery down from the top (the blue circle appears). It also refreshes by itself when it
  opens, every time you come back to the app and every minute.
- **Photos** are grouped by period, with the period's title staying at the top while you scroll. On the thumbnails:
  flag (✓ pick, ✕ reject; rejects are darkened), colour label, stars, file type (DNG, JPEG…), pencil = edited in
  LightCraft, two sheets = virtual copy, orange dot = a phone change not applied yet.
- **Stacks** show the cover with the number of photos: tap the number to open or close them.
- **⋮ (View options):** *Trash* (the photos in LightCraft's trash, with a red dot if there are any), *Square grid* or
  *Justified grid*, *Photo size* (1–5), *Group by period* (by year, month or day).
- **Trash:** the photos you moved to LightCraft's trash; select them and press *Restore* to bring them back into the
  library. Files are deleted from disk only with CleanCraftNas, from the PC.

The index stays on the phone: you can browse the library (and edit it: the changes wait) even without the NAS.

---

## 3. Searching and filtering

- **Search with AI…:** write what is in the photo — *orange cat lying down*, *sunset by the sea*, *vintage car* — and
  get the photos from the most similar. It starts with *Search* on the keyboard or after a short pause (at least 3
  letters). *Also show less similar ones* adds more. It works with libraries analysed by PresetCraftAppLicator's AI
  engine; the first time, the app downloads the model from the NAS (about 830 MB, better on Wi-Fi), after that it
  searches even without the NAS. A person's name in the sentence (*Martha at the beach*) shows only that person's photos.
- **Filters:** one panel with everything — keywords, people, title or file name, *Stars*, *Capture date*, *Label*,
  *Camera*, *Flag*, *File type* (RAW, JPEG, TIFF/PSD) — applied with *Apply*. Active filters appear as chips under the
  search, each with its ✕; *Clear all* removes them all.

---

## 4. Editing several photos at once

**Long-press** a photo to select it; drag to select the others too. The bottom bar offers:

| Button | Actions |
|---|---|
| **Edit** | Flag, stars, label for all selected photos; *Title and keywords…* for the texts. |
| **Develop** | *Paste develop settings from …* (copied earlier), *Copy develop settings…* from a photo, *Create virtual copy…*, *Remove the phone's changes* (those not applied yet). |
| **Stack** | *Create a stack* (the first selected is the cover), *Merge stacks*, *Make cover*, *Remove from stack*. |
| **Trash** | Moves to LightCraft's trash (the files stay on the NAS). |
| **Download** | To the phone, in `Pictures/ViewerCraftStar Download`: the *Original* (the file as it is on the NAS) or the camera's *JPEG*. In the background, with a notification. |

Changes show at once with an orange dot and are left on the NAS: PresetCraftAppLicator applies them to LightCraft (with
LightCraft closed, after a backup) and the dot disappears when the index confirms them.

---

## 5. The viewer

Tap a photo to open it full screen: swipe left and right, pinch to zoom, tap to hide the controls.

- **At the top:** *Back*, position and date; *Edited* if it has develop settings (tap it to see the original); **Faces**
  (the names of the people; those guessed by PresetCraftAppLicator have a dashed border: tap to confirm or change them),
  **Move to LightCraft's trash**, **Download to the phone**, **Develop** (the editor), **Info** (shooting data, file,
  AI-written description, keywords).
- **At the bottom:** *Flag* (Pick / Reject), *Stars*, *Label* and **Texts** (title, caption, keywords, author,
  copyright). With *Go to the next photo after a flag or a star* (Settings) it moves on by itself.

---

## 6. The editor: Develop

The editor develops the photo on the phone with the same engine as LightCraft, starting from a *smart preview* prepared
by the PC (downloaded the first time and kept on the phone). The changes go back to LightCraft unchanged.

- **At the top:** *Close* (asks whether to discard the changes), *Before* (hold to compare with the original), **☰**
  (*Copy develop settings*, *Paste*, *Reset to how it was*, *Reset to as shot*, the library's presets and profiles),
  **Done** (saves and sends to LightCraft).
- **Bottom tabs:**
  - **Light** — *Basic* (exposure, contrast, highlights, shadows, whites, blacks) and *Curve*;
  - **Color** — *White balance*, colour (vibrance, saturation), *HSL*, *Grading*;
  - **Effects** — texture, clarity, dehaze, vignette, grain;
  - **Detail** — sharpening, noise reduction, lens corrections;
  - **Masks** — see below;
  - **Crop** — aspect ratio, straighten, rotate.
- **Profiles:** the photo's profile (camera DCP profiles too: for those the app downloads the original file; the preview
  is approximate, the result in LightCraft is exact).

**Masks.** *+ New* creates a mask: **Linear** (a gradient), **Radial**, **Brush**, **Luminance range** and **Color
range**, **Object (AI)** — tap the subject and the phone's AI cuts it out (tap again to add missing parts; *Undo tap*,
*Edge* to soften it) — and **Subject**, **Sky**, **Background** (found by the LightCraft engine, the same on the PC).
*+ Shape* adds or subtracts a shape on the same mask. From the mask menu: *Rename*, *Duplicate*, *Invert*,
*Show/Hide*, *Delete*; the eye shows or hides the coloured overlay. Each mask has its own adjustments (light, colour,
effects, detail, blur). Masks made in LightCraft with shapes the phone cannot draw are shown and their adjustments can
be changed; the shape itself is changed on the PC.

---

## 7. Import: to review and sent

This part matters if new photos go through the phone; if they reach the NAS from the PC you can ignore it.

**To review** — the photos in the phone's working folder (not synced).

- *All N* and **Filters** at the top (flag, stars, label).
- Tap to open a photo: flag, stars, label, **Texts** and **Preset** (a LightCraft preset, previewed on the phone;
  *Import presets from file…* loads Lightroom presets `.xmp`, `.lrtemplate`, `.lcpreset`, Luminar looks or a `.zip`).
- **Round button bottom left:** imports new photos from the **Camera** or an **External disk** ([chapter 8](#8-ricoh-gr-camera-and-external-disk)).
- **Blue round button bottom right — send to the NAS**, with the number of photos: the photos move to the synced folder
  (the NAS's sync app uploads them) and the app writes next to each one on the NAS an `.xmp` with your choices.
  **Rejects** are not sent: they go to the phone's trash (30 days). The work carries on in the background with a
  notification, even with the app closed.

**Sent** — the photos already sent, with their state:

| House | Meaning |
|---|---|
| 🔴 red | The photo or its metadata are not on the NAS yet (or you changed them afterwards). |
| 🔵 blue | Photo and metadata safe on the NAS: they enter LightCraft when the home PC is on. |
| 🟢 green | Already in LightCraft. |

At the top, the summary (*N in LightCraft · N on the NAS · N to send*) and whether the home PC is on (last signal). The
**red trash** removes the blue and green photos from the phone; the **green circle** resumes sending in the background.

---

## 8. Ricoh GR camera and external disk

**Camera (Ricoh GR, tested with the GR IV).** The phone wakes the camera over Bluetooth, turns on its Wi-Fi (about 30
seconds), connects and shows the photos with thumbnails.

- *Format*: DNG only, JPEG only, DNG+JPEG; *Sort*: all together, by day, month or year; the group's checkbox selects the
  whole day. *IN / SD* picks the memory when there are two.
- Photos already on the phone or the NAS are dimmed and are not downloaded again. *Download N*: raw files go to *To
  review*, JPEGs to the phone's gallery (`DCIM/Camera`), named «capture time_name» like the maker's app.
- *Turn off and close* turns the camera off. Pairing a new camera: on the camera *Wireless Communication › Pairing ›
  Execute Pairing*, then OK twice while the QR code shows; in the app pick the camera and *Pair*.

**External disk** (USB stick, USB drive, card reader plugged into the phone). The first time Android's picker opens on
the disk: *Use this folder* and *Allow*; afterwards that disk opens by itself. *Change* picks another folder. The page
works like the camera one (*Format*: RAW only / JPEG only / RAW+JPEG, *Sort*, photos already present dimmed) and accepts
raw files of any brand LightCraft opens. Formats Android does not accept as photos (Canon CR3 on Android 17) are listed
separately: import those from the PC. Android reads FAT32 and exFAT disks, not NTFS.

---

## 9. Notifications and VPN

- **Notifications:** the list of messages, newest first. Sending and downloads in progress also have an Android
  notification with a progress bar.
- **VPN:** the VPN tab opens the VPN settings (OpenVPN profile, external server and port, user and password, how it
  turns on: *manual*, *when the app starts* away from home, *fully automatic*). The shield in the bar turns it on and
  off; while it is on, an Android notification offers *Turn off*. The engine is the free *OpenVPN for Android* app
  (installed from the Play Store, no need to open it). Android keeps one VPN at a time: if another one is on (for
  example AdGuard) the shield explains it and opens that app. All the steps are in the [VPN guide](remote-access.md).

---

## 10. Settings

| Section | Options |
|---|---|
| **Language** | Same as the phone, Italiano, English (the app restarts). |
| **Permissions** | Photos (with the shooting location), Notifications, Nearby devices (camera), Media management (send and move to trash without confirming each time); *Grant the missing ones*. |
| **Raw folders on the phone** | *New photos folder* (not synced, default `Pictures/VCS_Import_Photo`) and *Folder synced with the NAS* (default `Pictures/VCS_Dng_Sync`), in internal storage inside Pictures or DCIM; the app creates them if missing. |
| **NAS** | IP address, user, password, *Test connection*; then *Photos folder on the NAS* and *Also look for raw files in subfolders*. |
| **Service folder** | The folder on the NAS where PresetCraftAppLicator keeps libraries, previews and settings for the phone (the same one chosen in PresetCraftAppLicator). |
| **NAS speed** | Tries different ways of reading a large photo from the NAS: use it at home and over the VPN. |
| **Developing on the phone** | Engine version, space used by smart previews and originals downloaded for the editor, *Clear*. |
| **Settings backup** | *Save backup* (a zip in the service folder with settings, photo metadata and pending changes, never the password), *Restore…*. |
| **Search by subject (AI)** | Space used by the downloaded model, *Delete*. |
| **Sending notifications** | Notify when sending has finished or paused. |
| **Defaults** | Author and copyright for new photos; go to the next photo after a flag or a star. |

At the bottom, the version and *☕ Buy me a coffee*.

---

## 11. Privacy

- The NAS password stays on the phone, encrypted with an Android Keystore key; it leaves only to log into your NAS.
- On the NAS the app writes only `.xmp` files, the library changes (in the service folder) and the presets you choose to
  import: it never changes or deletes photos.
- No accounts, analytics or internet services: the app talks only to your NAS (and to the camera).
