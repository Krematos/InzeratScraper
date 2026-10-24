# InzeratScraper 🛒

Automatizovaný integrační a migrační nástroj pro extrakci inzerátů z bazarových portálů (Bazoš.cz), jejich inteligentní čištění a strukturování pomocí LLM (Gemini / OpenAI), striktní deterministickou validaci v Javě a následný import do cílového e-shopu.

---

## 🎯 Hlavní vlastnosti a architektura

Projekt řeší kompletní migrační pipeline rozdělenou do fází s mezikrokem v lokálním staging úložišti:

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
                                                                       │
                                                                       ▼
                                                           ┌───────────────────────┐
                                                           │ Fáze 4–6: Export,     │
                                                           │ Virtuální vlákna,     │
                                                           │ Statistiky a Job API  │
                                                           └───────────────────────┘
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

### 4. Fáze 4: Paralelní zpracování na virtuálních vláknech
- Čištění inzerátů běží na lehkých virtuálních vláknech Javy 21+ (`Executors.newVirtualThreadPerTaskExecutor()`).
- **Bulkhead (Semafor)**: Řízení maximálního souběhu volání LLM API (`llm.max-concurrency`, výchozí 3) pro ochranu před přetížením sítě i databáze.
- **Atomický claim záznamů**: Zabraňuje souběžnému zpracování stejného inzerátu více vlákny (`UPDATE ... SET status = 'PROCESSING' WHERE id = ... AND status = 'SCRAPED'`).
- **Graceful shutdown**: Metoda `@PreDestroy` při ukončení aplikace automaticky uvolní rozpracované záznamy ze stavu `PROCESSING` zpět do `SCRAPED`.
- **Automatická obnova uvízlých úloh**: Watchdog periodicky vrací záznamy zaseknuté v `PROCESSING` déle než stanovený limit (`scraper.processing-timeout-minutes`).

### 5. Fáze 5: Asynchronní úlohy (Clean Job API)
- Dlouhotrvající operace čištění lze spouštět asynchronně na pozadí (`async=true`, výchozí stav).
- Klient obdrží odpověď `202 Accepted` s unikátním `jobId` a může sledovat průběh v reálném čase.
- Ochrana proti souběžnému spuštění více úloh současně (`409 Conflict`).

### 6. Robustní LLM klient & Kaskádový fallback modelů
- **Pořadí modelů**:
  1. `gemini-3.1-flash-lite` (primární – nejrychlejší, úsporný a stabilní)
  2. `gemini-3.5-flash-lite` (1. fallback)
  3. `gemini-3.6-flash` (2. fallback)
  4. `gemini-3.8-flash` (3. fallback)
- **Rychlý fallback**: Timeout požadavku snížen na 15 s (`llm.read-timeout-seconds=15`). Při HTTP 503, 504, 404 nebo vypršení timeoutu klient okamžitě přepíná na další model v řadě.
- **Rate limiting & Sdílený cooldown**: Omezení frekvence požadavků za minutu (`llm.requests-per-minute`) a koordinovaná sdílená pauza pro všechna vlákna při návratu HTTP 429 na základě hlavičky `Retry-After` nebo exponenciálního backoffu.
- **Bezpečné logování**: Automatická sanitizace API klíčů v URL a zkracování těl chybových odpovědí na max. 500 znaků.

---

## 🛠️ Požadavky na prostředí

- **Java**: verze 21+ (doporučeno JDK 21 nebo novější)
- **Maven**: verze 3.9+ (nebo přibalený `./mvnw`)
- **Operační systém**: Windows / Linux / macOS

---

## ⚙️ Konfigurace

Aplikace se konfiguruje v souboru `src/main/resources/application.properties` nebo pomocí proměnných prostředí. K dispozici je vzorový soubor `application-example.properties`.

| Proměnná prostředí | Výchozí hodnota | Popis |
|--------------------|-----------------|-------|
| `LLM_API_URL` | Gemini 3.1 Flash-Lite endpoint | URL adresa primárního LLM modelu |
| `LLM_API_KEY` | *(prázdné)* | API klíč k LLM modelu (Google AI Studio / OpenAI) |
| `LLM_READ_TIMEOUT_SECONDS` | `15` | Read timeout HTTP požadavku na LLM API v sekundách |
| `LLM_MAX_CONCURRENCY` | `3` | Maximální počet souběžných volání LLM API (bulkhead semafor) |
| `LLM_REQUESTS_PER_MINUTE` | `10` | Omezení frekvence volání LLM API (RateLimiter) |
| `LLM_MAX_ATTEMPTS` | `5` | Maximální počet pokusů při přechodných chybách před označením `FAILED` |
| `ESHOP_REMOTE_URL` | `http://localhost:8080` | Kořenová URL adresa cílového e-shopu |
| `ESHOP_REMOTE_API_KEY` | *(prázdné)* | Autentizační klíč e-shopu (`X-API-Key`) |
| `ESHOP_CATEGORIES` | `Elektronika,Nářadí,Ostatní...` | Čárkou oddělený seznam povolených kategorií |
| `ESHOP_VAT_RATE` | `STANDARD` | Sazba DPH (`STANDARD`, `REDUCED`, `ZERO`) |
| `MIGRATION_API_KEY`| *(prázdné)* | Volitelný klíč pro zabezpečení lokálních migračních endpointů |
| `SCRAPER_MAX_PAGES`| `50` | Bezpečnostní limit počtu stránek výpisu |
| `SCRAPER_MAX_ADS`  | `500` | Bezpečnostní limit celkového počtu inzerátů |
| `SCRAPER_IMAGES_DIR` | `./data/images` | Složka pro lokální zálohu stažených fotek |
| `SCRAPER_PROCESSING_TIMEOUT_MINUTES` | `15` | Timeout pro obnovu uvízlých záznamů ve stavu `PROCESSING` |

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

### 2. Čištění inzerátů přes AI (Asynchronní i synchronní)
Zpracuje surové záznamy ve stavu `SCRAPED` přes kaskádu LLM modelů a validaci.
```http
# Výchozí asynchronní spuštění (vrací 202 Accepted s jobId):
POST /api/migration/clean?limit=20&retryFailed=false&async=true

# Synchronní spuštění (vrací 200 OK až po dokončení celé dávky):
POST /api/migration/clean?limit=20&retryFailed=false&async=false
```

### 3. Zjištění stavu asynchronní úlohy
Dotaz na průběh asynchronního čištění podle `jobId`.
```http
GET /api/migration/jobs/{jobId}
```
Odpověď:
```json
{
  "jobId": "c8a14b0e-...",
  "status": "RUNNING",
  "total": 20,
  "processed": 8,
  "succeeded": 7,
  "failed": 0,
  "needsReview": 1,
  "startedAt": "2026-10-24T20:00:00Z",
  "finishedAt": null
}
```

### 4. Přehled počtu inzerátů v jednotlivých fázích
Vrátí souhrnnou statistiku počtů záznamů napříč všemi stavy.
```http
GET /api/migration/stats
```
Odpověď:
```json
{
  "SCRAPED": 25,
  "PROCESSING": 0,
  "CLEANED": 18,
  "NEEDS_REVIEW": 2,
  "FAILED": 0,
  "SYNCED": 0
}
```

### 5. Závěrečný export vyčištěných produktů do JSON
Vrátí strukturované pole vyčištěných produktů (`CleanedProductDto`). Umožňuje volitelný filtr podle stavu a přímé uložení do souboru `./data/products-export.json`.
```http
# Jen výpis JSON do odpovědi:
GET /api/migration/export?status=CLEANED

# Výpis a zároveň uložení na disk do ./data/products-export.json:
GET /api/migration/export?status=CLEANED&saveToFile=true
```

### 6. Zobrazení inzerátů vyžadujících kontrolu
Vrátí inzeráty ve stavu `NEEDS_REVIEW` (např. sporná cena, cena pouze v textu, chybějící parametry).
```http
GET /api/migration/review
```

### 7. Synchronizace do e-shopu
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
