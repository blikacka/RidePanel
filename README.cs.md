# RidePanel

📖 English version: [README.md](README.md)

Bezplatná open-source Android aplikace, která zrcadlí obrazovku telefonu
na motorkový head-unit, abys mohl jezdit s Google Mapami, hudbou nebo
jakoukoli jinou aplikací přímo na palubním displeji místo lepení telefonu
na řídítka.

Inspirovaná — a protokolově kompatibilní s — ekosystémem head-unitů
*Carbit Ride* (řada SSDQ01, infotainment třídy EasyConn), ale postavená
nezávisle od základů jako komunitní projekt. Žádný cloud účet, žádná
telemetrie, žádné analytiky. Vše běží lokálně mezi telefonem a motorkou.

## Co umí

- 📱 → 🏍️ **Zrcadlení obrazovky** z telefonu na 800×480 palubní panel
  přes Wi-Fi Direct. H.264 video, hardwarově akcelerované.
- 🗺️ **Google Mapy na šířku jedním kliknutím** — spustí zrcadlení a
  otevře Mapy se zamknutou orientací, perfektní pro navigaci.
- ✅ **Splash „připojeno"** na head-unitu mezi spárováním a sdílením
  obrazu, ať víš že telefon s motorkou už komunikuje, ještě než ho dáš
  do kapsy.
- 💾 **Uložená zařízení** — spáruj jednou přes QR, příště se připoj
  jedním klikem.
- 🎯 **Správný poměr stran** — letterboxing nezávisle na orientaci
  telefonu, žádné natahování obrazu.
- 🌍 **Vícejazyčnost**: English · Čeština · Slovenčina · Polski · Deutsch.

## Jak to funguje

Stiskni **horní tlačítko na levé ovládací jednotce u řídítek** — na
palubním displeji se objeví QR kód. Naskenuj ho v RidePanelu — telefon
se připojí na Wi-Fi Direct head-unitu. RidePanel se ohlásí, otevře pár
lokálních TCP socketů a čeká, až si head-unit vyžádá video. Jakmile to
udělá, na palubce se zobrazí splash „připojeno", což znamená že telefon
je spárovaný.

Po stisknutí **Spustit zrcadlení** nebo **Otevřít Google Mapy**:

1. Android `MediaProjection` zachytí obrazovku.
2. `VirtualDisplay` posílá obraz do GLES letterbox shaderu, který ho
   aspect-correctně vloží do 800×480 framu head-unitu (s černými pruhy
   tam, kde je potřeba).
3. `MediaCodec` enkóduje snímky do H.264 (Baseline @ Level 4, 4 Mbps,
   30 fps, 2 sekundový I-frame interval — odladěné pro stabilní Wi-Fi P2P).
4. Snímky se živě streamují na palubku.

Po stisku **Stop** se zrcadlení čistě ukončí a auto-rotace telefonu se
vrátí do původního stavu.

## Testované zařízení

| Telefon      | OS         | Stav |
|--------------|------------|------|
| Pixel 10 Pro | Android 16 | ✅ denní použití |

| Motorka / head-unit                  | Displej    | Stav |
|--------------------------------------|------------|------|
| **QJMOTO SRT 600 SX** (stock panel)  | 5" 800×480 | ✅ denní použití |

Další telefony a další kompatibilní head-unity v tomto ekosystému by
měly fungovat — protokol je stejný. Pokud zkusíš jinou kombinaci, otevři
prosím issue nebo pošli PR s tím co jsi zjistil.

## Instalace

Aplikace zatím není na Google Play. Sideloaduj APK:

```bash
# Clone, build, install na zařízení se zapnutým USB debugging
git clone https://github.com/<your-fork>/ridepanel.git
cd ridepanel/RidePanel
./gradlew :app:installDebug
```

Nebo si stáhni pre-built `app-debug.apk` z
[GitHub Releases](../../releases) (až bude k dispozici) a nainstaluj
přes `adb install` / souborový manažer.

**Oprávnění o která tě aplikace požádá**, všechna vysvětlená v in-app FAQ:

- *Notifikace* — drží mirror službu naživu na pozadí.
- *Modify system settings* — zamkne telefon na šířku během zrcadlení,
  ať se Mapy neotočí na výšku když motorka skloní telefon.
- *Wi-Fi Direct* — pro QR párovací flow.
- *MediaProjection* (jednorázově per session) — standardní Android dialog
  „sdílet obrazovku".

## Příspěvky

**Pull requesty jsou velmi vítané** 🙌 — RidePanel je hobby projekt
udržovaný motorkáři, kteří taky umí kódovat. Pokud máš:

- otestovanou jinou kombinaci motorka / head-unit / telefon,
- překlad do svého jazyka,
- vylepšení UX nebo bug fix,
- nápad na novou featuru (audio? Mapy.cz integrace? touch zpětný kanál
  z head-unitu?) …

…otevři issue na diskuzi, nebo prostě pošli PR. Žádné CLA, žádné
gatekeeping — neboj se. Code style je whatever čistý Kotlin vypadá v
souborech kterých se dotkneš.

## Kup mi kávu ☕

Pokud ti RidePanel ušetřil cenu phone-mountu + kabelů, můžeš poslat
spropitné přes PayPal:
[paypal.com/donate](https://www.paypal.com/donate/?hosted_button_id=XL58JUWCCWWPC)

Čistě dobrovolné — aplikace je a vždy bude zdarma.

## Licence

MIT — viz [LICENSE](../LICENSE). Použij, uprav, prodávej komerčně, klidně;
jen zachovej copyright notice. Software je poskytován „TAK JAK JE", bez
jakékoli záruky — jízda na motorce je činnost s vysokým rizikem, nespoléhej
na aplikaci v ničem, co je safety-critical.
