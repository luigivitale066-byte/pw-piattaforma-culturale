"use strict";

const $ = (selector, root = document) => root.querySelector(selector);
const state = { token: null, user: null, events: [], view: "events", page: 0, register: false };
const pageSize = 9;
const dateFormat = new Intl.DateTimeFormat("it-IT", { dateStyle: "medium", timeStyle: "short" });
const money = new Intl.NumberFormat("it-IT", { style: "currency", currency: "EUR" });
const statusLabels = { PENDING: "In attesa", CONFIRMED: "Confermata", CANCELLED: "Annullata" };
const roleLabels = { ROLE_USER: "Visitatore", ROLE_OPERATOR: "Operatore", ROLE_ADMIN: "Amministratore" };
const messages = {
  "Invalid username or password": "Username o password non corretti.",
  "Username or email is already registered": "Username o email sono gia registrati.",
  "Not enough seats available": "Non ci sono abbastanza posti disponibili.",
  "An active reservation already exists for this event": "Hai gia una prenotazione attiva per questo evento.",
  "Invalid or expired token": "Sessione scaduta. Accedi nuovamente.",
  "Authentication required": "Accedi per continuare.",
  "Access denied": "Non hai i permessi per questa operazione.",
  "You cannot change your own role": "Non puoi modificare il tuo stesso ruolo.",
  "User not found": "Utente non trovato.",
  "Active reservation not found for this user": "La prenotazione non e piu attiva o non ti appartiene."
};

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined && text !== null) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function button(text, action, className = "secondary") {
  const node = element("button", text, className);
  node.type = "button";
  node.addEventListener("click", () => run(() => action(node)));
  return node;
}

function notify(message, error = false) {
  $("#notice-text").textContent = message;
  $("#notice").classList.toggle("error", error);
  $("#notice").hidden = false;
  if (error && $("#detail-dialog").open) {
    let inlineError = $(".form-error", $("#detail-content"));
    if (!inlineError) {
      inlineError = element("p", null, "form-error");
      inlineError.setAttribute("role", "alert");
      $("#detail-content").append(inlineError);
    }
    inlineError.textContent = message;
  }
}

async function run(action) {
  try {
    await action();
  } catch (error) {
    console.error(error);
    notify(error.message, true);
  }
}

async function api(path, options = {}) {
  const headers = { Accept: "application/json" };
  if (state.token) headers.Authorization = `Bearer ${state.token}`;
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  let response;
  try {
    response = await fetch(`/api${path}`, {
      ...options, headers, body: options.body === undefined ? undefined : JSON.stringify(options.body)
    });
  } catch (error) {
    throw new Error("Impossibile raggiungere il server. Controlla che il backend sia avviato.", { cause: error });
  }
  if (!response.ok) {
    let detail = "Richiesta non riuscita.";
    if (response.headers.get("content-type")?.includes("application/json")) {
      const payload = await response.json();
      detail = payload.message || detail;
    }
    if (response.status === 401 && state.token) {
      clearSession();
      detail = "Sessione scaduta. Accedi nuovamente.";
    }
    throw new Error(messages[detail] || (response.status >= 500
      ? "Errore del server. Riprova; se persiste, controlla i log del backend." : detail));
  }
  return response.status === 204 ? null : response.json();
}

const TOKEN_KEY = "trame_token";

function clearSession() {
  localStorage.removeItem(TOKEN_KEY);
  state.token = null;
  state.user = null;
  renderAccount();
  $("#detail-dialog").close();
  if (["reservations", "manage", "dashboard"].includes(state.view)) {
    showView("events");
  }
  $("#reservation-list").replaceChildren();
  $("#user-list").replaceChildren();
  $("#stats").replaceChildren();
  $("#occupancy").replaceChildren();
}

function renderAccount() {
  const account = $("#account");
  account.replaceChildren();
  if (state.user) {
    account.append(element("span", `${state.user.username} · ${roleLabels[state.user.role]}`),
      button("Esci", () => { clearSession(); notify("Hai effettuato l'uscita."); }, "text-button"));
  } else {
    account.append(button("Accedi", () => openAuth(false), "text-button"),
      button("Registrati", () => openAuth(true), "primary"));
  }
  for (const nav of document.querySelectorAll("[data-view]")) {
    const view = nav.dataset.view;
    nav.hidden = (view === "reservations" && !state.user)
      || (view === "manage" && !["ROLE_ADMIN", "ROLE_OPERATOR"].includes(state.user?.role))
      || (view === "dashboard" && state.user?.role !== "ROLE_ADMIN");
  }
}

function showView(view) {
  if (view === "reservations" && !state.user) return openAuth(false);
  if (view === "manage" && !["ROLE_ADMIN", "ROLE_OPERATOR"].includes(state.user?.role)) return;
  if (view === "dashboard" && state.user?.role !== "ROLE_ADMIN") return;
  state.view = view;
  for (const section of document.querySelectorAll(".view")) section.hidden = section.id !== view;
  for (const nav of document.querySelectorAll("[data-view]")) {
    if (nav.dataset.view === view) nav.setAttribute("aria-current", "page");
    else nav.removeAttribute("aria-current");
  }
  $(`#${view}-title`).focus({ preventScroll: true });
  if (view === "catalog") return run(loadCatalog);
  if (view === "events") return run(loadEvents);
  if (view === "reservations") return run(loadReservations);
  if (view === "dashboard") return run(loadDashboard);
}

function openAuth(register) {
  state.register = register;
  const form = $("#auth-form");
  form.reset();
  $("#auth-error").textContent = "";
  $("#auth-title").textContent = register ? "Entra nella nostra comunita" : "Accedi al tuo spazio";
  $("#auth-submit").textContent = register ? "Crea account" : "Accedi";
  $("#toggle-auth").textContent = register ? "Hai gia un account? Accedi" : "Non hai un account? Registrati";
  $("#email-field").hidden = !register;
  form.elements.email.required = register;
  form.elements.email.disabled = !register;
  form.elements.password.minLength = register ? 8 : 1;
  form.elements.password.maxLength = 72;
  form.elements.password.autocomplete = register ? "new-password" : "current-password";
  $("#password-help").hidden = !register;
  if (!$("#auth-dialog").open) $("#auth-dialog").showModal();
}

function loading(target) {
  target.replaceChildren(element("p", "Caricamento in corso...", "loading"));
}

function empty(target, message) {
  target.replaceChildren(element("p", message, "empty"));
}

function date(value) { return value ? dateFormat.format(new Date(value)) : "Non indicata"; }
function shortDescription(text) { return text ? (text.length > 160 ? `${text.slice(0, 157)}...` : text) : "Scopri tutti i dettagli."; }

function card(title, subtitle, kind, action) {
  const node = element("article", null, "card");
  const art = element("div", null, `card-art ${kind === "catalog" ? "catalog-art" : ""}`);
  art.setAttribute("aria-hidden", "true");
  art.append(element("span", title.slice(0, 1).toUpperCase(), "art-letter"), element("span", subtitle, "badge"));
  const body = element("div", null, "card-body");
  body.append(element("h3", title));
  node.append(art, body);
  if (action) body.append(button("Scopri di piu", action));
  return { node, body };
}

let eventRequest = 0;
async function loadEvents() {
  const version = ++eventRequest;
  const target = $("#event-list");
  loading(target);
  const form = $("#event-filter");
  const params = new URLSearchParams();
  if (form.elements.from.value) params.set("from", new Date(`${form.elements.from.value}T00:00:00`).toISOString());
  if (form.elements.to.value) params.set("to", new Date(`${form.elements.to.value}T23:59:59.999`).toISOString());
  try {
    const events = await api(`/events?${params}`);
    if (version !== eventRequest) return;
    state.events = events;
    target.replaceChildren();
    if (!events.length) return empty(target, "Nessun evento in questo periodo. Torna presto o cambia le date.");
    for (const event of events) {
      const { node, body } = card(event.title, "EVENTO", "events");
      body.append(element("p", date(event.startDatetime), "muted"),
        element("p", shortDescription(event.description), "card-description"));
      const meta = element("div", null, "card-meta");
      meta.append(element("span", `${event.availableSeats} posti disponibili`),
        element("strong", event.price === 0 ? "Gratuito" : money.format(event.price)));
      body.append(meta, button("Dettagli e prenotazione", () => openDetail("event", event.id)));
      target.append(node);
    }
  } catch (error) {
    if (version === eventRequest) empty(target, "Non e stato possibile caricare gli eventi. Usa il filtro per riprovare.");
    throw error;
  }
}

let catalogRequest = 0;
async function loadCatalog() {
  const version = ++catalogRequest;
  const target = $("#catalog-list");
  loading(target);
  $("#previous").disabled = true;
  $("#next").disabled = true;
  const data = new FormData($("#catalog-filter"));
  const params = new URLSearchParams({ query: data.get("query"), category: data.get("category"),
    page: state.page, size: pageSize });
  try {
    const items = await api(`/catalog?${params}`);
    if (version !== catalogRequest) return;
    target.replaceChildren();
    $("#previous").disabled = state.page === 0;
    $("#next").disabled = items.length < pageSize;
    $("#page-number").textContent = `Pagina ${state.page + 1}`;
    if (!items.length) return empty(target, "Nessun contenuto trovato in questa pagina. Modifica i filtri o torna alla precedente.");
    for (const item of items) {
      const { node, body } = card(item.title, item.category || "CATALOGO", "catalog");
      body.append(element("p", [item.author, item.year].filter(value => value != null && value !== "").join(" · "), "muted"),
        element("p", shortDescription(item.description), "card-description"),
        button("Esplora il contenuto", () => openDetail("item", item.id)));
      target.append(node);
    }
  } catch (error) {
    if (version === catalogRequest) empty(target, "Catalogo non disponibile. Usa la ricerca per riprovare.");
    throw error;
  }
}

function labeled(label, input) {
  const node = element("label", label);
  node.append(input);
  return node;
}

let detailRequest = 0;
async function openDetail(kind, id) {
  const version = ++detailRequest;
  const dialog = $("#detail-dialog");
  const target = $("#detail-content");
  loading(target);
  dialog.showModal();
  const [item, feedbacks] = await Promise.all([
    api(`/${kind === "event" ? "events" : "catalog"}/${id}`),
    api(`/feedbacks?${kind === "event" ? "eventId" : "itemId"}=${id}`)
  ]);
  if (!dialog.open || version !== detailRequest) return;
  target.replaceChildren();
  const title = element("h2", item.title);
  title.id = "detail-title";
  target.append(element("p", kind === "event" ? "EVENTO E VISITA" : item.category || "CATALOGO", "eyebrow"), title,
    element("p", item.description || "Descrizione non disponibile.", "detail-description"));
  if (kind === "event") {
    target.append(element("p", `Inizio: ${date(item.startDatetime)}`));
    if (item.endDatetime) target.append(element("p", `Fine: ${date(item.endDatetime)}`));
    target.append(element("p", `${item.availableSeats} posti disponibili su ${item.capacity} · ${money.format(item.price)} a persona`));
    if (state.user && item.availableSeats > 0) {
      const form = element("form", null, "field-row detail-section");
      const seats = element("input");
      Object.assign(seats, { type: "number", min: "1", max: String(item.availableSeats), step: "1", value: "1", required: true });
      const submit = element("button", "Conferma prenotazione", "primary");
      submit.type = "submit";
      form.append(labeled("Numero di posti", seats), submit);
      form.addEventListener("submit", event => {
        event.preventDefault();
        run(async () => {
          submit.disabled = true;
          try {
            await api(`/events/${id}/reservations`, { method: "POST", body: { seats: Number(seats.value) } });
            dialog.close();
            notify("Prenotazione confermata! La trovi nella sezione Prenotazioni.");
            await loadEvents();
          } finally { submit.disabled = false; }
        });
      });
      target.append(form);
    } else if (!state.user) {
      target.append(button("Accedi per prenotare", () => { dialog.close(); openAuth(false); }, "primary"));
    } else target.append(element("p", "Al momento i posti sono esauriti.", "badge"));
  } else {
    target.append(element("p", [item.author, item.year].filter(value => value != null && value !== "").join(" · "), "muted"));
    if (item.mediaUrl && /^https?:\/\//i.test(item.mediaUrl)) {
      const link = element("a", "Apri il contenuto multimediale");
      Object.assign(link, { href: item.mediaUrl, target: "_blank", rel: "noopener noreferrer" });
      target.append(link);
    }
  }
  const section = element("section", null, "detail-section");
  section.append(element("h3", "Le voci dei visitatori"));
  if (feedbacks.length) {
    for (const feedback of feedbacks) {
      const review = element("article", null, "feedback");
      review.append(element("strong", `Valutazione: ${feedback.rating ?? "non indicata"} / 5`),
        element("p", feedback.comment || "Nessun commento."),
        element("small", date(feedback.createdAt), "muted"));
      section.append(review);
    }
  } else section.append(element("p", "Nessun feedback. Condividi per primo la tua esperienza.", "muted"));
  if (state.user) {
    const form = element("form", null, "stack detail-section");
    const rating = element("select");
    for (let value = 5; value >= 1; value--) {
      const option = element("option", `${value} / 5`);
      option.value = value;
      rating.append(option);
    }
    const comment = element("textarea");
    Object.assign(comment, { rows: 3, maxLength: 5000 });
    const submit = element("button", "Invia feedback", "primary");
    submit.type = "submit";
    form.append(labeled("La tua valutazione", rating), labeled("Commento (facoltativo)", comment), submit);
    form.addEventListener("submit", event => {
      event.preventDefault();
      run(async () => {
        submit.disabled = true;
        try {
          await api("/feedbacks", { method: "POST", body: {
            [kind === "event" ? "eventId" : "itemId"]: id,
            rating: Number(rating.value), comment: comment.value.trim() || null
          } });
          notify("Grazie! Il tuo feedback e stato pubblicato.");
          dialog.close();
          await openDetail(kind, id);
        } finally { submit.disabled = false; }
      });
    });
    section.append(form);
  } else section.append(element("p", "Accedi per lasciare un feedback.", "muted"));
  target.append(section);
}

async function confirmCancellation() {
  const dialog = $("#confirm-dialog");
  dialog.returnValue = "no";
  dialog.showModal();
  return new Promise(resolve => dialog.addEventListener("close", () => resolve(dialog.returnValue === "yes"), { once: true }));
}

async function loadReservations() {
  const target = $("#reservation-list");
  const user = state.user;
  loading(target);
  try {
    const [reservations, events] = await Promise.all([api("/reservations"), api("/events")]);
    if (state.user !== user) return;
    const names = new Map(events.map(event => [event.id, event.title]));
    const isStaff = ["ROLE_ADMIN", "ROLE_OPERATOR"].includes(user.role);
    $("#reservation-help").textContent = isStaff
      ? "Vista staff: tutte le prenotazioni con il nome di chi ha prenotato."
      : "I tuoi posti riservati, tutti in un unico spazio.";
    target.replaceChildren();
    if (!reservations.length) return empty(target, "Non ci sono ancora prenotazioni. Scegli un evento per cominciare.");
    for (const reservation of reservations) {
      const { node, body } = card(names.get(reservation.eventId) || "Evento", "PRENOTAZIONE", "events");
      body.append(element("span", statusLabels[reservation.status], `badge ${reservation.status === "CANCELLED" ? "cancelled" : ""}`),
        element("p", `${reservation.seats} posti · ${date(reservation.createdAt)}`, "muted"));
      if (isStaff) body.append(element("strong", `Prenotato da: ${reservation.username}`));
      const canCancel = user.role === "ROLE_ADMIN" || reservation.userId === user.id;
      if (reservation.status !== "CANCELLED" && canCancel) body.append(button("Annulla prenotazione", async node => {
        if (!await confirmCancellation()) return;
        node.disabled = true;
        try {
          await api(`/reservations/${reservation.id}/cancel`, { method: "PUT" });
          notify("Prenotazione annullata.");
          await loadReservations();
        } finally { node.disabled = false; }
      }, "text-button"));
      target.append(node);
    }
  } catch (error) {
    empty(target, "Impossibile caricare le prenotazioni. Premi Aggiorna per riprovare.");
    throw error;
  }
}

async function loadDashboard() {
  const user = state.user;
  loading($("#stats"));
  $("#user-list").replaceChildren();
  $("#occupancy").replaceChildren();
  try {
    const [stats, users, events] = await Promise.all([api("/admin/stats"), api("/users"), api("/events")]);
    if (state.user !== user) return;
    $("#stats").replaceChildren();
    for (const [label, value] of [["Utenti registrati", stats.totalUsers], ["Prenotazioni totali", stats.totalReservations], ["Eventi in programma", events.length]]) {
      const panel = element("div", null, "panel stat");
      panel.append(element("strong", value), element("span", label, "muted"));
      $("#stats").append(panel);
    }
    const names = new Map(events.map(event => [event.id, event.title]));
    if (!stats.eventsOccupancy.length) $("#occupancy").append(element("p", "Non ci sono ancora eventi.", "muted"));
    for (const entry of stats.eventsOccupancy) {
      const row = element("div", null, "occupancy-row");
      const title = names.get(entry.eventId) || entry.eventId;
      const progress = element("progress");
      progress.max = 100;
      progress.value = entry.occupancyPct;
      progress.setAttribute("aria-label", `Occupazione ${title}`);
      row.append(element("strong", title), progress, element("span", `${entry.occupancyPct}%`));
      $("#occupancy").append(row);
    }
    for (const userEntry of users) {
      const row = element("tr");
      for (const value of [userEntry.username, userEntry.email, roleLabels[userEntry.role], date(userEntry.createdAt)]) row.append(element("td", value));
      const actions = element("td");
      if (userEntry.id === user.id) {
        actions.append(element("span", "Il tuo account (ruolo non modificabile)", "muted"));
      } else {
        const form = element("form", null, "stack");
        const select = element("select");
        select.setAttribute("aria-label", `Ruolo di ${userEntry.username}`);
        for (const [value, label] of Object.entries(roleLabels)) {
          const option = element("option", label);
          option.value = value;
          select.append(option);
        }
        select.value = userEntry.role;
        const submit = element("button", "Salva ruolo", "secondary");
        submit.type = "submit";
        submit.disabled = true;
        select.addEventListener("change", () => { submit.disabled = select.value === userEntry.role; });
        form.append(select, submit);
        form.addEventListener("submit", event => {
          event.preventDefault();
          run(async () => {
            select.disabled = true;
            submit.disabled = true;
            try {
              const updated = await api(`/admin/users/${userEntry.id}/role`, {
                method: "PUT", body: { role: select.value }
              });
              userEntry.role = updated.role;
              row.cells[2].textContent = roleLabels[updated.role];
              select.value = updated.role;
              notify(`Ruolo di ${updated.username} aggiornato: ${roleLabels[updated.role]}.`);
            } finally {
              select.disabled = false;
              submit.disabled = select.value === userEntry.role;
            }
          });
        });
        actions.append(form);
      }
      row.append(actions);
      $("#user-list").append(row);
    }
  } catch (error) {
    empty($("#stats"), "Dashboard non disponibile. Premi Aggiorna per riprovare.");
    throw error;
  }
}

$("#auth-form").addEventListener("submit", event => {
  event.preventDefault();
  run(async () => {
    const form = event.target;
    const submit = $("#auth-submit");
    const body = Object.fromEntries(new FormData(form));
    $("#auth-error").textContent = "";
    submit.disabled = true;
    try {
      if (state.register) {
        if (new TextEncoder().encode(body.password).length > 72) throw new Error("La password deve occupare al massimo 72 byte UTF-8.");
        await api("/auth/register", { method: "POST", body });
      }
      const auth = await api("/auth/login", { method: "POST", body: { username: body.username, password: body.password } });
      state.token = auth.token;
      localStorage.setItem(TOKEN_KEY, auth.token);
      try { state.user = await api("/users/me"); }
      catch (error) { clearSession(); throw error; }
      $("#auth-dialog").close();
      form.reset();
      renderAccount();
      notify(`Benvenuto, ${state.user.username}!`);
    } catch (error) {
      $("#auth-error").textContent = error.message;
      throw error;
    } finally { submit.disabled = false; }
  });
});

function createForm(selector, endpoint, build, success) {
  $(selector).addEventListener("submit", event => {
    event.preventDefault();
    const form = event.target;
    run(async () => {
      const submit = $('button[type="submit"]', form);
      submit.disabled = true;
      try {
        await api(endpoint, { method: "POST", body: build(Object.fromEntries(new FormData(form))) });
        form.reset();
        notify(success);
      } finally { submit.disabled = false; }
    });
  });
}

createForm("#create-event", "/events", data => {
  const start = new Date(data.startDatetime);
  const end = data.endDatetime ? new Date(data.endDatetime) : null;
  if (end && end < start) throw new Error("La fine non puo precedere l'inizio dell'evento.");
  return { ...data, capacity: Number(data.capacity), price: Number(data.price),
    startDatetime: start.toISOString(), endDatetime: end?.toISOString() || null };
}, "Evento pubblicato. E disponibile nella sezione Eventi e visite.");
createForm("#create-item", "/catalog", data => ({ ...data, year: data.year ? Number(data.year) : null,
  mediaUrl: data.mediaUrl || null }), "Contenuto aggiunto al catalogo.");

for (const nav of document.querySelectorAll("[data-view]")) nav.addEventListener("click", () => showView(nav.dataset.view));
for (const close of document.querySelectorAll("[data-close]")) close.addEventListener("click", () => close.closest("dialog").close());
$("#notice button").addEventListener("click", () => { $("#notice").hidden = true; });
$("#toggle-auth").addEventListener("click", () => openAuth(!state.register));
$("#explore").addEventListener("click", () => { showView("events"); $("#events").scrollIntoView({ block: "start" }); });
$("#event-filter").addEventListener("submit", event => { event.preventDefault(); run(loadEvents); });
$("#event-filter").addEventListener("reset", () => { setTimeout(() => run(loadEvents), 0); });
$("#catalog-filter").addEventListener("submit", event => { event.preventDefault(); state.page = 0; run(loadCatalog); });
$("#catalog-filter").addEventListener("reset", () => { state.page = 0; setTimeout(() => run(loadCatalog), 0); });
$("#previous").addEventListener("click", () => { state.page = Math.max(0, state.page - 1); run(loadCatalog); });
$("#next").addEventListener("click", () => { state.page++; run(loadCatalog); });
$("#refresh-reservations").addEventListener("click", () => run(loadReservations));
$("#refresh-dashboard").addEventListener("click", () => run(loadDashboard));
async function restoreSession() {
  const token = localStorage.getItem(TOKEN_KEY);
  if (!token) return;
  state.token = token;
  try {
    state.user = await api("/users/me");
  } catch (error) {
    // 401 già gestito da api(); per altri errori si scarta il token salvato
    if (state.token) clearSession();
  }
  renderAccount();
}

renderAccount();
restoreSession();
run(loadEvents);
