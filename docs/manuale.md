# Manuale di ViewerCraftStar

<a href="manual.md">🇬🇧 English</a>

Questo manuale descrive tutte le schermate e le opzioni dell'app. Per installare la suite da zero (NAS, PC, telefono, VPN)
e per il flusso di lavoro parti dalla **[guida della suite](https://github.com/poty87/PresetCraftAppLicator/blob/main/docs/guida-suite.md)**;
per l'accesso da fuori casa c'è la **[guida VPN](remote-access.md#-italiano)**.

## Indice

1. [La barra in basso](#1-la-barra-in-basso)
2. [Librerie: la galleria](#2-librerie-la-galleria)
3. [Cercare e filtrare](#3-cercare-e-filtrare)
4. [Modificare più foto insieme](#4-modificare-più-foto-insieme)
5. [Il visualizzatore](#5-il-visualizzatore)
6. [L'editor: Sviluppa](#6-leditor-sviluppa)
7. [Importa: da rivedere e inviate](#7-importa-da-rivedere-e-inviate)
8. [Fotocamera Ricoh GR e disco esterno](#8-fotocamera-ricoh-gr-e-disco-esterno)
9. [Notifiche e VPN](#9-notifiche-e-vpn)
10. [Impostazioni](#10-impostazioni)
11. [Privacy](#11-privacy)

---

## 1. La barra in basso

| Scheda | Cosa contiene |
|---|---|
| **Librerie** | Le tue librerie LightCraft di casa: galleria, ricerca, visualizzatore, editor. |
| **Importa** | Le foto nuove del telefono: *Da rivedere* e *Inviate*. |
| **Notifiche** | Gli avvisi dell'app (spedizioni, modifiche applicate, errori), con il numero di quelli nuovi. |
| **VPN** | Lo scudo: accende e spegne la VPN; il pallino dice lo stato (rosso spenta, giallo in collegamento, verde accesa). |
| **Impostazioni** | Tutte le opzioni ([capitolo 10](#10-impostazioni)). |

In cima a Librerie e Importa una riga dice sempre se il NAS è raggiungibile: *rete di casa*, *via VPN*, *un'altra VPN in
mezzo*, *non raggiungibile*.

---

## 2. Librerie: la galleria

- **Il titolo** è la libreria aperta: un tocco per cambiarla. Sotto: numero di foto e pile e quando è stata aggiornata
  (*aggiornamento…* mentre si aggiorna).
- **Aggiornare:** tira giù la galleria dall'alto (compare il tondo blu). Si aggiorna anche da sola all'apertura, a ogni
  ritorno nell'app e ogni minuto.
- **Le foto** sono divise per periodo, con il titolo del periodo che resta in alto mentre scorri. Sulle miniature:
  bandierina (✓ scelta, ✕ scartata, le scartate sono scurite), etichetta colore, stelle, tipo di file (DNG, JPEG…),
  matita = ritoccata in LightCraft, due fogli = copia virtuale, pallino arancione = modifica del telefono non ancora
  applicata.
- **Le pile** mostrano la copertina con il numero di foto: un tocco sul numero le apre o le chiude.
- **⋮ (Opzioni di visualizzazione):** *Cestino* (le foto nel cestino di LightCraft, con il pallino rosso se ce ne sono),
  *Griglia con celle quadrate* o *Griglia giustificata*, *Dimensione delle foto* (1–5), *Dividi per periodo* (per anno,
  mese o giorno).
- **Cestino:** le foto che hai messo nel cestino di LightCraft; selezionale e premi *Ripristina* per riportarle in
  libreria. I file si cancellano dal disco solo con CleanCraftNas, dal PC.

L'indice resta sul telefono: la libreria si sfoglia (e si modifica: le modifiche aspettano) anche senza NAS.

---

## 3. Cercare e filtrare

- **Cerca con l'IA…:** scrivi cosa c'è nella foto — *gatto arancione sdraiato*, *tramonto al mare*, *auto d'epoca* — e
  trovi le foto dalla più somigliante. Parte con *Cerca* sulla tastiera o dopo una breve pausa (servono almeno 3 lettere).
  *Mostra anche le meno somiglianti* ne aggiunge altre. Funziona con le librerie analizzate dal Motore IA di
  PresetCraftAppLicator; la prima volta l'app scarica dal NAS il modello (circa 830 MB, meglio in Wi-Fi), poi cerca anche
  senza NAS. Un nome di persona nella frase (*Marta al mare*) mostra solo le foto di quella persona.
- **Filtri:** un pannello con tutto insieme — *Parole chiave, persone, titolo o nome del file*, *Stelle*, *Data di scatto*,
  *Etichetta*, *Fotocamera*, *Bandierina*, *Tipo di file* (RAW, JPEG, TIFF/PSD) — applicato con *Applica*. I filtri attivi
  compaiono come etichette sotto la ricerca, ognuna con la ✕; *Azzera* li toglie tutti.

---

## 4. Modificare più foto insieme

**Pressione lunga** su una foto per selezionarla; trascinando selezioni anche le altre. La barra in basso offre:

| Pulsante | Azioni |
|---|---|
| **Modifica** | Bandierina, stelle, etichetta per tutte le selezionate; *Titolo e parole chiave…* per i testi. |
| **Sviluppo** | *Incolla lo sviluppo di …* (copiato prima), *Copia sviluppo…* da una foto, *Crea copia virtuale…*, *Togli le modifiche del telefono* (quelle non ancora applicate). |
| **Pila** | *Crea una pila* (la prima selezionata è la copertina), *Unisci le pile*, *Rendi copertina*, *Togli dalla pila*. |
| **Cestino** | Sposta nel cestino di LightCraft (i file restano sul NAS). |
| **Scarica** | Sul telefono, in `Pictures/ViewerCraftStar Download`: l'*Originale* (il file com'è sul NAS) o il *JPEG* della fotocamera. In background, con una notifica. |

Le modifiche si vedono subito con un pallino arancione e vengono lasciate sul NAS: PresetCraftAppLicator le applica a
LightCraft (con LightCraft chiuso, dopo un backup) e il pallino sparisce quando l'indice le conferma.

---

## 5. Il visualizzatore

Un tocco su una foto la apre a tutto schermo: scorri a destra e sinistra, due dita per ingrandire, un tocco per
nascondere i comandi.

- **In alto:** *Indietro*, posizione e data; *Modificata* se ha uno sviluppo (toccalo per vedere l'originale); **Volti**
  (i nomi delle persone; quelli dati da soli da PresetCraftAppLicator hanno il bordo tratteggiato: un tocco per
  confermarli o cambiarli), **Nel cestino di LightCraft**, **Scarica sul telefono**, **Sviluppa** (l'editor),
  **Informazioni** (dati di scatto, file, descrizione scritta dall'IA, parole chiave).
- **In basso:** *Bandierina* (Scelta / Scartata), *Stelle*, *Etichetta* e **Testi** (titolo, didascalia, parole chiave,
  autore, copyright). Con *Passa alla foto successiva dopo una bandierina o una stella* (Impostazioni) si va avanti da soli.

---

## 6. L'editor: Sviluppa

L'editor sviluppa la foto sul telefono con lo stesso motore di LightCraft, partendo da una *smart preview* preparata dal
PC (si scarica la prima volta e resta sul telefono). Le modifiche tornano a LightCraft identiche.

- **In alto:** *Chiudi* (chiede se scartare le modifiche), *Prima* (tieni premuto per confrontare con l'originale),
  **☰** (*Copia sviluppo*, *Incolla*, *Ripristina com'era*, *Ripristina come scattata*, preset e profili della libreria),
  **Fatto** (salva e invia a LightCraft).
- **Schede in basso:**
  - **Luce** — *Base* (esposizione, contrasto, alte luci, ombre, bianchi, neri) e *Curva*;
  - **Colore** — *Bilanciamento del bianco*, *Colore* (vividezza, saturazione), *HSL*, *Grading*;
  - **Effetti** — texture, chiarezza, foschia, vignettatura, grana;
  - **Dettaglio** — nitidezza, riduzione del rumore, correzioni dell'obiettivo;
  - **Maschere** — vedi sotto;
  - **Ritaglio** — proporzioni, raddrizzamento, rotazione.
- **Profili:** il profilo della foto (anche i profili DCP della fotocamera: per quelli l'app scarica il file originale;
  l'anteprima è approssimata, il risultato in LightCraft è esatto).

**Maschere.** *+ Nuova* crea una maschera: **Lineare** (una sfumatura), **Radiale**, **Pennello**, **Luminanza** e
**Colore** (per intervallo), **Oggetto (IA)** — un tocco sul soggetto e l'IA del telefono lo scontorna (tocca ancora per
aggiungere le parti mancanti; *Annulla tocco*, *Bordo* per ammorbidirlo) — e **Soggetto**, **Cielo**, **Sfondo** (trovati
dal motore di LightCraft, uguali sul PC). *+ Forma* aggiunge o toglie una forma alla stessa maschera. Dal menu della maschera:
*Rinomina*, *Duplica*, *Inverti*, *Mostra/Nascondi*, *Elimina*; l'occhio mostra o nasconde la maschera colorata. Ogni
maschera ha le sue correzioni (luce, colore, effetti, dettaglio, sfocatura). Le maschere fatte in LightCraft con forme che
il telefono non sa disegnare si vedono e se ne regolano le correzioni; la forma si cambia sul PC.

---

## 7. Importa: da rivedere e inviate

Questa parte serve se le foto nuove passano dal telefono; se arrivano sul NAS dal PC puoi ignorarla.

**Da rivedere** — le foto nella cartella di lavoro del telefono (non sincronizzata).

- *Tutte N* e **Filtri** in alto (bandierina, stelle, etichetta).
- Un tocco apre la foto: bandierina, stelle, etichetta, **Testi** e **Preset** (un preset di LightCraft, con l'anteprima
  sul telefono; *Importa preset da file…* carica preset di Lightroom `.xmp`, `.lrtemplate`, `.lcpreset`, look di Luminar
  o `.zip`).
- **Tondo in basso a sinistra:** importa nuove foto dalla **Fotocamera** o da un **Disco esterno** ([capitolo 8](#8-fotocamera-ricoh-gr-e-disco-esterno)).
- **Tondo blu in basso a destra — Spedisci al NAS**, con il numero di foto: le foto passano nella cartella sincronizzata
  (l'app di sincronizzazione del NAS le carica) e l'app scrive accanto a ognuna sul NAS un `.xmp` con le tue scelte. Le
  **scartate** non partono: vanno nel cestino del telefono (30 giorni). Il lavoro continua in background con una
  notifica, anche ad app chiusa.

**Inviate** — le foto spedite, con lo stato:

| Casetta | Significato |
|---|---|
| 🔴 rossa | La foto o i suoi metadati non sono ancora sul NAS (o li hai cambiati dopo). |
| 🔵 blu | Foto e metadati al sicuro sul NAS: entrano in LightCraft quando il PC di casa è acceso. |
| 🟢 verde | Già in LightCraft. |

In alto il riepilogo (*N in LightCraft · N sul NAS · N da inviare*) e se il PC di casa è acceso (ultimo segnale). Il
**cestino rosso** toglie dal telefono le foto blu e verdi; il **tondo verde** riprende l'invio in background.

---

## 8. Fotocamera Ricoh GR e disco esterno

**Fotocamera (Ricoh GR, provata con la GR IV).** Il telefono sveglia la fotocamera via Bluetooth, ne accende il Wi-Fi
(circa 30 secondi), ci si collega e mostra le foto con le miniature.

- *Formato*: Solo DNG, Solo JPEG, DNG+JPEG; *Ordina*: tutte insieme, per giorno, mese o anno; la casella del gruppo
  seleziona tutto il giorno. *IN / SD* sceglie la memoria se ce ne sono due.
- Le foto già sul telefono o sul NAS sono scure e non si riscaricano. *Scarica N*: i raw vanno in *Da rivedere*, i JPEG
  nella galleria del telefono (`DCIM/Camera`), con il nome «ora di scatto_nome» come l'app del produttore.
- *Spegni e chiudi* spegne la fotocamera. Associazione di una fotocamera nuova: sulla fotocamera *Wireless Communication ›
  Pairing › Execute Pairing*, poi OK due volte mentre si vede il QR; nell'app scegli la fotocamera e *Associa*.

**Disco esterno** (chiavetta, disco USB, lettore di schede collegato al telefono). La prima volta si apre il selettore di
Android già sul disco: *Usa questa cartella* e *Consenti*; dopo quel disco si apre da solo. *Cambia* sceglie un'altra
cartella. La pagina è come quella della fotocamera (*Formato*: Solo RAW / Solo JPEG / RAW+JPEG, *Ordina*, foto già
presenti scure) e vale per i raw di qualsiasi marca che LightCraft apre. I formati che Android non accetta come foto (su
Android 17 i Canon CR3) sono indicati a parte: si importano dal PC. Android legge i dischi FAT32 ed exFAT, non NTFS.

---

## 9. Notifiche e VPN

- **Notifiche:** l'elenco degli avvisi, dal più recente. Le spedizioni e gli scarichi in corso hanno anche una notifica
  di Android con la barra di avanzamento.
- **VPN:** la scheda VPN apre le impostazioni della VPN (profilo OpenVPN, server e porta esterni, utente e password, come
  si accende: *manuale*, *all'avvio dell'app* fuori casa, *completamente automatica*). Lo scudo nella barra la accende e la
  spegne; mentre è accesa una notifica di Android offre *Spegni*. Il motore è l'app gratuita *OpenVPN for Android*
  (installata dal Play Store, non serve aprirla). Android tiene una VPN alla volta: se un'altra è accesa (per esempio
  AdGuard) lo scudo lo spiega e apre quell'app. Tutti i passaggi nella [guida VPN](remote-access.md#-italiano).

---

## 10. Impostazioni

| Sezione | Opzioni |
|---|---|
| **Lingua** | Come il telefono, Italiano, English (l'app si riavvia). |
| **Permessi** | Foto (con la posizione di scatto), Notifiche, Dispositivi nelle vicinanze (fotocamera), Gestione dei media (spedire e spostare nel cestino senza conferma ogni volta); *Concedi* quelli che mancano. |
| **Cartelle dei raw sul telefono** | *Cartella delle foto nuove* (non sincronizzata, predefinita `Pictures/VCS_Import_Photo`) e *Cartella sincronizzata con il NAS* (predefinita `Pictures/VCS_Dng_Sync`), nella memoria interna dentro Pictures o DCIM; se mancano l'app le crea. |
| **NAS** | Indirizzo IP, utente, password, *Prova connessione*; poi *Cartella delle foto sul NAS* e *Cerca i raw anche nelle sottocartelle*. |
| **Cartella di servizio** | La cartella sul NAS dove PresetCraftAppLicator tiene librerie, anteprime e impostazioni per il telefono (la stessa scelta in PresetCraftAppLicator). |
| **Velocità del NAS** | Prova diversi modi di leggere una foto grande dal NAS: da usare a casa e in VPN. |
| **Sviluppo sul telefono** | Versione del motore, spazio di smart preview e originali scaricati per l'editor, *Svuota*. |
| **Backup delle impostazioni** | *Salva backup* (uno zip nella cartella di servizio con impostazioni, metadati delle foto e modifiche in attesa, mai la password), *Ripristina…*. |
| **Ricerca per soggetti (IA)** | Spazio del modello scaricato, *Elimina*. |
| **Notifiche della spedizione** | Avvisare quando la spedizione è finita o sospesa. |
| **Valori predefiniti** | Autore e copyright per le foto nuove; passare alla foto successiva dopo una bandierina o una stella. |

In fondo la versione e *☕ Offrimi un caffè*.

---

## 11. Privacy

- La password del NAS resta sul telefono, cifrata con una chiave del Keystore di Android; esce solo per entrare nel tuo NAS.
- Sul NAS l'app scrive solo file `.xmp`, le modifiche alle librerie (nella cartella di servizio) e i preset che scegli di
  importare: non cambia e non cancella mai le foto.
- Niente account, statistiche o servizi su internet: l'app parla solo con il tuo NAS (e con la fotocamera).
