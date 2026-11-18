<h1 align="center">ViewerCraftStar</h1>

<p align="center">
Review the raw photos on your Android phone, rate and flag them, and send them home — to a Synology NAS and into
<b>LightCraft</b> — with all your choices.<br>
<a href="#-italiano">🇮🇹 Italiano</a> · <a href="https://github.com/poty87/ViewerCraftStar/releases/latest">⬇ Download</a>
</p>

> ViewerCraftStar is an independent app. It is not made by, affiliated with or endorsed by the authors of
> [LightCraft](https://github.com/storytold/lightcraft), Ricoh or Synology.

> [!IMPORTANT]
> **At the moment it works only with Ricoh GR DNG files** (tested with the GR IV), on Android 13 or later. It is the
> phone side of [PresetCraftAppLicator](https://github.com/poty87/PresetCraftAppLicator), which runs on the home PC.

## How it works

1. The raw files (DNG) arrive on the phone (for example from the camera's own app) in the app's **work folder**, which
   is *not* synced.
2. In **To review** you look at them full screen, swipe between them and set **flags** (pick / reject), **stars**,
   **colour labels**, title, caption, keywords, creator and copyright, and optionally a LightCraft **preset**, previewed
   on the phone.
3. **Send to the NAS** moves every photo to the folder that **Synology Drive** backs up to the NAS. **Rejected** photos
   are not sent: they go to the phone's bin (recoverable for 30 days).
4. As soon as each DNG is on the NAS, the app writes a small `.xmp` file next to it with your choices. On the home PC,
   PresetCraftAppLicator imports the photos into LightCraft with the right camera profile and applies ratings, flags,
   labels, metadata and preset.
5. The **Sent** section shows where each photo is:
   - 🔴 red — the photo or its metadata is not on the NAS yet (or was changed afterwards);
   - 🔵 blue — photo and metadata are safe on the NAS (they enter LightCraft when the home PC is on);
   - 🟢 green — already in LightCraft.

   Blue and green photos can be moved to the phone's bin with one tap.

After *Send to the NAS* the work goes on in the background, with a notification and a progress bar, even with the app
closed. It stops by itself when every photo is safe on the NAS (or, if you choose so in the settings, when they are all in
LightCraft).

## Away from home

The app talks to the NAS over SMB on your home network. Away from home it needs a VPN to your home network — for example
the free **VPN Server** package of Synology DSM with OpenVPN. The top of the screen always shows whether the NAS can be
reached (home network, via VPN, or not reachable) with a button to open OpenVPN Connect. If the NAS can't be reached when
you send the photos, the app asks whether to open the VPN or try later; nothing runs in the background meanwhile.

Synology Drive uploads the photos on its own, with its own settings (for example only on Wi-Fi), wherever you are.

## What you need

- An Android phone with Android 13 or later.
- A Synology NAS with **Synology Drive** backing up the phone's "sent" folder (Photo Backup), and an SMB share the app can
  write to.
- [PresetCraftAppLicator](https://github.com/poty87/PresetCraftAppLicator) with *automatic import* watching the same NAS
  folder, on the PC where your LightCraft library is.

## Install and set up

1. Download the `.apk` from the [latest release](https://github.com/poty87/ViewerCraftStar/releases/latest) and open it
   on the phone (Android asks to allow installing apps from your browser or file manager).
2. Open the app and allow access to photos (*Allow all*).
3. In **Settings**:
   - **Work folder** (default `Pictures/ViewerCraftStar/`, created if missing) and **folder synced with the NAS** (the
     one Synology Drive backs up);
   - **NAS**: IP address, shared folder, folder inside the share, user and password — then *Test connection*;
   - default creator and copyright, and the sending notifications.
4. Point your camera app (or file manager rule) to put new DNG files in the work folder.

## Privacy

- The NAS password is stored on the phone encrypted with a key from the Android Keystore; it never leaves the phone
  except to log in to your NAS.
- On the NAS the app only writes `.xmp` files (and preset files you choose to import) in your photo folder: it never
  changes or deletes photos.
- No accounts, no analytics, no internet services: the app only talks to your NAS.

## Build from source

Android SDK (API 35) and JDK 17:

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/
./gradlew assembleRelease      # signed only if keystore.properties exists
```

To sign a release, create a keystore and a `keystore.properties` file in the project folder (both are ignored by git):

```
storeFile=viewercraftstar.keystore
storePassword=…
keyAlias=viewercraftstar
keyPassword=…
```

## Licence

MIT — see [LICENSE](LICENSE). Uses [smbj](https://github.com/hierynomus/smbj) (Apache 2.0) and Jetpack Compose (Apache 2.0).

---

## 🇮🇹 Italiano

ViewerCraftStar serve a rivedere sul telefono Android i raw appena scattati, dare stelle e bandierine e mandarli a casa —
sul NAS Synology e dentro **LightCraft** — con tutte le tue scelte. È la parte "telefono" di
[PresetCraftAppLicator](https://github.com/poty87/PresetCraftAppLicator), che gira sul PC di casa.

> [!IMPORTANT]
> **Per ora funziona solo con i DNG delle Ricoh GR** (provata con la GR IV), su Android 13 o successivo.

**Come funziona**

1. I DNG arrivano sul telefono nella **cartella di lavoro** dell'app, che *non* è sincronizzata.
2. In **Da rivedere** le guardi a tutto schermo, scorri tra una e l'altra e imposti **bandierine** (scelta / scartata),
   **stelle**, **etichette colore**, titolo, didascalia, parole chiave, autore, copyright e, se vuoi, un **preset** di
   LightCraft, con l'anteprima sul telefono.
3. **Spedisci al NAS** sposta tutte le foto nella cartella che **Synology Drive** carica sul NAS. Le **scartate** non
   partono: vanno nel cestino del telefono (recuperabili per 30 giorni).
4. Appena ogni DNG è sul NAS l'app gli scrive accanto un piccolo `.xmp` con le tue scelte; sul PC di casa
   PresetCraftAppLicator importa le foto in LightCraft con il profilo giusto e applica stelle, bandierine, etichette,
   metadati e preset.
5. La sezione **Spedite** mostra dove si trova ogni foto: 🔴 rossa = foto o metadati non ancora sul NAS (o cambiati dopo),
   🔵 blu = foto e metadati al sicuro sul NAS (entrano in LightCraft quando il PC di casa è acceso), 🟢 verde = già in
   LightCraft. Blu e verdi si tolgono dal telefono con un tocco.

Dopo *Spedisci al NAS* il lavoro continua in background, con una notifica e una barra di avanzamento, anche con l'app
chiusa, e si ferma da solo quando tutte le foto sono al sicuro sul NAS (o, se lo scegli nelle impostazioni, quando sono
tutte in LightCraft).

**Fuori casa** serve una VPN verso la rete di casa, per esempio il pacchetto gratuito **VPN Server** di Synology con
OpenVPN. In alto l'app mostra sempre se il NAS è raggiungibile (rete di casa, via VPN, non raggiungibile) con un bottone
per aprire OpenVPN Connect. Synology Drive carica le foto per conto suo, con le sue impostazioni, ovunque tu sia.

**Cosa serve**: un telefono Android 13 o successivo; un NAS Synology con **Synology Drive** che fa il backup della cartella
"spedite" del telefono e una cartella condivisa SMB in cui l'app può scrivere; PresetCraftAppLicator con l'*importazione
automatica* sulla stessa cartella del NAS, sul PC con la libreria LightCraft.

**Installazione e impostazioni**

1. Scarica l'`.apk` dall'[ultima release](https://github.com/poty87/ViewerCraftStar/releases/latest) e aprilo sul telefono
   (Android chiede di consentire l'installazione di app dal browser o dal gestore file).
2. Apri l'app e consenti l'accesso alle foto (*Consenti tutto*).
3. Nelle **Impostazioni**: cartella di lavoro (predefinita `Pictures/ViewerCraftStar/`, creata se manca) e cartella
   sincronizzata con il NAS; dati del NAS (IP, cartella condivisa, cartella interna, utente e password) e *Prova
   connessione*; autore e copyright predefiniti; notifiche della spedizione.
4. Fai arrivare i DNG nuovi nella cartella di lavoro (app della fotocamera o regola di un gestore file).

**Privacy**: la password del NAS resta sul telefono, cifrata con una chiave del Keystore di Android; sul NAS l'app scrive
solo file `.xmp` (e i preset che scegli di importare) e non modifica né cancella mai le foto; niente account, statistiche o
servizi su internet: l'app parla solo con il tuo NAS.

**Licenza**: MIT — vedi [LICENSE](LICENSE).
