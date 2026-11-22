// « Révisions d'anglais » — onglet « Ajouter » (seulement dans l'appli Android).
// Chargé par l'APK après la page principale : il en réutilise les variables (W, view, render…).
// Traduction et voix fournies par l'appli Android (objet AndroidApp).
(function () {
  if (!window.AndroidApp) return;

  const AKEY = "vocab-added-v1";
  const key = s => String(s || "").toLowerCase().replace(/\s+/g, " ").trim();
  let added = [];
  try { added = JSON.parse(localStorage.getItem(AKEY)) || []; } catch (e) { added = []; }
  const saveAdded = () => { try { localStorage.setItem(AKEY, JSON.stringify(added)); } catch (e) {} };

  // Mots déjà arrivés par Claude (dans DATA) : la copie du téléphone n'est plus utile.
  const official = new Set(DATA.map(d => key(d[0])));
  const before = added.length;
  added = added.filter(a => {
    if (!official.has(key(a.en))) return true;
    try { AndroidApp.dropLocal(audioSlug(a.en)); } catch (e) {}
    return false;
  });
  if (added.length !== before) saveAdded();

  function rebuild() {
    // W = mots officiels + mots ajoutés ici, par ordre alphabétique (w.i = position dans W).
    const base = W.filter(w => !w.local);
    const extra = added.map(a => ({ en: a.en, fr: a.fr, mine: a.mine || "", mine0: a.mine || "", note: a.note || "", local: true }));
    const all = base.concat(extra).sort((a, b) => a.en.localeCompare(b.en, "en", { sensitivity: "base" }));
    W.length = 0;
    all.forEach((w, i) => { w.i = i; W.push(w); });
    applyMine();
  }

  // Voix du téléphone : si elle a changé (meilleure voix choisie), on refait les sons des mots ajoutés.
  const VKEY = "vocab-voice-v1";
  function voiceInfo() { try { return String(AndroidApp.voiceInfo() || ""); } catch (e) { return ""; } }
  function resynthIfNeeded(tries) {
    const v = voiceInfo();
    if (!v) { if (tries > 0) setTimeout(() => resynthIfNeeded(tries - 1), 700); return; }
    let old = ""; try { old = localStorage.getItem(VKEY) || ""; } catch (e) {}
    if (old === v) return;
    added.forEach(a => { try { AndroidApp.synth(a.en, audioSlug(a.en)); } catch (e) {} });
    try { localStorage.setItem(VKEY, v); } catch (e) {}
    if (view === "add") render();
  }
  setTimeout(() => resynthIfNeeded(10), 500);

  // Voix du téléphone quand un son manque.
  ttsFallback = t => { try { AndroidApp.speak(String(t)); } catch (e) {} };

  // ---------- Onglet ----------
  const tabs = document.getElementById("tabs");
  const tb = document.createElement("button");
  tb.dataset.tab = "add";
  tb.textContent = "+ Ajouter";
  tabs.appendChild(tb);

  const st = document.createElement("style");
  st.textContent = `
    #tabs{grid-template-columns:repeat(4,auto);width:min(400px,calc(100% - 24px))}
    #tabs button{font-size:.84rem;padding:11px 8px;white-space:nowrap}
    .addf input{margin-bottom:10px}
    .sugg{display:flex;flex-wrap:wrap;gap:6px;margin:4px 0 12px}
    .sugg button{border:0;border-radius:999px;padding:7px 13px;font:inherit;font-size:.9rem;font-weight:600;background:var(--track);color:var(--ink)}
    .sugg button.on{background:var(--t-blue);color:#fff}
    .addmsg{margin:10px 0 0;font-weight:600}
    .addmsg.ok{color:var(--good)} .addmsg.ko{color:var(--bad)}
    .two{display:flex;gap:8px} .two .btn{flex:1}
    .loc{display:inline-block;font-size:.72rem;font-weight:700;color:var(--accent);background:var(--accent-soft);border-radius:999px;padding:1px 8px;margin-left:6px;vertical-align:middle}
    .del{border:0;background:none;color:var(--muted);font-size:1.1rem;padding:4px 8px}
  `;
  document.head.appendChild(st);

  const F = { en: "", fr: "", mine: "", note: "", alts: [], busy: false, msg: "", ok: true };

  function renderAdd() {
    const mineList = added.slice().sort((a, b) => a.en.localeCompare(b.en, "en", { sensitivity: "base" }));
    return `<section class="panel addf">
      <div class="label" style="margin-top:0">Mot ou expression en anglais</div>
      <form id="trform" autocomplete="off">
        <input type="text" id="a-en" value="${esc(F.en)}" placeholder="ex. : take for granted" autocapitalize="off" spellcheck="false">
        <button class="btn main" type="submit" ${F.busy ? "disabled" : ""}>${F.busy ? "Traduction…" : "Traduire"}</button>
      </form>
      ${F.alts.length ? `<div class="label">Propositions (touche pour choisir)</div>
        <div class="sugg">${F.alts.map((a, k) => `<button data-alt="${k}" class="${key(a) === key(F.fr) ? "on" : ""}">${esc(a)}</button>`).join("")}</div>` : ""}
      <div class="label">Traduction</div>
      <input type="text" id="a-fr" value="${esc(F.fr)}" placeholder="traduction en français">
      <div class="label">Ma traduction (facultatif)</div>
      <input type="text" id="a-mine" value="${esc(F.mine)}" placeholder="ta propre traduction">
      <div class="label">Remarque (facultatif)</div>
      <input type="text" id="a-note" value="${esc(F.note)}" placeholder="exemple, autre sens…">
      <div class="two" style="margin-top:6px">
        <button class="btn ghost" data-a-listen>🔊 Écouter</button>
        <button class="btn main" data-a-save>Ajouter à mes mots</button>
      </div>
      ${F.msg ? `<div class="addmsg ${F.ok ? "ok" : "ko"}">${esc(F.msg)}</div>` : ""}
      ${voiceLine()}
    </section>
    <section class="panel">
      <div class="label" style="margin-top:0">Ajoutés sur le téléphone (${added.length})</div>
      ${mineList.length ? mineList.map(a => `<div class="row"><div class="t"><b class="say" data-speak="${esc(a.en)}">${esc(a.en)}</b><div class="fr">${esc(a.fr)}</div>${a.mine ? `<div class="mn">Ta traduction : <em>${esc(a.mine)}</em></div>` : ""}</div>
          <button class="del" data-a-del="${esc(a.en)}" aria-label="Supprimer">✕</button></div>`).join("")
        : `<div class="empty">Aucun mot ajouté ici pour l'instant.</div>`}
      ${mineList.length ? `<button class="btn ghost" data-a-share style="margin-top:12px">Envoyer la liste à Claude</button>
        <div class="small" style="margin-top:8px">Pour les mettre aussi dans le tableau « Vocabulaire anglais » et l'appli Claude : envoie la liste dans une conversation avec Claude.</div>` : ""}
    </section>`;
  }

  function voiceLine() {
    const v = voiceInfo(); if (!v) return "";
    const [eng, name] = v.split("|");
    if (eng !== "Google") return `<div class="small" style="margin-top:12px">Voix du téléphone : ${esc(eng)}. Pour une voix plus naturelle, installe « Services de synthèse vocale de Google » (Speech Services by Google) depuis le Play Store, puis relance l'appli.</div>`;
    return `<div class="small" style="margin-top:12px">Voix : Google ${name && name.endsWith("-network") ? "en ligne (la plus naturelle)" : "du téléphone"}.</div>`;
  }

  function readForm() {
    const g = id => { const el = document.getElementById(id); return el ? el.value : null; };
    const en = g("a-en"); if (en !== null) F.en = en;
    const fr = g("a-fr"); if (fr !== null) F.fr = fr;
    const mi = g("a-mine"); if (mi !== null) F.mine = mi;
    const no = g("a-note"); if (no !== null) F.note = no;
  }

  const baseRender = render;
  render = function () {
    if (view !== "add") { tb.classList.remove("on"); return baseRender(); }
    document.querySelectorAll("#tabs button").forEach(b => b.classList.toggle("on", b === tb));
    document.querySelector(".wrap").classList.remove("wide");
    $app.innerHTML = renderAdd();
  };

  // ---------- Traduction (réponse de l'appli Android) ----------
  let reqId = 0;
  window.__ajoutTr = function (id, json) {
    if (id !== reqId) return;
    F.busy = false;
    let r = null; try { r = JSON.parse(json); } catch (e) {}
    if (!r || !r.alts || !r.alts.length) {
      F.msg = "Pas de traduction trouvée (Internet ?). Tu peux l'écrire toi-même."; F.ok = false;
    } else {
      F.alts = r.alts; F.fr = r.alts[0]; F.msg = ""; F.ok = true;
    }
    if (view === "add") render();
  };

  function translate() {
    readForm();
    const en = F.en.trim();
    if (!en) return;
    F.busy = true; F.alts = []; F.msg = ""; reqId++;
    render();
    try { AndroidApp.translate(reqId, en); } catch (e) { F.busy = false; render(); }
  }

  function exists(en) { return W.some(w => key(w.en) === key(en)); }

  function save() {
    readForm();
    const en = F.en.trim().replace(/\s+/g, " ");
    const fr = F.fr.trim();
    if (!en) { F.msg = "Écris d'abord le mot anglais."; F.ok = false; return render(); }
    if (!fr && !F.mine.trim()) { F.msg = "Ajoute une traduction."; F.ok = false; return render(); }
    if (exists(en)) { F.msg = `« ${en} » est déjà dans tes mots.`; F.ok = false; return render(); }
    const word = en.charAt(0).toUpperCase() + en.slice(1);
    added.push({ en: word, fr: fr || F.mine.trim(), mine: F.mine.trim(), note: F.note.trim(), t: Date.now() });
    saveAdded();
    try { AndroidApp.synth(word, audioSlug(word)); } catch (e) {}
    S = null; rebuild();
    Object.assign(F, { en: "", fr: "", mine: "", note: "", alts: [], msg: `« ${word} » ajouté à tes mots.`, ok: true });
    render();
  }

  function share() {
    const lines = added.slice().sort((a, b) => a.en.localeCompare(b.en, "en", { sensitivity: "base" }))
      .map(a => `- ${a.en} : ${a.fr}${a.mine ? ` (ma traduction : ${a.mine})` : ""}${a.note ? ` — ${a.note}` : ""}`);
    const text = "Ajoute ces mots à mon vocabulaire anglais (tableau et appli) :\n" + lines.join("\n");
    try { AndroidApp.share(text); } catch (e) {}
  }

  $app.addEventListener("submit", e => {
    if (e.target.id !== "trform") return;
    e.preventDefault(); translate();
  });
  $app.addEventListener("click", e => {
    if (view !== "add") return;
    const t = e.target.closest("button"); if (!t) return;
    if (t.dataset.alt !== undefined) { readForm(); F.fr = F.alts[+t.dataset.alt]; render(); return; }
    if (t.dataset.aListen !== undefined) { readForm(); if (F.en.trim()) AndroidApp.speak(F.en.trim()); return; }
    if (t.dataset.aSave !== undefined) { save(); return; }
    if (t.dataset.aShare !== undefined) { share(); return; }
    if (t.dataset.aDel !== undefined) {
      const en = t.dataset.aDel;
      added = added.filter(a => a.en !== en); saveAdded();
      try { AndroidApp.dropLocal(audioSlug(en)); } catch (e) {}
      S = null; rebuild(); render(); return;
    }
  });

  // Petit repère « ajouté » dans « Mes mots ».
  const baseList = renderList;
  renderList = function () {
    let html = baseList();
    for (const w of W) if (w.local) {
      const needle = `data-speak="${esc(w.en)}">${esc(w.en)}</b>`;
      html = html.replace(needle, needle + `<span class="loc">ajouté</span>`);
    }
    return html;
  };


  // ---------- Exercice « En contexte » : la phrase la plus courante, avec un trou ----------
  let EX = {};
  fetch("exemples.json").then(r => r.ok ? r.json() : {}).then(d => { EX = d || {}; if (view === "home") render(); }).catch(() => {});
  const hasEx = w => !!EX[w.en];

  const st2 = document.createElement("style");
  st2.textContent = `
    .m-ctx{background:var(--t-green)}
    .ctxs{font-size:1.32rem;line-height:1.45;font-weight:600;margin:6px 0 4px}
    .ctxs .blank{display:inline-block;min-width:84px;border-bottom:3px solid var(--accent);margin:0 2px}
    .ctxs mark{background:var(--accent-soft);color:var(--accent);border-radius:8px;padding:0 5px}
    .ctxfr{margin-top:10px;color:var(--muted);font-size:.95rem;font-style:italic}
    .ctxbtn{border:0;background:none;color:var(--accent);font:inherit;font-weight:600;font-size:.92rem;margin-top:12px;padding:6px}
  `;
  document.head.appendChild(st2);

  const ICON_CTX = '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 6h16M4 12h6M14 12h6M4 18h16"/><path d="M10.5 12h3" stroke-dasharray="1 2"/></svg>';

  const baseHome = renderHome;
  renderHome = function () {
    const html = baseHome();
    const n = W.filter(hasEx).length;
    const btn = `<button class="mode m-ctx" data-start="ctx"><span class="ic">${ICON_CTX}</span><span><b>En contexte</b><span>${n ? "Le mot dans une phrase courante : trouve celui qui manque." : "Chargement des phrases…"}</span></span></button>`;
    const k = html.lastIndexOf("</div>");
    return html.slice(0, k) + btn + html.slice(k);
  };

  const baseStart = start;
  start = function (mode) {
    if (mode !== "ctx") return baseStart(mode);
    const q = buildQueue(W.length).filter(i => hasEx(W[i])).slice(0, settings.size);
    if (!q.length) return;
    S = { mode: "ctx", dir: "en", queue: q, pos: 0, total: q.length, graded: new Set(), right: [], wrong: [],
          answered: false, choice: null, typed: "", okTyped: null, opts: null, showFr: false, played: -1 };
    view = "play"; render();
  };

  function sentence(w, reveal) {
    const ex = EX[w.en][0];
    return esc(ex).replace(/\[(.+?)\]/, (m, g) => reveal ? `<mark>${g}</mark>` : `<span class="blank">&nbsp;</span>`);
  }
  function plain(w) { return EX[w.en][0].replace(/[\[\]]/g, ""); }

  function playSentence(w) {
    const url = "audio/ex-" + audioSlug(w.en) + ".mp3?v=5";
    const text = plain(w);
    const ctx = getCtx();
    if (!ctx) return speakEl(url, text);
    try { if (ctx.state !== "running") ctx.resume(); } catch (e) {}
    const token = ++speakToken;
    loadBuf(url).then(buf => {
      if (token !== speakToken) return;
      stopCur();
      const src = ctx.createBufferSource(); src.buffer = buf;
      const g = ctx.createGain(); const at = ctx.currentTime + 0.03;
      g.gain.setValueAtTime(0, at); g.gain.linearRampToValueAtTime(1, at + 0.012);
      src.connect(g); g.connect(ctx.destination);
      src.start(at, Math.min(0.15, buf.duration / 4));
      curSrc = { src, g };
      src.onended = () => { if (curSrc && curSrc.src === src) curSrc = null; };
    }).catch(() => { if (token === speakToken) ttsFallback(text); });
  }

  const basePlay = renderPlay;
  renderPlay = function () {
    if (!S || S.mode !== "ctx") return basePlay();
    const w = current();
    const done = Math.min(S.graded.size, S.total);
    const top = `<div class="top">
        <button class="x" data-quit aria-label="Arrêter">${ICON.close}</button>
        <div class="bar"><i style="width:${done / S.total * 100}%"></i></div>
        <div class="count">${done}/${S.total}</div>
      </div>`;
    if (!S.opts) S.opts = options(w);
    const ok = S.answered && (S.choice === w.i || sameAnswer(W[S.choice], w, S.dir));
    const opts = S.opts.map(i => {
      const x = W[i]; let cls = "opt";
      if (S.answered) { if (i === w.i || sameAnswer(x, w, S.dir)) cls += " right"; else if (i === S.choice) cls += " wrong"; }
      return `<button class="${cls}" data-opt="${i}" ${S.answered ? "disabled" : ""}>${esc(x.en)}</button>`;
    }).join("");
    if (S.answered && S.played !== S.pos) { S.played = S.pos; setTimeout(() => playSentence(w), 120); }
    return top + `<section class="postcard card">
        <div class="dirtag">${S.answered ? "La phrase complète" : "Quel mot manque ?"}</div>
        ${S.answered ? `<div class="postmark ${ok ? "good" : "bad"}">${ok ? "Bravo !" : "Raté"}</div>` : ""}
        <div class="ctxs">${sentence(w, S.answered)}</div>
        ${S.answered || S.showFr ? `<div class="ctxfr">${esc(EX[w.en][1])}</div>`
          : `<button class="ctxbtn" data-ctx-fr>Voir la traduction de la phrase</button>`}
        ${S.answered ? `<button class="ctxbtn" data-ctx-say>🔊 Réécouter la phrase</button>
          ${detail(w).replace('<div class="detail">', '<div class="detail" style="margin-top:12px">')}` : ""}
      </section>
      <div class="opts">${opts}</div>
      ${S.answered ? `<div style="margin-top:14px"><button class="btn main" data-next>Suivant</button></div>` : ""}`;
  };

  $app.addEventListener("click", e => {
    if (view !== "play" || !S || S.mode !== "ctx") return;
    const t = e.target.closest("button"); if (!t) return;
    if (t.dataset.ctxFr !== undefined) { S.showFr = true; render(); return; }
    if (t.dataset.ctxSay !== undefined) { playSentence(current()); return; }
  });


  // ---------- Fréquence d'usage en anglais (frequences.json : 4 très courant … 1 rare) ----------
  let FQ = {};
  fetch("frequences.json").then(r => r.ok ? r.json() : {}).then(d => { FQ = d || {}; if (view === "list" || view === "play") render(); }).catch(() => {});
  const FQ_LABEL = { 4: "très courant", 3: "courant", 2: "peu courant", 1: "rare" };
  const st3 = document.createElement("style");
  st3.textContent = `
    .fq{display:inline-block;font-size:.72rem;font-weight:700;border-radius:999px;padding:1px 8px;margin-left:6px;vertical-align:middle;font-family:inherit;letter-spacing:0}
    .fq4{background:rgba(48,209,88,.18);color:#13803a} .fq3{background:rgba(10,132,255,.15);color:#0a63c9}
    .fq2{background:rgba(255,149,10,.18);color:#a85c00} .fq1{background:rgba(255,59,48,.16);color:#c4271c}
  `;
  document.head.appendChild(st3);
  const fqBadge = w => FQ[w.en] ? `<span class="fq fq${FQ[w.en]}" title="Fréquence en anglais">${FQ_LABEL[FQ[w.en]]}</span>` : "";

  const baseDetail = detail;
  detail = function (w) {
    const html = baseDetail(w);
    const k = html.indexOf("</span></div>");
    return k < 0 ? html : html.slice(0, k + 7) + fqBadge(w) + html.slice(k + 7);
  };

  const baseList2 = renderList;
  renderList = function () {
    let html = baseList2();
    for (const w of W) {
      const b = fqBadge(w); if (!b) continue;
      const needle = `data-speak="${esc(w.en)}">${esc(w.en)}</b>`;
      html = html.replace(needle, needle + b);
    }
    return html;
  };

  rebuild();
  render();
})();
