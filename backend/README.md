# Trame - piattaforma culturale

Prototipo universitario per un'impresa culturale simulata: catalogazione dei contenuti,
prenotazione degli eventi e raccolta dei feedback in un unico sistema.

## Architettura

```text
Browser (HTML, CSS, JavaScript)
       | fetch JSON + bearer JWT
Spring Boot 3 / Java 21 (HTTP, validazione, ruoli, transazioni)
       | JDBC con query parametrizzate
PostgreSQL (utenti, catalogo, eventi, prenotazioni, feedback)
```

Il front-end in [static](src/main/resources/static) viene servito direttamente da
Spring Boot: non occorrono Node, npm, CDN o un secondo server. API e interfaccia hanno
la stessa origine. Il contratto delle API e in [openapi.yaml](../openapi.yaml).
Le password sono hashate con BCrypt; credenziali e chiavi non sono nel codice.
Il token viene conservato nel `localStorage` del browser e ripristinato dopo un
ricaricamento; le password non vengono salvate nel browser. Ogni richiesta autenticata
verifica nel DB l'esistenza e il ruolo corrente
dell'utente. Le risposte non contengono hash di password.

## Avvio su Windows

Prerequisiti: JDK 21, Maven 3.9+, PostgreSQL e database `cultural_db`.
Applicare [schema.sql](../schema.sql) se il database non e gia aggiornato:

```powershell
psql -h localhost -U postgres -d cultural_db -v ON_ERROR_STOP=1 -f .\schema.sql
```

Dalla cartella `backend` eseguire:

```powershell
.\start.ps1
```

Lo script usa `JAVA_HOME`, chiede la password PostgreSQL senza visualizzarla e genera
una chiave JWT temporanea, senza salvarla su disco. Quando Tomcat e pronto, aprire
**http://localhost:8080**. Fermare con **Ctrl+C**. Lo script ripristina le variabili
di ambiente che ha modificato al termine.

Configurazione facoltativa tramite variabili d'ambiente:

| Variabile | Valore predefinito / funzione |
|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/cultural_db` |
| `DATABASE_USERNAME` | `postgres` (solo sviluppo locale; preferire un utente dedicato) |
| `DATABASE_PASSWORD` | Richiesta dallo script se non impostata |
| `JWT_SECRET_BASE64` | Generata dallo script; almeno 32 byte casuali codificati Base64 |
| `JWT_EXPIRATION_SECONDS` | `3600`, deve essere positivo |
| `SERVER_PORT` | `8080` (adattare l'URL nel browser se modificata) |

In alternativa, con `DATABASE_PASSWORD` e `JWT_SECRET_BASE64` gia configurate:

```powershell
mvn spring-boot:run
```

Il backend non crea o modifica automaticamente le tabelle all'avvio. La chiave deve
restare stabile tra avvii solo se si desidera mantenere validi i token non scaduti.
Non aprire direttamente l'HTML dal filesystem: usare l'indirizzo HTTP del backend.

## Primo accesso e ruoli

1. Registrare un account dall'interfaccia. Diventa sempre `ROLE_USER`.
2. Per creare il primo amministratore, eseguire nel Query Tool, sostituendo lo username:

```sql
UPDATE users
SET role = 'ROLE_ADMIN'
WHERE username = 'your-admin-username';
```

3. Uscire e accedere nuovamente per aggiornare anche i menu dell'interfaccia.
4. Da **Gestione** creare eventi e contenuti: il database iniziale puo essere vuoto.

| Ruolo | Funzionalita |
|---|---|
| Ospite | Catalogo, ricerca con paginazione, eventi con filtri per data, dettagli e feedback |
| Visitatore | Anche prenotazioni personali, annullamento e invio feedback |
| Operatore | Anche creazione di eventi e contenuti |
| Amministratore | Anche elenco utenti con assegnazione ruoli, tutte le prenotazioni e dashboard |

La registrazione pubblica assegna sempre `ROLE_USER`. Solo il primo amministratore
deve essere predisposto sul DB come indicato sopra. Successivamente, da **Dashboard >
Utenti registrati**, un amministratore puo scegliere Visitatore, Operatore o
Amministratore per un altro account e premere **Salva ruolo**. Non puo cambiare il
proprio ruolo. Le modifiche sono serializzate e il ruolo del chiamante e ricontrollato
nella transazione, impedendo anche che amministratori concorrenti si rimuovano a vicenda.
I permessi API cambiano subito, anche con un token gia emesso; l'utente deve uscire e
accedere nuovamente per aggiornare i menu del browser.

## Regole dei dati e API

Implementati tutti gli endpoint descritti nel contratto:

- `POST /api/auth/register`, `POST /api/auth/login`
- `GET /api/users/me`, `GET /api/users` (solo amministratore)
- `PUT /api/admin/users/{id}/role` (solo amministratore, escluso il proprio account)
- `GET/POST /api/catalog`, `GET /api/catalog/{id}`
- `GET/POST /api/events`, `GET /api/events/{id}`
- `POST /api/events/{id}/reservations`, `GET /api/reservations`
- `PUT /api/reservations/{id}/cancel`
- `GET/POST /api/feedbacks`, `GET /api/admin/stats` (solo amministratore)

Utenti ed eventi hanno una relazione molti-a-molti tramite le prenotazioni. Un feedback
e associato a un solo contenuto o evento; l'autore e ricavato dal token, non dal JSON
del client. Se l'utente viene cancellato dal DB, il feedback resta con autore nullo.
Il modello completo e in [schema.sql](../schema.sql).

Tutte le tabelle usano `INTEGER GENERATED ALWAYS AS IDENTITY`: gli ID sono numeri
generati dal database e JSON numerici nelle API. Ogni tabella ha una sequenza
indipendente che parte da 1. Cancellazioni e inserimenti falliti possono lasciare
buchi nella numerazione; gli ID non sono un contatore dei record.

Per un database precedente con UUID, fermare l'applicazione, creare un backup e
applicare una sola volta [migrate_numeric_ids.sql](../migrate_numeric_ids.sql).
Lo script assegna gli ID per data di creazione (UUID come criterio di spareggio),
conserva i dati e rimappa tutte le chiavi esterne in una transazione. Non eseguirlo
su database gia convertiti; per nuove installazioni usare solo lo schema principale.
I vecchi URL con UUID non sono piu validi: ricaricare la pagina e accedere nuovamente.

La creazione di una prenotazione blocca la riga dell'evento con `FOR UPDATE` nella
stessa transazione della verifica e dell'inserimento. `PENDING` e `CONFIRMED` occupano
posti; `CANCELLED` no. L'indice univoco parziale impedisce piu prenotazioni attive per
utente/evento, ma permette una nuova prenotazione dopo l'annullamento. I conflitti di
capienza o duplicazione restituiscono HTTP 409. La disponibilita mostrata e informativa:
il server la ricontrolla sempre al momento della conferma.

La dashboard conta tutte le prenotazioni, incluse le annullate; l'occupazione considera
solo quelle attive. Un evento con capienza zero ha occupazione zero. Le date sono
salvate con fuso orario e mostrate nel fuso locale del browser. Il prezzo e informativo:
non viene incassato alcun pagamento.

## Test automatici

Dalla cartella `backend`, con Java 21 disponibile:

```powershell
mvn test
```

Comprende test JWT, controllo della capienza e parsing completo della specifica OpenAPI.
Comprende anche il test della migrazione UUID verso ID numerici su uno schema isolato.
I test HTTP/PostgreSQL sono abilitati solo se `TEST_DATABASE_PASSWORD` e impostata.
Usano uno schema temporaneo dal nome casuale e lo eliminano alla fine; non alterano le
tabelle dell'applicazione. L'utente di test deve poter creare schemi. In caso di interruzione
forzata, verificare manualmente l'eventuale schema residuo `tesi_test_...` prima di rimuoverlo.

```powershell
$securePassword = Read-Host "Password PostgreSQL per i test" -AsSecureString
$env:TEST_DATABASE_PASSWORD = [Net.NetworkCredential]::new("", $securePassword).Password
try { mvn test } finally { Remove-Item Env:TEST_DATABASE_PASSWORD }
```

Opzionali: `TEST_DATABASE_URL` e `TEST_DATABASE_USERNAME`, con gli stessi valori di
default locali della configurazione principale. `mvn package` produce il JAR eseguibile.
I test verificano autenticazione, ruoli, input errati, capienza, isolamento tra utenti,
annullamento/nuova prenotazione, feedback, statistiche e riesecuzione dello schema.
Due richieste HTTP concorrenti sull'ultimo posto devono produrre esattamente un 201 e un 409.

## Scenari funzionali per la dimostrazione

| Scenario | Risultato atteso |
|---|---|
| Aprire la home senza login | Catalogo, eventi e feedback accessibili; gestione non visibile |
| Cercare un contenuto e aprirne i dettagli | Risultati filtrati, dati dell'opera e feedback |
| Registrarsi e accedere | Menu personale; nessuna possibilita di scegliere un ruolo |
| Prenotare uno o piu posti | Conferma e presenza nell'elenco personale |
| Prenotare lo stesso evento due volte | Conflitto esplicito, nessuna seconda prenotazione attiva |
| Richiedere piu posti dei disponibili | Rifiuto senza inserimento |
| Annullare e prenotare di nuovo | Annullamento confermato e nuova prenotazione consentita |
| Inviare un feedback | Valutazione e commento nel dettaglio del contenuto/evento |
| Accedere come operatore | Creazione di eventi/contenuti; niente dashboard o utenti |
| Accedere come amministratore | Utenti, prenotazioni globali e indicatori di occupazione |
| Cambiare il ruolo di un altro utente | Solo amministratore; permessi aggiornati immediatamente |
| Tentare di cambiare il proprio ruolo | Conflitto esplicito, ruolo invariato |
| Usare uno schermo da 390 px | Moduli e schede utilizzabili senza overflow della pagina |
| Token scaduto o server non raggiungibile | Errore visibile; nessun messaggio di successo ingannevole |

La verifica del browser e stata eseguita con risposte API simulate per coprire i
controlli di interfaccia. Separatamente i test HTTP hanno usato il vero backend e
PostgreSQL in uno schema isolato. Ripetere gli scenari sul proprio avvio locale prima
di acquisire gli screenshot definitivi dell'elaborato.

## Limiti e sviluppi futuri

Questo e un MVP locale, non un servizio pronto per la produzione. Mancano modifica/
eliminazione di contenuti ed eventi, recupero password, verifica email, moderazione
feedback, pagamenti, notifiche, audit ed export. Gli elenchi di
eventi, feedback e utenti sono adatti a un piccolo prototipo; su volumi maggiori servono
paginazione e limiti ulteriori. La ricerca attuale usa filtri testuali, non l'indice
full-text predisposto nel DB. Il tracciamento delle visite non rientra nelle
funzionalita richieste e resta un possibile sviluppo analytics futuro.

Prima di pubblicare: aggiornare e verificare le dipendenze, usare HTTPS e un utente DB
con privilegi minimi, definire backup e conservazione dei dati, introdurre rate limiting
e verificare privacy e protezione dei dati. Report di tesi, scelta dell'organizzazione
definitiva e screenshot finali richiedono ancora la revisione dello studente.
