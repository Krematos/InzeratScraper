# InzeratScraper 🛒

Automatizovaný integrační a migrační nástroj pro extrakci inzerátů z bazarových portálů (Bazoš.cz), jejich inteligentní čištění a strukturování pomocí LLM (Gemini / OpenAI), striktní deterministickou validaci v Javě a následný import do cílového e-shopu.

---

## 🎯 Hlavní vlastnosti a architektura

Projekt řeší kompletní migrační pipeline rozdělenou do 3 fází s mezikrokem v lokálním staging úložišti:

```
┌─────────────────┐       ┌────────────────────────┐       ┌───────────────────────┐
│     Bazoš       │ ────> │   Fáze 1: Scraping     │ ────> │  Staging DB (H2)      │
│ (Profil prodejce│       │  & Záloha obrázků      │       │ (Raw inzeráty + stav) │
└─────────────────┘       └────────────────────────┘       └───────────────────────┘
                                                                       │
                                                                       ▼
┌─────────────────┐       ┌────────────────────────┐       ┌───────────────────────┐
│ E-shop (VPS)    │ <──── │ Fáze 3: Synchronizace  │ <──── │ Fáze 2: AI Čištění    │
│ (REST API)      │       │ (včetně Dry-Run módu)  │       │ & Java Validace       │
└─────────────────┘       └────────────────────────┘       └───────────────────────┘
```

### 1. Fáze 1: Scraping & Staging úložiště (`BazosScraperService`)
- Prochází inzeráty zadaného profilu prodejce včetně stránkování (`&crz=20, 40...`).
- Aplikuje bezpečnostní limity (`max-pages`, `max-ads`, `max-body-size`) proti zacyklení nebo honeypotům.
- Stahuje obrázky v plném rozlišení přímo na disk (obchází miniatury `1t` -> `1`).
- Surové inzeráty ukládá do lokální H2 databáze (`data/staging-db`) ve stavu `SCRAPED` s kontrolou duplicity podle `external_id`.

### 2. Fáze 2: Inteligentní čištění & Deterministická validace (`AdDataCleanerService`)
- **Předčištění regexem** (`AdTextSanitizer`): Okamžité odstranění bazarových klišé (*„inzerát platí do smazání“*, *„na dobírku neposílám“*), telefonních čísel a e-mailů.
- **LLM analýza** (`LlmClient`): Převod textu na strukturovaný JSON (název produktu, kanonická kategorie z allowlistu e-shopu, stav zboží `NEW`/`USED`/`DAMAGED`, extrahované technické parametry).
- **Nezávislá kódová validace** (`ProductDataValidator`):
  - **Sanitizace HTML**: `Jsoup.clean(..., Safelist.none())` s konfigurací `OutputSettings().prettyPrint(false)` a `Parser.unescapeEntities(..., false)` zachovává skutečné odstavce i znaky jako `&` a `<` (bez nežádoucího převodu na `&amp;` a `&lt;`).
  - **Omezení délky**: Název max. 100 znaků (ořez na hranici celého slova), popis max. 1000 znaků (ořez na hranici celé věty).
  - **Unikátnost názvu**: Automatické přidávání čísla inzerátu k duplicitním názvům pro splnění `UNIQUE` constraintu v cílovém e-shopu.
  - **Validace ceny**: Detekce cenových anomálií (> 20 % odchylka oproti regexu) nebo chybějící ceny a automatické zařazení do stavu `NEEDS_REVIEW` pro ruční kontrolu.

### 3. Fáze 3: Synchronizace do e-shopu (`RemoteEshopSyncService`)
- Postupný přenos vyčištěných inzerátů do e-shopu přes zabezpečené interní REST API (`X-API-Key`).
- Podpora **Dry-Run režimu** (`dryRun=true`), kdy se data pouze zvalidují a vypíšou do logu bez odesílání na server.

---

## 🛠️ Požadavky na prostředí

- **Java**: verze 21 (JDK 21)
- **Maven**: verze 3.9+ (nebo přibalený `./mvnw`)
- **Operační systém**: Windows / Linux / macOS

---

## ⚙️ Konfigurace

Aplikace se konfiguruje v souboru `src/main/resources/application.properties` nebo pomocí proměnných prostředí. K dispozici je vzorový soubor `application-example.properties`.

| Proměnná prostředí | Výchozí hodnota | Popis |
|--------------------|-----------------|-------|
| `LLM_API_URL` | Gemini 3.8 Flash endpoint | URL adresa LLM API (Google Gemini / OpenAI kompatibilní) |
| `LLM_API_KEY` | *(prázdné)* | API klíč k LLM modelu |
| `ESHOP_REMOTE_URL` | `http://localhost:8080` | Kořenová URL adresa cílového e-shopu |
| `ESHOP_REMOTE_API_KEY` | *(prázdné)* | Autentizační klíč e-shopu (`X-API-Key`) |
| `ESHOP_CATEGORIES` | `Elektronika,Nářadí,Ostatní...` | Čárkou oddělený seznam povolených kategorií |
| `ESHOP_VAT_RATE` | `STANDARD` | Sazba DPH (`STANDARD`, `REDUCED`, `ZERO`) |
| `MIGRATION_API_KEY`| *(prázdné)* | Volitelný klíč pro zabezpečení lokálních migračních endpointů |
| `SCRAPER_MAX_PAGES`| `50` | Bezpečnostní limit počtu stránek výpisu |
| `SCRAPER_MAX_ADS`  | `500` | Bezpečnostní limit celkového počtu inzerátů |
| `SCRAPER_IMAGES_DIR` | `./data/images` | Složka pro lokální zálohu stažených fotek |

---

## 🚀 Sestavení a spuštění

### Sestavení projektu a spuštění testů
```bash
./mvnw clean verify
# na Windows:
.\mvnw.cmd clean verify
```

### Spuštění aplikace
V PowerShellu na Windows je nutné argumenty začínající na `-D` obalit do uvozovek, aby je PowerShell nerozdělil:
```powershell
# PowerShell (Windows):
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"

# Bash (Linux / macOS):
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```
Aplikace naslouchá na `http://127.0.0.1:8080`.

---

## 📡 REST API Rozhraní

Aplikaci lze řídit přes HTTP endpointy v controlleru [`MigrationController`](file:///C:/Users/hmac1/Desktop/Byznys/InzeratScraper/InzeratScraper/src/main/java/hanzner/inzeratscraper/controller/MigrationController.java):

### 1. Spuštění scrapingu z Bazoše
Stáhne inzeráty do lokální staging databáze a uloží fotografie na disk.
```http
POST /api/migration/scrape?profileUrl=https://www.bazos.cz/hodnoceni.php?idmail=123456&downloadImages=true
```

### 2. Dávkové vyčištění přes AI
Vezme inzeráty ve stavu `SCRAPED` nebo `FAILED`, provede LLM analýzu a kódovou validaci.
```http
POST /api/migration/clean?limit=20
```

### 3. Zobrazení inzerátů vyžadujících kontrolu
Vrátí inzeráty ve stavu `NEEDS_REVIEW` (např. sporná cena, cena v textu, chybějící název).
```http
GET /api/migration/review
```

### 4. Synchronizace do e-shopu
Odešle vyčištěné produkty do cílového e-shopu.
```http
# Zkušební běh bez odeslání (pouze logování a validace)
POST /api/migration/sync?limit=20&dryRun=true

# Ostrý import do e-shopu
POST /api/migration/sync?limit=20&dryRun=false
```

---

## 🔒 Bezpečnost

- **Síťová izolace**: Aplikace se ve výchozím stavu váže výhradně na loopback adaptér `127.0.0.1`.
- **H2 Konzole**: H2 webová konzole (`/h2-console`) je v produkčním nastavení omezena pouze na localhost.
- **XSS ochrana**: Všechny texty z externích webů a LLM modelů prochází striktním HTML stripem přes `Jsoup` s prázdným safelistem.

---

## 🤖 CI & Dependabot

- **Continuous Integration** (`.github/workflows/ci.yml`): Automatický build a běh testů v prostředí JDK 21 při každém commitu a pull requestu.
- **Dependabot** (`.github/dependabot.yml`): Týdenní automatické kontroly a aktualizace závislostí v Maven (`pom.xml`) a GitHub Actions.
