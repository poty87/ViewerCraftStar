# Reaching the NAS from outside home (VPN)

<a href="#-italiano">🇮🇹 Italiano</a>

At home, ViewerCraftStar talks to the NAS directly on your local network (SMB). Away from home the NAS is not reachable
from the internet — and it should not be: opening SMB to the internet is unsafe. Instead the phone joins your home network
through a **VPN**. This is the setup used while building the suite; it needs no paid service and no third-party account.

- **On the NAS**: Synology **VPN Server** package (free, in Package Center), protocol **OpenVPN**.
- **On the phone**: the VPN built into ViewerCraftStar, with the free **OpenVPN for Android** app (Google Play) as its
  engine, never opened.
- **Synology Drive** does not need the VPN: it reaches the NAS on its own. The VPN is only for ViewerCraftStar (sending the
  metadata, knowing which photos are safe, importing presets).

The examples below use placeholders — replace them with your own values:

| Placeholder | Meaning |
|---|---|
| `yourname.synology.me` | your DDNS name |
| `192.168.1.10` | the NAS's address on your home network |
| `192.168.0.2` | the WAN address of the second router, if you have two (see step 3) |
| `1195` | the external UDP port you open (any free port works) |

## 1. Check that your connection can receive connections

Open the ISP router's status page and compare its **WAN / internet IP** with the address shown by any "what is my IP"
website. If they match, you have a public IPv4 address and can continue. If they differ (often `100.64.x.x`), your
provider uses **CGNAT**: incoming connections can't reach you, and you need to ask the provider for a public IP (often free)
before going on.

## 2. A name for your home: DDNS

Your public address can change. In DSM open **Control Panel › External Access › DDNS › Add**, choose **Synology** as the
service provider and pick a host name, e.g. `yourname.synology.me`. DSM keeps it pointed at your current address. Check
that the *External address* shown matches your public IP.

## 3. Routers: a fixed address for the NAS and the port forwarding

1. **Reserve the NAS's address** on the router that gives addresses to your home network (DHCP / address reservation),
   e.g. `192.168.1.10`, so it never changes.
2. **One router** (the provider's box does everything): forward **UDP `1195`** → `192.168.1.10`, port **`1194`**.
3. **Two routers in a row** (for example the provider's box, and behind it a mesh system such as TP-Link Deco in *router*
   mode — "double NAT"), forward the port **twice**:
   - on the **first** (provider's) router, give the second router a fixed address (`192.168.0.2`, DHCP reservation) and
     forward **UDP `1195`** → `192.168.0.2`, port **`1195`**;
   - on the **second** router (on Deco: app › *More* › *Advanced* › *NAT Forwarding* › *Port Forwarding*) forward
     **UDP `1195`** → `192.168.1.10`, port **`1194`**.

   Putting the second router in *access point* / bridge mode avoids the double forwarding, if you prefer.

Notes from the real setup:

- some routers refuse external port `1194` because they use it for their own VPN server — any other free port works,
  that's why the examples use `1195`;
- some provider boxes accept only port numbers up to four digits;
- the NAS always listens on `1194`: only the external port changes.

## 4. VPN Server on the NAS

1. **Package Center** › install **VPN Server**.
2. **VPN Server › OpenVPN**: enable it, protocol **UDP**, port **1194**; leave **compression off**; tick **Allow clients to
   access server's LAN** (so the phone can reach the NAS's local address); **Apply**.
3. **VPN Server › Privilege**: allow OpenVPN **only** to the DSM user the phone will use.
4. **Control Panel › Security › Protection**: turn on **Auto block** (blocks addresses after repeated failed logins).
5. **Control Panel › Security › Firewall** (if enabled): allow **VPN Server (OpenVPN)**, port 1194. You don't need to open
   anything else.
6. **VPN Server › OpenVPN › Export configuration**: you get a zip with a `.ovpn` file.

## 5. The `.ovpn` profile

Open the exported `.ovpn` in a text editor:

1. Set the `remote` line to your DDNS name and the **external** port:

   ```
   remote yourname.synology.me 1195
   ```

2. Leave `redirect-gateway` commented out (`#redirect-gateway def1`): only the traffic for your home network goes through
   the VPN, everything else uses the phone's normal connection ("split tunnel").
3. Right after `proto udp` add these two lines:

   ```
   tun-mtu 1340
   mssfix 1300
   ```

   On mobile data, packets bigger than about 1330 bytes inside the tunnel can be dropped silently: reading from the NAS
   works, but **writing files times out**. These two lines keep the packets small enough.

With ViewerCraftStar you don't need to edit the `.ovpn`: the external server and port and the smaller packets are
settings of the app. Just copy the exported **zip** to the phone (USB, e-mail to yourself, a cloud folder…).

## 6. The phone

1. Install **OpenVPN for Android** from Google Play (ViewerCraftStar › Settings › VPN has the link). You never open it.
2. In **ViewerCraftStar › Settings › VPN**: *Import zip…*, then the external **server** (your DDNS name) and **port**
   (the one forwarded by the router), *Smaller packets* on, the **DSM user name and password**. The zip is also copied
   to the service folder and goes into the settings backup; user and password stay only on the phone.
3. Tap the **shield** in the bottom bar. The first time OpenVPN for Android asks to let ViewerCraftStar control it:
   allow. The dot turns green and the top line should say **"NAS reachable · via VPN"**.

## Everyday use

- **At home: VPN off.** The app reaches the NAS directly on the Wi-Fi.
- **Away: VPN on** with the shield (or automatically when the app opens, if you choose so in Settings › VPN). It stays
  on until you turn it off, from the shield or the notification "ViewerCraftStar VPN on".
- **Another VPN** (for example AdGuard) takes the only VPN slot Android has: the shield tells you and opens that app to
  turn it off.
- Synology Drive keeps uploading the photos with its own settings (for example only on Wi-Fi), VPN or not.

---

## 🇮🇹 Italiano

# Raggiungere il NAS da fuori casa (VPN)

A casa ViewerCraftStar parla con il NAS direttamente sulla rete locale (SMB). Fuori casa il NAS non è raggiungibile da
internet — ed è giusto così: aprire SMB su internet non è sicuro. Il telefono entra invece nella rete di casa con una
**VPN**. Questa è la configurazione usata mentre la suite veniva costruita: niente servizi a pagamento né account di terzi.

- **Sul NAS**: il pacchetto **VPN Server** di Synology (gratuito, nel Centro pacchetti), protocollo **OpenVPN**.
- **Sul telefono**: la VPN dentro ViewerCraftStar, con l'app gratuita **OpenVPN for Android** (Google Play) come
  motore, mai da aprire.
- **Synology Drive** non ha bisogno della VPN: raggiunge il NAS per conto suo. La VPN serve solo a ViewerCraftStar (invio dei
  metadati, sapere quali foto sono al sicuro, importare preset).

Gli esempi usano dei segnaposto da sostituire con i tuoi valori:

| Segnaposto | Significato |
|---|---|
| `tuonome.synology.me` | il tuo nome DDNS |
| `192.168.1.10` | l'indirizzo del NAS nella rete di casa |
| `192.168.0.2` | l'indirizzo WAN del secondo router, se ne hai due (passo 3) |
| `1195` | la porta UDP esterna che apri (va bene qualsiasi porta libera) |

## 1. Controlla che la tua linea possa ricevere connessioni

Apri la pagina di stato del router del provider e confronta il suo **IP WAN / internet** con quello mostrato da un sito
"qual è il mio IP". Se coincidono hai un IPv4 pubblico e puoi andare avanti. Se sono diversi (spesso `100.64.x.x`) il provider
usa il **CGNAT**: le connessioni in arrivo non ti raggiungono e devi chiedere un IP pubblico (spesso gratuito) prima di
proseguire.

## 2. Un nome per casa tua: DDNS

L'indirizzo pubblico può cambiare. In DSM apri **Pannello di controllo › Accesso esterno › DDNS › Aggiungi**, scegli
**Synology** come provider e un nome, per esempio `tuonome.synology.me`: DSM lo tiene puntato al tuo indirizzo attuale.
Controlla che l'*indirizzo esterno* mostrato coincida con il tuo IP pubblico.

## 3. Router: indirizzo fisso del NAS e inoltro della porta

1. **Riserva l'indirizzo del NAS** sul router che assegna gli indirizzi alla rete di casa (DHCP / prenotazione indirizzo),
   per esempio `192.168.1.10`, così non cambia mai.
2. **Un solo router** (la box del provider fa tutto): inoltra **UDP `1195`** → `192.168.1.10`, porta **`1194`**.
3. **Due router in fila** (per esempio la box del provider e dietro un sistema mesh come TP-Link Deco in modalità *router* —
   "doppio NAT"): l'inoltro va fatto **due volte**:
   - sul **primo** router (quello del provider) dai al secondo router un indirizzo fisso (`192.168.0.2`, prenotazione DHCP)
     e inoltra **UDP `1195`** → `192.168.0.2`, porta **`1195`**;
   - sul **secondo** router (sui Deco: app › *Altro* › *Avanzate* › *Inoltro NAT* › *Inoltro porta*) inoltra **UDP `1195`**
     → `192.168.1.10`, porta **`1194`**.

   Se preferisci, mettendo il secondo router in modalità *access point* / bridge l'inoltro doppio non serve.

Dalla configurazione reale:

- alcuni router rifiutano la porta esterna `1194` perché la usano per il loro server VPN: va bene qualsiasi altra porta
  libera, per questo gli esempi usano `1195`;
- alcune box dei provider accettano solo porte fino a quattro cifre;
- il NAS ascolta sempre sulla `1194`: cambia solo la porta esterna.

## 4. VPN Server sul NAS

1. **Centro pacchetti** › installa **VPN Server**.
2. **VPN Server › OpenVPN**: attivalo, protocollo **UDP**, porta **1194**; lascia la **compressione disattivata**; spunta
   **Consenti ai client di accedere alla LAN del server** (così il telefono raggiunge l'indirizzo locale del NAS);
   **Applica**.
3. **VPN Server › Privilegi**: consenti OpenVPN **solo** all'utente DSM che userà il telefono.
4. **Pannello di controllo › Sicurezza › Protezione**: attiva il **Blocco automatico** (blocca gli indirizzi dopo troppi
   accessi falliti).
5. **Pannello di controllo › Sicurezza › Firewall** (se attivo): consenti **VPN Server (OpenVPN)**, porta 1194. Non serve
   aprire altro.
6. **VPN Server › OpenVPN › Esporta configurazione**: ottieni uno zip con un file `.ovpn`.

## 5. Il profilo `.ovpn`

Apri il `.ovpn` esportato con un editor di testo:

1. Nella riga `remote` metti il nome DDNS e la porta **esterna**:

   ```
   remote tuonome.synology.me 1195
   ```

2. Lascia `redirect-gateway` commentato (`#redirect-gateway def1`): nella VPN passa solo il traffico verso la rete di casa,
   tutto il resto usa la connessione normale del telefono ("split tunnel").
3. Subito dopo `proto udp` aggiungi queste due righe:

   ```
   tun-mtu 1340
   mssfix 1300
   ```

   Sui dati mobili i pacchetti più grandi di circa 1330 byte dentro il tunnel possono sparire senza errori: la lettura dal
   NAS funziona, ma **la scrittura dei file va in timeout**. Queste due righe tengono i pacchetti abbastanza piccoli.

Con ViewerCraftStar non serve modificare il `.ovpn`: server e porta esterni e i pacchetti più piccoli sono
impostazioni dell'app. Basta copiare lo **zip** esportato sul telefono (cavo USB, e-mail a te stesso, una cartella
cloud…).

## 6. Il telefono

1. Installa **OpenVPN for Android** da Google Play (in ViewerCraftStar › Impostazioni › VPN c'è il link). Non serve mai
   aprirla.
2. In **ViewerCraftStar › Impostazioni › VPN**: *Importa zip…*, poi il **server** esterno (il tuo nome DDNS) e la
   **porta** (quella inoltrata dal router), *Pacchetti più piccoli* attivo, **nome utente e password DSM**. Lo zip viene
   copiato anche nella cartella di servizio e va nel backup delle impostazioni; utente e password restano solo sul
   telefono.
3. Tocca lo **scudo** nella barra in basso. La prima volta OpenVPN for Android chiede di lasciarsi comandare da
   ViewerCraftStar: consenti. Il pallino diventa verde e in alto deve comparire **«NAS raggiungibile · via VPN»**.

## Nell'uso di tutti i giorni

- **A casa: VPN spenta.** L'app raggiunge il NAS direttamente sul Wi-Fi.
- **Fuori casa: VPN accesa** con lo scudo (o da sola all'apertura dell'app, se lo scegli in Impostazioni › VPN). Resta
  accesa finché non la spegni, dallo scudo o dalla notifica «VPN di ViewerCraftStar accesa».
- **Un'altra VPN** (per esempio AdGuard) occupa l'unico posto VPN di Android: lo scudo te lo dice e apre quell'app per
  spegnerla.
- Synology Drive continua a caricare le foto con le sue impostazioni (per esempio solo in Wi-Fi), con o senza VPN.
