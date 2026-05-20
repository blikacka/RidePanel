---
layout: default
title: RidePanel — Privacy Policy
---

# RidePanel — Privacy Policy

Last updated: 2026-05-20

This document is published from
[github.com/blikacka/RidePanel](https://github.com/blikacka/RidePanel)
and served via GitHub Pages. Source:
`docs/privacy/index.md` on the `master` branch.

Languages: [English](#english) · [Čeština](#čeština) ·
[Slovenčina](#slovenčina) · [Polski](#polski) · [Deutsch](#deutsch)

---

## English

RidePanel ("the App") does not collect, transmit, or share any personal
data. The App operates exclusively on-device and through a direct
peer-to-peer Wi-Fi link with the user's motorcycle head-unit.

### What the App stores locally

- A random UUID generated on first launch (`phoneUuid`), used as the
  identifier the head-unit knows your phone by. Never transmitted off
  the local Wi-Fi link.
- Pairing records for known head-units (Bluetooth MAC, head-unit ID,
  user-chosen label). Stored in Android SharedPreferences. Removed when
  the user uninstalls the App.
- Camera frames captured during QR code scanning. Processed in memory
  only; never stored, never transmitted.
- Screen frames captured via Android MediaProjection during an active
  mirror session. Streamed in real time to the paired head-unit over
  Wi-Fi Direct. Not stored, not buffered to disk, not transmitted to
  any third party.

### What the App does NOT do

- No analytics SDKs (Firebase, Crashlytics, AppCenter, etc.)
- No telemetry or usage tracking
- No cloud sync or remote backup
- No advertising
- No account registration
- No third-party API calls

### Permissions justification

- **INTERNET / ACCESS_NETWORK_STATE:** required by Android for socket
  operations on the local Wi-Fi Direct interface. The App makes no
  outbound calls to the public internet.
- **WRITE_SETTINGS:** optional user-grantable permission used solely to
  lock the system display orientation to landscape during a mirror
  session, preventing Google Maps from rotating away from the
  motorcycle's head-unit view. Original orientation is restored when
  the mirror session ends.
- **CAMERA:** used only when the user actively scans a head-unit QR
  code.
- **BLUETOOTH_SCAN / BLUETOOTH_CONNECT / NEARBY_WIFI_DEVICES:** used to
  discover and pair with motorcycle head-units in physical proximity.
- **FOREGROUND_SERVICE_MEDIA_PROJECTION:** required by Android to keep
  screen capture alive while the user is actively riding.

### Children

The App is not directed at children under 13.

### Changes to this policy

Material changes will bump the "Last updated" date above. The App
itself contains no mechanism that requires policy acceptance.

### Contact

jakub.cieciala@astrumq.com

---

## Čeština

RidePanel („Aplikace") nesbírá, nepředává ani nesdílí žádné osobní
údaje. Aplikace běží výhradně lokálně a komunikuje přímo přes Wi-Fi
peer-to-peer spojení s head-unitem motorky uživatele.

### Co Aplikace ukládá lokálně

- Náhodné UUID vygenerované při prvním spuštění (`phoneUuid`), používané
  jako identifikátor, pod kterým head-unit zná telefon. Nikdy se
  nepřenáší mimo lokální Wi-Fi spojení.
- Záznamy o spárovaných head-unitech (Bluetooth MAC, ID head-unitu,
  uživatelem zvolený název). Uloženy v Android SharedPreferences.
  Odstraněny při odinstalování Aplikace.
- Snímky z kamery při skenování QR kódu. Zpracovávány pouze v paměti;
  nikde se neukládají ani nepřenášejí.
- Snímky obrazovky zachycené přes Android MediaProjection během
  aktivního zrcadlení. Streamují se v reálném čase na spárovaný
  head-unit přes Wi-Fi Direct. Nikde se neukládají, nezálohují na disk
  ani nepřenášejí třetím stranám.

### Co Aplikace NEDĚLÁ

- Žádné analytické SDK (Firebase, Crashlytics, AppCenter atd.)
- Žádná telemetrie ani sledování použití
- Žádná synchronizace s cloudem ani vzdálená záloha
- Žádné reklamy
- Žádná registrace účtu
- Žádná volání API třetích stran

### Odůvodnění oprávnění

- **INTERNET / ACCESS_NETWORK_STATE:** vyžaduje Android pro socketové
  operace na lokálním Wi-Fi Direct rozhraní. Aplikace nedělá žádná
  volání na veřejný internet.
- **WRITE_SETTINGS:** volitelné uživatelem udělené oprávnění používané
  výhradně pro zamknutí orientace systému na šířku během zrcadlení,
  aby se Google Mapy neotočily mimo zobrazení head-unitu motorky.
  Původní orientace se obnoví po ukončení zrcadlení.
- **CAMERA:** používáno pouze při aktivním skenování QR kódu
  head-unitu uživatelem.
- **BLUETOOTH_SCAN / BLUETOOTH_CONNECT / NEARBY_WIFI_DEVICES:** používáno
  k objevení a spárování s head-unity motorek v dosahu.
- **FOREGROUND_SERVICE_MEDIA_PROJECTION:** vyžaduje Android pro
  zachování zrcadlení obrazovky během jízdy.

### Děti

Aplikace není určena dětem mladším 13 let.

### Změny této politiky

Podstatné změny budou doprovázeny aktualizací data „Last updated" výše.
Aplikace sama o sobě neobsahuje žádný mechanismus, který by vyžadoval
přijetí politiky.

### Kontakt

jakub.cieciala@astrumq.com

---

## Slovenčina

RidePanel („Aplikácia") nezhromažďuje, neprenáša ani nezdieľa žiadne
osobné údaje. Aplikácia beží výlučne lokálne a komunikuje priamo cez
Wi-Fi peer-to-peer spojenie s head-unitom motorky používateľa.

### Čo Aplikácia ukladá lokálne

- Náhodné UUID vygenerované pri prvom spustení (`phoneUuid`), používané
  ako identifikátor, pod ktorým head-unit pozná telefón. Nikdy sa
  neprenáša mimo lokálne Wi-Fi spojenie.
- Záznamy o spárovaných head-unitoch (Bluetooth MAC, ID head-unitu,
  používateľom zvolený názov). Uložené v Android SharedPreferences.
  Odstránené pri odinštalovaní Aplikácie.
- Snímky z kamery pri skenovaní QR kódu. Spracovávané iba v pamäti;
  nikde sa neukladajú ani neprenášajú.
- Snímky obrazovky zachytené cez Android MediaProjection počas
  aktívneho zrkadlenia. Streamujú sa v reálnom čase na spárovaný
  head-unit cez Wi-Fi Direct. Nikde sa neukladajú, nezálohujú na disk
  ani neprenášajú tretím stranám.

### Čo Aplikácia NEROBÍ

- Žiadne analytické SDK (Firebase, Crashlytics, AppCenter atď.)
- Žiadna telemetria ani sledovanie použitia
- Žiadna synchronizácia s cloudom ani vzdialená záloha
- Žiadne reklamy
- Žiadna registrácia účtu
- Žiadne volania API tretích strán

### Odôvodnenie oprávnení

- **INTERNET / ACCESS_NETWORK_STATE:** vyžaduje Android pre socketové
  operácie na lokálnom Wi-Fi Direct rozhraní. Aplikácia nerobí žiadne
  volania na verejný internet.
- **WRITE_SETTINGS:** voliteľné používateľom udelené oprávnenie
  používané výlučne na uzamknutie orientácie systému na šírku počas
  zrkadlenia, aby sa Google Mapy neotočili mimo zobrazenie head-unitu
  motorky. Pôvodná orientácia sa obnoví po ukončení zrkadlenia.
- **CAMERA:** používané iba pri aktívnom skenovaní QR kódu head-unitu
  používateľom.
- **BLUETOOTH_SCAN / BLUETOOTH_CONNECT / NEARBY_WIFI_DEVICES:**
  používané na objavenie a spárovanie s head-unitmi motoriek v dosahu.
- **FOREGROUND_SERVICE_MEDIA_PROJECTION:** vyžaduje Android na
  zachovanie zrkadlenia obrazovky počas jazdy.

### Deti

Aplikácia nie je určená deťom mladším ako 13 rokov.

### Zmeny tejto politiky

Podstatné zmeny budú sprevádzané aktualizáciou dátumu „Last updated"
vyššie. Aplikácia sama o sebe neobsahuje žiadny mechanizmus, ktorý by
vyžadoval prijatie politiky.

### Kontakt

jakub.cieciala@astrumq.com

---

## Polski

RidePanel ("Aplikacja") nie zbiera, nie przekazuje ani nie udostępnia
żadnych danych osobowych. Aplikacja działa wyłącznie lokalnie i
komunikuje się bezpośrednio przez połączenie Wi-Fi peer-to-peer z
head-unitem motocykla użytkownika.

### Co Aplikacja przechowuje lokalnie

- Losowe UUID wygenerowane przy pierwszym uruchomieniu (`phoneUuid`),
  używane jako identyfikator, pod którym head-unit zna telefon. Nigdy
  nie jest przesyłane poza lokalne połączenie Wi-Fi.
- Zapisy o sparowanych head-unitach (Bluetooth MAC, ID head-unitu,
  nazwa wybrana przez użytkownika). Przechowywane w Android
  SharedPreferences. Usuwane przy odinstalowaniu Aplikacji.
- Klatki z aparatu podczas skanowania kodu QR. Przetwarzane wyłącznie
  w pamięci; nigdzie nie są zapisywane ani przesyłane.
- Klatki ekranu zarejestrowane przez Android MediaProjection podczas
  aktywnego dublowania. Strumieniowane w czasie rzeczywistym do
  sparowanego head-unitu przez Wi-Fi Direct. Nigdzie nie są zapisywane,
  nie są buforowane na dysku, nie są przesyłane stronom trzecim.

### Czego Aplikacja NIE robi

- Brak SDK analitycznych (Firebase, Crashlytics, AppCenter itp.)
- Brak telemetrii ani śledzenia użycia
- Brak synchronizacji z chmurą ani zdalnej kopii zapasowej
- Brak reklam
- Brak rejestracji konta
- Brak wywołań API stron trzecich

### Uzasadnienie uprawnień

- **INTERNET / ACCESS_NETWORK_STATE:** wymagane przez Android dla
  operacji gniazdowych na lokalnym interfejsie Wi-Fi Direct. Aplikacja
  nie wykonuje żadnych wywołań do publicznego internetu.
- **WRITE_SETTINGS:** opcjonalne uprawnienie przyznawane przez
  użytkownika, używane wyłącznie do zablokowania orientacji
  poziomej systemu podczas sesji dublowania, aby zapobiec obracaniu
  się Google Maps poza widok head-unitu motocykla. Oryginalna
  orientacja zostaje przywrócona po zakończeniu sesji.
- **CAMERA:** używana tylko gdy użytkownik aktywnie skanuje kod QR
  head-unitu.
- **BLUETOOTH_SCAN / BLUETOOTH_CONNECT / NEARBY_WIFI_DEVICES:** używane
  do wykrywania i parowania z head-unitami motocykli w pobliżu.
- **FOREGROUND_SERVICE_MEDIA_PROJECTION:** wymagane przez Android do
  utrzymania przechwytywania ekranu podczas aktywnej jazdy.

### Dzieci

Aplikacja nie jest skierowana do dzieci poniżej 13 lat.

### Zmiany w tej polityce

Istotne zmiany będą oznaczone aktualizacją daty "Last updated"
powyżej. Sama Aplikacja nie zawiera żadnego mechanizmu wymagającego
akceptacji polityki.

### Kontakt

jakub.cieciala@astrumq.com

---

## Deutsch

RidePanel ("die App") sammelt, überträgt oder teilt keine
personenbezogenen Daten. Die App läuft ausschließlich auf dem Gerät
und kommuniziert direkt über eine Wi-Fi Peer-to-Peer-Verbindung mit
dem Motorrad-Head-Unit des Nutzers.

### Was die App lokal speichert

- Eine zufällige UUID, die beim ersten Start erzeugt wird
  (`phoneUuid`), als Kennung, unter der die Head-Unit das Telefon
  kennt. Wird niemals außerhalb der lokalen Wi-Fi-Verbindung übertragen.
- Pairing-Daten bekannter Head-Units (Bluetooth-MAC, Head-Unit-ID,
  benutzerdefinierter Name). Werden in Android SharedPreferences
  gespeichert. Werden bei Deinstallation der App entfernt.
- Kamerabilder beim Scannen eines QR-Codes. Werden nur im
  Arbeitsspeicher verarbeitet; nirgendwo gespeichert, niemals
  übertragen.
- Bildschirminhalte, die über Android MediaProjection während einer
  aktiven Mirror-Sitzung erfasst werden. Werden in Echtzeit über Wi-Fi
  Direct an die gekoppelte Head-Unit gestreamt. Werden nirgendwo
  gespeichert, nicht auf der Festplatte gepuffert und nicht an Dritte
  übertragen.

### Was die App NICHT tut

- Keine Analytics-SDKs (Firebase, Crashlytics, AppCenter usw.)
- Keine Telemetrie oder Nutzungsverfolgung
- Keine Cloud-Synchronisation oder Remote-Backup
- Keine Werbung
- Keine Kontoregistrierung
- Keine Aufrufe an Drittanbieter-APIs

### Rechtfertigung der Berechtigungen

- **INTERNET / ACCESS_NETWORK_STATE:** wird von Android für
  Socket-Operationen auf der lokalen Wi-Fi-Direct-Schnittstelle
  benötigt. Die App führt keine Aufrufe ins öffentliche Internet aus.
- **WRITE_SETTINGS:** optionale, vom Nutzer erteilte Berechtigung, die
  ausschließlich verwendet wird, um die System-Displayausrichtung
  während einer Mirror-Sitzung im Querformat zu sperren, damit Google
  Maps nicht aus der Ansicht der Motorrad-Head-Unit gedreht wird. Die
  ursprüngliche Ausrichtung wird wiederhergestellt, wenn die Sitzung
  endet.
- **CAMERA:** wird nur verwendet, wenn der Nutzer aktiv den QR-Code
  einer Head-Unit scannt.
- **BLUETOOTH_SCAN / BLUETOOTH_CONNECT / NEARBY_WIFI_DEVICES:** wird
  verwendet, um Motorrad-Head-Units in physischer Nähe zu erkennen
  und zu koppeln.
- **FOREGROUND_SERVICE_MEDIA_PROJECTION:** wird von Android benötigt,
  um die Bildschirmaufnahme während aktiver Fahrt am Leben zu halten.

### Kinder

Die App richtet sich nicht an Kinder unter 13 Jahren.

### Änderungen dieser Richtlinie

Wesentliche Änderungen werden mit einer Aktualisierung des Datums
"Last updated" oben gekennzeichnet. Die App selbst enthält keinen
Mechanismus, der die Annahme der Richtlinie verlangt.

### Kontakt

jakub.cieciala@astrumq.com
