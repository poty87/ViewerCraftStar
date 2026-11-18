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

## Part of a suite

This app is one of three independent tools that, together, build a **workflow for [LightCraft](https://github.com/storytold/lightcraft)
with your photos on a NAS**: shoot, review on the phone, send home, import with the right colours, and keep the NAS clean.
Each one works on its own; together they cover the whole path.

| App | Runs on | What it does |
|---|---|---|
| [**PresetCraftAppLicator**](https://github.com/poty87/PresetCraftAppLicator) | Windows PC | Gives LightCraft photos the camera profile of the style they were shot with. Watches a NAS folder and imports new photos automatically; applies the ratings, flags, metadata and presets chosen on the phone and tells the phone which photos are safe at home. |
| [**ViewerCraftStar**](https://github.com/poty87/ViewerCraftStar) | Android | Review the raw files on the phone (flags, stars, labels, metadata, LightCraft presets with preview), send them to the NAS through Synology Drive, follow them until they are safe on the NAS and in LightCraft, then free the phone. |
| [**CleanCraftNas**](https://github.com/poty87/CleanCraftNas) | Windows PC | Deletes from disk — even on the NAS — the photos you put in LightCraft's trash, so the NAS stays in step with the library. |

**The workflow**

1. Shoot, and let the raw files reach the phone (for example with the camera's own app).
2. **ViewerCraftStar**: review them, then *Send to the NAS* — Synology Drive uploads the photos and the app writes your
   choices next to them (`.xmp`).
3. **PresetCraftAppLicator** (home PC): imports them into LightCraft with the right camera profile and applies your choices.
4. Edit in LightCraft; the photos you don't want go to LightCraft's trash.
5. **CleanCraftNas**: deletes those photos from the NAS too.

**Away from home** the phone reaches the NAS through a VPN to your home network: see the
[remote access guide](https://github.com/poty87/ViewerCraftStar/blob/main/docs/remote-access.md) (Synology *VPN Server* with OpenVPN on the NAS, *OpenVPN Connect* on the phone, port
forwarding through one or two routers).

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

## Library: your home LightCraft library on the phone

The **Library** tab shows your LightCraft libraries — all of them, not only the photos sent from the phone — from the
index that [PresetCraftAppLicator](https://github.com/poty87/PresetCraftAppLicator) (1.14.0 or later) writes in a service
folder on the NAS (*Automatic import › Libraries to browse on the phone*; set the same folder in the app under
*Settings › Home library*). Photos and catalogs stay where they are.

- **Browse** by capture date, with LightCraft's **stacks** (cover and number of photos; tap the number to open or close).
- **Filter** by stars (exactly 5, at least N, none), flag, colour labels, camera, capture dates and text (name, title,
  keywords).
- **View** full screen with swipe and photo details. Previews are the JPEGs the camera embedded in the files (DNG, CR2,
  ORF, JPG; a small thumbnail for TIFFs saved by Photoshop), read from the NAS a piece at a time: they show the photo as
  shot, not with LightCraft's edits. Thumbnails stay on the phone.
- **Edit** stars, flags, colour labels, title, caption and keywords, one photo at a time or many at once (long press to
  select). Changes show at once with an orange dot and are left on the NAS; PresetCraftAppLicator applies them to
  LightCraft (with LightCraft closed, after a backup) and the dot disappears when the new index confirms them.
- **Download** selected photos to `Pictures/ViewerCraftStar Download`: the original file or the camera's JPEG, in the
  background with a notification.

The index is kept on the phone, so the library can be browsed (and edited — changes wait) even when the NAS can't be
reached.

## Away from home

The app talks to the NAS over SMB on your home network. Away from home it needs a VPN to your home network — for example
the free **VPN Server** package of Synology DSM with OpenVPN. The top of the screen always shows whether the NAS can be
reached (home network, via VPN, or not reachable) with a button to open OpenVPN Connect. If the NAS can't be reached when
you send the photos, the app asks whether to open the VPN or try later; nothing runs in the background meanwhile.

Synology Drive uploads the photos on its own, with its own settings (for example only on Wi-Fi), wherever you are.

Step-by-step setup — DDNS, port forwarding through one or two routers, VPN Server, the `.ovpn` profile (including the fix
for file writes timing out on mobile data) and the phone: **[docs/remote-access.md](docs/remote-access.md)**.

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

### Fa parte di una suite

Questa app è uno di tre programmi indipendenti che, insieme, creano un **flusso di lavoro per
[LightCraft](https://github.com/storytold/lightcraft) con le foto su un NAS**: scatti, rivedi sul telefono, spedisci a casa,
importi con i colori giusti e tieni pulito il NAS. Ognuno funziona anche da solo; insieme coprono tutto il percorso.

| App | Dove gira | A cosa serve |
|---|---|---|
| [**PresetCraftAppLicator**](https://github.com/poty87/PresetCraftAppLicator) | PC Windows | Dà alle foto di LightCraft il profilo della fotocamera dello stile con cui le hai scattate. Osserva una cartella del NAS e importa da solo le foto nuove; applica stelle, bandierine, metadati e preset scelti sul telefono e dice al telefono quali foto sono al sicuro a casa. |
| [**ViewerCraftStar**](https://github.com/poty87/ViewerCraftStar) | Android | Rivedi i raw sul telefono (bandierine, stelle, etichette, metadati, preset di LightCraft con anteprima), spediscili sul NAS tramite Synology Drive, seguili finché sono al sicuro sul NAS e in LightCraft, poi libera il telefono. |
| [**CleanCraftNas**](https://github.com/poty87/CleanCraftNas) | PC Windows | Cancella dal disco — anche dal NAS — le foto che hai messo nel cestino di LightCraft, così il NAS resta allineato alla libreria. |

**Il flusso di lavoro**

1. Scatti e fai arrivare i raw sul telefono (per esempio con l'app della fotocamera).
2. **ViewerCraftStar**: li rivedi e premi *Spedisci al NAS* — Synology Drive carica le foto e l'app scrive accanto a ognuna
   le tue scelte (`.xmp`).
3. **PresetCraftAppLicator** (PC di casa): le importa in LightCraft con il profilo giusto e applica le tue scelte.
4. Lavori in LightCraft; le foto che non vuoi finiscono nel cestino di LightCraft.
5. **CleanCraftNas**: cancella quelle foto anche dal NAS.

**Fuori casa** il telefono raggiunge il NAS con una VPN verso la rete di casa: vedi la
[guida all'accesso da fuori casa](https://github.com/poty87/ViewerCraftStar/blob/main/docs/remote-access.md#-italiano) (*VPN Server* di Synology con OpenVPN sul NAS, *OpenVPN Connect* sul
telefono, inoltro della porta attraverso uno o due router).

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

**Libreria: la libreria LightCraft di casa sul telefono.** La scheda **Libreria** mostra le tue librerie LightCraft — tutte,
non solo le foto spedite dal telefono — dall'indice che PresetCraftAppLicator (1.14.0 o successivo) scrive in una cartella
di servizio sul NAS (*Importazione automatica › Librerie da consultare sul telefono*; nell'app la stessa cartella in
*Impostazioni › Libreria di casa*). Foto e cataloghi restano dove sono.
- **Sfoglia** per data di scatto, con le **pile** di LightCraft (copertina e numero di foto; tocca il numero per aprirle o
  chiuderle).
- **Filtra** per stelle (esattamente 5, almeno N, nessuna), bandierina, etichette colore, fotocamera, date di scatto e testo.
- **Guarda** a tutto schermo con lo scorrimento e i dettagli della foto. Le anteprime sono i JPEG che la fotocamera ha
  messo nei file (DNG, CR2, ORF, JPG; per i TIF salvati da Photoshop una miniatura), letti dal NAS a pezzi: mostrano la foto
  come scattata, non con le modifiche di LightCraft. Le miniature restano sul telefono.
- **Modifica** stelle, bandierine, etichette, titolo, didascalia e parole chiave, di una foto o di molte insieme (pressione
  lunga per selezionare). Le modifiche si vedono subito con un pallino arancione e vengono lasciate sul NAS;
  PresetCraftAppLicator le applica a LightCraft (con LightCraft chiuso, dopo un backup) e il pallino sparisce quando il
  nuovo indice le conferma.
- **Scarica** le foto selezionate in `Pictures/ViewerCraftStar Download`: l'originale o il JPEG della fotocamera, in
  background con una notifica.

L'indice resta sul telefono: la libreria si sfoglia (e si modifica — le modifiche aspettano) anche senza NAS.

**Fuori casa** serve una VPN verso la rete di casa, per esempio il pacchetto gratuito **VPN Server** di Synology con
OpenVPN. In alto l'app mostra sempre se il NAS è raggiungibile (rete di casa, via VPN, non raggiungibile) con un bottone
per aprire OpenVPN Connect. Synology Drive carica le foto per conto suo, con le sue impostazioni, ovunque tu sia.
La configurazione passo per passo — DDNS, inoltro della porta attraverso uno o due router, VPN Server, il profilo `.ovpn`
(compresa la correzione per le scritture che vanno in timeout sui dati mobili) e il telefono — è nella
**[guida all'accesso da fuori casa](docs/remote-access.md#-italiano)**.

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
