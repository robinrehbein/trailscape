/* Klickbarer Prototyp des Vorschlags. Beispielinhalte sind erfunden. data-go: Seite, data-sheet: Blatt, data-toast: Meldung. */
(function(){
const gear='<button type="button" class="ib" data-go="einst" aria-label="Einstellungen">⚙</button>';
const row=(ic,t,s,act,extra)=>`<button type="button" class="p-row" ${act}><span class="ic">${ic}</span><span class="tx"><b>${t}</b>${s?`<span>${s}</span>`:''}</span>${extra||'<span class="ch">›</span>'}</button>`;
const sw=on=>`<span class="p-sw${on?' on':''}"></span>`;
const neu='<span class="p-badge">neu</span>';
const nope='data-toast="Im Prototyp nicht ausgeführt"';
const route='<svg viewBox="0 0 300 200" preserveAspectRatio="none" aria-hidden="true"><path class="route" d="M40 150C70 90 110 170 140 110S200 40 240 70 260 140 200 160 90 185 40 150z"/></svg>';
const profile='<svg viewBox="0 0 300 60" preserveAspectRatio="none" style="width:100%;height:56px" aria-hidden="true"><path d="M0 55L30 48 60 50 95 34 130 38 170 18 205 22 240 8 270 14 300 6V60H0z" fill="var(--accent-soft)" stroke="var(--accent)" stroke-width="1.5"/></svg>';
const road='<svg viewBox="0 0 300 240" preserveAspectRatio="none" aria-hidden="true"><path d="M0 110L60 78 110 96 170 60 230 88 300 66V112H0z" fill="var(--mapline)"/><path d="M118 240L143 112H157L182 240z" fill="var(--muted)" opacity=".55"/><path d="M150 232V200M150 180V160M150 146V134" stroke="var(--surface)" stroke-width="3"/><circle cx="150" cy="176" r="9" fill="var(--accent)"/><path d="M150 186v22" stroke="var(--accent)" stroke-width="6" stroke-linecap="round"/></svg>';
const seg=(items)=>`<div class="p-chips">${items.map(i=>`<button type="button" class="p-chip${i[2]?' on':''}" ${i[1]}>${i[0]}</button>`).join('')}</div>`;

function heute(drin){return `
 <div class="p-card">
  <span class="p-eyebrow">Einheit des Tages</span>
  <h5>Heute eine lockere Runde.</h5>
  <small>60 min · 120–150 W · Puls bis 140. Du bist erholt, der Plan sieht Grundlage vor.</small>
  ${seg([['Draußen','data-go="heute"',!drin],['Drinnen','data-go="heute_in"',drin]])}
  ${drin?`<small>Ab 16:40 Uhr ist es dunkel. Passende Strecke: Alpe d’Huez, die ersten 60 Minuten.</small><button type="button" class="p-btn" data-sheet="vorfahrt">Drinnen fahren</button>`
        :`<small>Trocken, 9 °C, leichter Westwind. Vorschlag: 26 km, flach.</small><button type="button" class="p-btn" data-go="vorschl">Runde für heute bauen</button>`}
  <button type="button" class="p-btn plain" data-sheet="warum">Warum diese Empfehlung?</button>
 </div>
 <div class="p-card">
  <span class="p-eyebrow">Diese Woche · Woche 4 von 12</span>
  <div class="p-week"><div class="on"><i>✓</i>Mo</div><div class="on"><i>✓</i>Di</div><div class="now"><i>60′</i>Mi</div><div><i>–</i>Do</div><div><i>45′</i>Fr</div><div><i>3 h</i>Sa</div><div><i>–</i>So</div></div>
  <div class="p-prog"><i style="width:42%"></i></div><small>42 von 100 km · Serie: 5 Wochen</small>
  <button type="button" class="p-btn tonal" data-go="plan">Plan ansehen</button>
 </div>
 <div class="p-list">
  ${row('🎯','Dein Ziel: Gran Fondo, 120 km','in 9 Wochen · Prognose 5:10 h','data-go="plan"')}
  ${row('🚴','Letzte Tour: Feierabendrunde','Di 24.11. · 32,4 km · 1:24 h','data-go="tour"')}
 </div>`}

function tour(indoor){return `
 <div class="p-map" style="flex:none;height:150px">${route}</div>
 <div class="p-stats"><div><b>${indoor?'13,8':'32,4'}</b><span>km</span></div><div><b>${indoor?'0:58':'1:24'}</b><span>Std.</span></div><div><b>${indoor?'1 071':'310'}</b><span>Hm</span></div></div>
 ${indoor?'<div><span class="p-badge">Indoor</span></div>':''}
 <div class="p-card"><h5>Was die Tour gebracht hat</h5><small>Eine ruhige Grundlageneinheit. Deine Form bleibt stabil, die Woche liegt im Plan.</small></div>
 ${indoor?`<div class="p-card"><h5>Anstiege ${neu}</h5><small>Alpe d’Huez · 58:12 · neue Bestzeit</small></div><div class="p-card"><h5>Vergleich mit deiner Außenfahrt ${neu}</h5><small>Drinnen 58:12 · Draußen 1:04:30 · Ø Leistung 214 W gegen 198 W</small></div>`:''}
 <div class="p-card"><h5>Höhenprofil</h5>${profile}</div>
 <button type="button" class="p-btn outline" ${nope}>Alle Werte</button>
 <div class="p-sticky"><button type="button" class="p-btn" data-sheet="${indoor?'nochmal_in':'nochmal'}">Nochmal fahren</button></div>`}

const strecken=`
 ${seg([['Bekannte','data-go="indoor"',1],['Eigene','data-go="indoor_eigene"'],['Herausforderungen','data-go="indoor_chall"']])}`;
const indoorTop=`
 <div class="p-list">${row('📶','Trainer gekoppelt','Verbindet sich, wenn die Fahrt beginnt','data-go="e_sensor"')}</div>`;
const sCard=(name,sub,state,btn,act,det)=>`<div class="p-card"><button type="button" class="p-tile" style="padding:0;background:none" ${det||''}><b>${name}</b><span>${sub}</span><span>${state}</span></button><button type="button" class="p-btn${btn==='Fahren'?'':' tonal'}" ${act}>${btn}</button></div>`;

const S={
 heute:{name:'Heute',title:'Heute',sub:'Mittwoch, 25. November',nav:'tabs',tab:'heute',actions:gear,html:heute(false),
  note:'Eine Karte beantwortet, was heute ansteht, mit einem Knopf. Der Umschalter Draußen | Drinnen ersetzt die Karten „Wo fährst du heute?“ und „Einheit des Tages“. Woche, Ziel und letzte Tour führen dorthin, wo sie hinzeigen.',neu:'Der Umschalter nutzt die Empfehlung aus Branch idee-02.'},
 heute_in:{name:'Heute (drinnen)',title:'Heute',sub:'Mittwoch, 25. November',nav:'tabs',tab:'heute',actions:gear,html:heute(true),
  note:'Dieselbe Karte, wenn drinnen vorgeschlagen oder gewählt ist. „Drinnen fahren“ öffnet direkt „Vor der Fahrt“ mit der passenden Strecke, ohne Umweg über eine Startseite.'},
 plan:{name:'Plan',title:'Plan',sub:'Gran Fondo · 14. Februar',nav:'push',actions:'<button type="button" class="ib" data-sheet="rezept" aria-label="Plan-Rezept">⋮</button>',
  html:`<div class="p-card"><span class="p-eyebrow">Dein Ziel</span><h5>Gran Fondo, 120 km</h5><small>14. Februar · Zielzeit 5:00 h · Prognose 5:10 h</small><div class="p-btns"><button type="button" class="p-btn tonal" data-sheet="ziel">Ändern</button><button type="button" class="p-btn plain" data-toast="Blatt „Wie die Prognose entsteht“">Wie wird das berechnet?</button></div></div>
  <div class="p-h">Diese Woche · Woche 4 von 12</div>
  <div class="p-list">${row('✓','Mo · Ruhetag','erledigt','',' ')}${row('✓','Di · Intervalle 4 × 4 min','erledigt · 52 min','',' ')}${row('●','Mi · Lockere Runde','heute · 60 min · 120–150 W','data-go="vorschl"','<span class="p-badge">Runde bauen</span>')}${row('○','Fr · Grundlage','45 min','',' ')}${row('○','Sa · Lange Tour','3 h','',' ')}</div>
  <button type="button" class="p-btn outline" ${nope}>Alle Wochen ansehen</button>`,
  note:'War das zweite Segment im Tab „Training“. Jetzt ein Tipp von „Heute“. Plan-Rezept (teilen, als Datei speichern, Rezept öffnen) liegt im Menü oben rechts.'},
 karte:{name:'Karte',title:'Karte',nav:'tabs',tab:'karte',top:false,bodyStyle:'padding:0;position:relative',
  html:`<div class="p-map full" style="margin:0;position:absolute;inset:0" data-sheet="ort"></div>
  <div class="p-float" style="left:14px;right:14px;top:14px;flex-direction:row;align-items:center"><button type="button" class="p-search" data-sheet="suche">Wohin?</button>${gear.replace('class="ib"','class="p-fab"')}</div>
  <div class="p-float" style="right:14px;top:72px"><button type="button" class="p-fab" data-sheet="ansicht" aria-label="Kartenansicht">◫</button><button type="button" class="p-fab" data-toast="Karte auf deinen Standort" aria-label="Standort">◎</button></div>
  <div class="p-float" style="left:14px;right:14px;bottom:88px;flex-direction:row"><button type="button" class="p-btn" data-go="planung_leer">Route planen</button><button type="button" class="p-btn tonal" data-sheet="runde">Runde bauen</button></div>`,
  note:'Zwei beschriftete Knöpfe statt eines versteckten langen Drucks. Tippe auf die Karte, um einen Punkt zu setzen. Der Ebenen-Knopf ist der einzige Weg zur Kartenansicht; das Zahnrad gibt es jetzt auch hier.'},
 planung_leer:{name:'Route planen',title:'Route planen',sub:'Wähle Start und Ziel',nav:'push',
  html:`<div class="p-map" data-sheet="ort"></div><div class="p-list">${row('◎','Mein Standort als Start','','data-go="planung"')}${row('＋','Punkt hinzufügen','Suchen oder auf die Karte tippen','data-sheet="suche"')}</div>${seg([['Gemischt','',1],['Asphalt',''],['Schotter','']])}<button type="button" class="p-btn plain" data-back>Verwerfen</button>`,
  note:'Eine leere Planung war bisher nur über „Alle Punkte entfernen“ erreichbar.'},
 planung:{name:'Route planen',title:'Route planen',sub:'42 km · 380 Hm ↑',nav:'push',
  html:`<div class="p-map">${route}</div><div class="p-card">${profile}<small>Start: Mein Standort · 3 Wegpunkte · zurück zum Start</small></div>${seg([['Gemischt','',1],['Asphalt',''],['Schotter','']])}
  <button type="button" class="p-btn" data-go="fahrmodus">Losfahren</button><div class="p-btns"><button type="button" class="p-btn outline" data-toast="Gespeichert. Die Route bleibt auf der Karte.">Speichern</button><button type="button" class="p-btn plain" data-back>Verwerfen</button></div>`,
  note:'„Losfahren“ startet Navigation und Aufzeichnung. „Speichern“ lässt die Route stehen. „Verwerfen“ ist ein sichtbarer Knopf; bisher hatte die Funktion keinen.'},
 vorschl:{name:'Passende Runde',title:'Passende Runde',sub:'3 Vorschläge',nav:'push',
  html:`<div class="p-map">${route}</div><div class="p-card"><span class="p-eyebrow">Beste</span><h5>26 km · 140 Hm</h5><small>58 % Schotter · 12 km neue Wege · Rückenwind auf dem Heimweg</small><button type="button" class="p-btn" data-go="planung">Übernehmen</button></div>
  <div class="p-list">${row('2','28 km · 190 Hm','40 % Schotter',nope)}${row('3','24 km · 90 Hm','Asphalt',nope)}</div><button type="button" class="p-btn plain" data-toast="Sucht neue Vorschläge">Andere Vorschläge</button>`,
  note:'Die Suche startet von selbst; der Zwischenschritt „Routen suchen“ entfällt. Nur drei Knöpfe der App führen hierher.'},
 fahrmodus:{name:'Fahrmodus',title:'',nav:'none',top:false,back:false,
  html:`<div style="display:flex;justify-content:space-between;align-items:center;padding-top:14px"><span class="p-badge">Aufzeichnung</span>${seg([['Daten','',1],['Karte','data-go="fahrkarte"']])}</div>
  <div class="p-hud"><div style="grid-column:1/-1"><span>Tempo</span><b style="font-size:54px">24,6</b><span>km/h</span></div><div><b>12,4</b><span>km gefahren</span></div><div><b>0:31</b><span>Fahrzeit</span></div><div><b>140</b><span>Höhenmeter ↑</span></div><div><b>In 200 m</b><span>Links</span></div></div>
  <div style="flex:1"></div><div class="p-btns"><button type="button" class="p-btn tonal" data-toast="Pausiert">Pause</button><button type="button" class="p-btn danger" data-go="tour">Beenden: halten</button></div>`,
  note:'Ein Bediensatz für Pause und Beenden statt drei. „Daten“ und „Karte“ sind zwei Seiten desselben Fahrmodus. Nach dem Beenden öffnet das Tourdetail.'},
 fahrkarte:{name:'Fahrmodus · Karte',title:'',nav:'none',top:false,back:false,bodyStyle:'padding:0 14px 20px',
  html:`<div style="display:flex;justify-content:space-between;align-items:center;padding-top:14px"><span class="p-badge">In 200 m · Links</span>${seg([['Daten','data-back'],['Karte','',1]])}</div><div class="p-map">${route}</div>
  <div class="p-btns"><button type="button" class="p-btn tonal" data-toast="Pausiert">Pause</button><button type="button" class="p-btn danger" data-go="tour">Beenden: halten</button></div>`,
  note:'Die Navigation ist die Kartenseite des Fahrmodus, mit derselben Leiste.'},
 indoor:{name:'Indoor',title:'Indoor',nav:'tabs',tab:'indoor',actions:'<button type="button" class="ib" data-go="garage" aria-label="Garage">👕</button><button type="button" class="ib" data-go="e_indoor" aria-label="Einstellungen Indoor">⚙</button>',
  html:indoorTop+strecken+`<button type="button" class="p-btn plain" style="text-align:left" data-toast="Länge, Anstieg, Belag, Stand, Sortierung">Filter und Sortierung</button>
  ${sCard('Alpe d’Huez','13,8 km · 1 071 Hm · Rennrad','Bereit','Fahren','data-sheet="vorfahrt"','data-go="strecke"')}
  ${sCard('Stilfser Joch','24,3 km · 1 808 Hm · Rennrad','Noch nicht auf dem Gerät','Herunterladen · 77 MB','data-toast="Lädt herunter …"','data-go="strecke"')}
  ${sCard('Mont Ventoux','21,5 km · 1 610 Hm · Rennrad','Bereit','Fahren','data-sheet="vorfahrt"','data-go="strecke"')}`,
  note:'Neuer Tab. Jede Strecke trägt ihren eigenen Knopf; Fußleiste und doppeltes „Fahren“ entfallen. Garage und Einstellungen haben je ein Symbol oben, der Trainer-Status führt zum einen Ort fürs Koppeln.'},
 indoor_eigene:{name:'Indoor · Eigene',title:'Indoor',nav:'tabs',tab:'indoor',actions:'<button type="button" class="ib" data-go="garage" aria-label="Garage">👕</button><button type="button" class="ib" data-go="e_indoor" aria-label="Einstellungen Indoor">⚙</button>',
  html:indoorTop+seg([['Bekannte','data-go="indoor"'],['Eigene','data-go="indoor_eigene"',1],['Herausforderungen','data-go="indoor_chall"']])+
  `${sCard('Schotterrunde am See','Gefahrene Tour · So 22.11. · 48 km','Bereit','Fahren','data-sheet="vorfahrt"')}
  ${sCard('Feierabendrunde','Planung · 32 km','Wird einmal vorbereitet','Vorbereiten','data-toast="Die 3D-Ansicht wird erstellt …"')}
  ${sCard('Teststrecke','Rund 3 km, eingebaut, ohne Netz','Bereit','Fahren','data-sheet="vorfahrt"')}`,
  note:'Eigene Touren und Planungen im selben Kartenmuster wie die bekannten Strecken. Die Teststrecke steht am Ende statt an erster Stelle.'},
 indoor_chall:{name:'Indoor · Herausforderungen',title:'Indoor',nav:'tabs',tab:'indoor',actions:'<button type="button" class="ib" data-go="garage" aria-label="Garage">👕</button><button type="button" class="ib" data-go="e_indoor" aria-label="Einstellungen Indoor">⚙</button>',
  html:indoorTop+seg([['Bekannte','data-go="indoor"'],['Eigene','data-go="indoor_eigene"'],['Herausforderungen','data-go="indoor_chall"',1]])+
  `${sCard('Herausforderung von Alex','Alpe d’Huez · 58:12','Strecke bereit','Gegen Alex fahren','data-sheet="vorfahrt"')}<button type="button" class="p-btn outline" data-toast="Dateiauswahl">Datei wählen</button><small class="p-muted">Herausforderungen kommen als Datei von Person zu Person. Kein Konto, kein Server.</small>`,
  note:'Empfangen und Fahren an einem Ort.',neu:'Herausforderungen kommen mit Branch spiel-3.'},
 strecke:{name:'Streckendetail',title:'Alpe d’Huez',sub:'Bekannte Strecke · Rennrad',nav:'push',
  html:`<div class="p-card"><h5>Höhenprofil</h5>${profile}</div><div class="p-stats"><div><b>13,8</b><span>km</span></div><div><b>1 071</b><span>Hm</span></div><div><b>8,1 %</b><span>im Schnitt</span></div></div>
  <div class="p-card"><h5>Über die Strecke</h5><small>21 Kehren, durchgehend Asphalt. Richtwert für dich: etwa 1:05 h.</small></div>
  <button type="button" class="p-btn" data-sheet="vorfahrt">Fahren</button><button type="button" class="p-btn tonal" data-go="vorschau">In 3D ansehen</button>`,
  note:'Dieselbe Seite für bekannte und eigene Strecken, mit einer Hauptaktion.',neu:'„In 3D ansehen“ kommt mit Branch idee-04.'},
 vorschau:{name:'3D-Vorschau',title:'3D-Vorschau',sub:'Alpe d’Huez',nav:'push',
  html:`<div class="p-3d">${road}</div>${profile}<div class="p-btns"><button type="button" class="p-btn" data-toast="Wiedergabe läuft">Wiedergabe</button><button type="button" class="p-btn tonal" data-toast="Tempo 8×">4×</button><button type="button" class="p-btn tonal" data-toast="Kamera: Drohne">Kamera</button></div>`,
  note:'Kamerafahrt ohne Trainer. Erreichbar aus dem Streckendetail und aus „Nochmal fahren“ im Tourdetail. „Zurück“ führt dorthin zurück, woher man kam.',neu:'Kommt mit Branch idee-04.'},
 ifahrt:{name:'Indoor-Fahrt',title:'',nav:'none',top:false,back:false,
  html:`<div class="p-3d" style="margin-top:0">${road}</div><div class="p-hud"><div><b>182 W</b><span>Leistung</span></div><div><b>14,2</b><span>km/h</span></div><div><b>84</b><span>Trittfrequenz</span></div><div><b>8,1 %</b><span>Steigung · Asphalt</span></div></div>
  <div class="p-btns"><button type="button" class="p-btn tonal" data-toast="Gang 10 / 24">Gang −</button><button type="button" class="p-btn outline" disabled>Gang 11 / 24</button><button type="button" class="p-btn tonal" data-toast="Gang 12 / 24">Gang +</button></div>
  <div class="p-btns"><button type="button" class="p-btn outline" data-toast="Kamera: Lenker">Kamera</button><button type="button" class="p-btn outline" data-toast="Trainer verbunden · Schalttaster verbunden">Geräte</button><button type="button" class="p-btn" data-sheet="ipause">Pause</button></div>`,
  note:'Beschriftete Knöpfe statt reiner Symbole. Beendet wird über „Pause“; es gibt nur noch einen Dialog dafür.'},
 iausw:{name:'Auswertung',title:'Auswertung',sub:'Alpe d’Huez · Indoor',nav:'push',back:false,
  html:`<div class="p-stats"><div><b>0:58</b><span>Zeit</span></div><div><b>13,8</b><span>km</span></div><div><b>214 W</b><span>Ø Leistung</span></div></div><div class="p-card"><small>Speichern legt die Fahrt als Tour in den Verlauf. Dort stehen Anstiege, Vergleich und Teilen.</small></div>
  <button type="button" class="p-btn" data-go="tour_in">Speichern</button><button type="button" class="p-btn plain" data-home>Verwerfen</button>`,
  note:'Kurzfassung mit einer Entscheidung. Nach „Speichern“ öffnet das Tourdetail; die Auswertung ist keine Sackgasse mehr.'},
 garage:{name:'Garage',title:'Garage',nav:'push',
  html:`<div class="p-3d" style="min-height:150px;flex:none;height:150px">${road}</div>
  <div class="p-card"><h5>Fahrer</h5><div class="p-dots"><i style="background:#c98a5a"></i><i style="background:#8a5a3a"></i><i style="background:#f0c9a0" class="on"></i></div></div>
  <div class="p-card"><h5>Kleidung</h5><div class="p-dots"><i style="background:#0D7A42" class="on"></i><i style="background:#2A6474"></i><i style="background:#BB2016"></i><i class="lock">🔒</i></div><small>Trikotmuster: Streifen · ein Muster frei „ab Stufe 4“</small></div>
  <div class="p-card"><h5>Rad</h5><div class="p-dots"><i style="background:#1d1f22" class="on"></i><i style="background:#9aa0a8"></i><i style="background:#c9a227"></i></div></div>
  <button type="button" class="p-btn outline" data-toast="Vorgabe wiederhergestellt">Vorgabe wiederherstellen</button>`,
  note:'Ein Einstieg: das Symbol oben im Indoor-Tab. Gilt ab dem nächsten Streckenstart.',neu:'Kommt mit Branch spiel-2.'},
 verlauf:{name:'Verlauf · Touren',title:'Verlauf',nav:'tabs',tab:'verlauf',actions:'<button type="button" class="ib" data-toast="Tour nach Namen suchen" aria-label="Touren suchen">⌕</button><button type="button" class="ib" data-sheet="import" aria-label="Touren importieren">＋</button>'+gear,
  html:seg([['Touren','data-go="verlauf"',1],['Fortschritt','data-go="fortschritt"'],['Erfolge','data-go="erfolge"']])+seg([['Alle','',1],['Draußen',nope],['Indoor',nope],['Geplant',nope]])+
  `<div class="p-h">November</div><div class="p-list">${row('🚴','Feierabendrunde','Di 24.11. · 32,4 km · 1:24 h · locker','data-go="tour"')}${row('🏔','Alpe d’Huez','So 22.11. · 13,8 km · 0:58 h · Indoor','data-go="tour_in"')}${row('🚴','Schotterrunde am See','Sa 21.11. · 48,0 km · 2:10 h · mittel','data-go="tour"')}${row('🚴','Kurze Morgenrunde','Do 19.11. · 18,2 km · 0:46 h · locker','data-go="tour"')}</div>
  <button type="button" class="p-btn outline" data-toast="Zeigt alle Spuren auf der Karte">Alle Touren auf der Karte</button>`,
  note:'Der Blick zurück: Touren, Fortschritt, Erfolge. „Geplant“ ist ein Filter; die letzte Tour steht wieder oben.'},
 fortschritt:{name:'Verlauf · Fortschritt',title:'Verlauf',nav:'tabs',tab:'verlauf',actions:gear,
  html:seg([['Touren','data-go="verlauf"'],['Fortschritt','data-go="fortschritt"',1],['Erfolge','data-go="erfolge"']])+
  `<div class="p-card"><h5>Letzte 12 Wochen</h5><div class="p-bars">${[30,45,38,60,52,70,48,66,80,58,74,42].map(h=>`<i style="height:${h}%"></i>`).join('')}</div></div>
  <div class="p-card"><h5>Jahresziel</h5><div class="p-prog"><i style="width:64%"></i></div><small>3 200 von 5 000 km</small><button type="button" class="p-btn tonal" data-toast="Dialog „Jahresziel“">Ändern</button></div>
  <div class="p-list">${row('★','Bestwerte','Längste Tour 112 km · Tour öffnen','data-go="tour"')}${row('♥','Deine Form','ausgeruht · Fitness steigt',nope)}${row('☾','Körperwerte','Ruhepuls 52 · Schlaf 7:20 h',nope)}</div>`,
  note:'War das erste Segment im Tab „Training“. Das Jahresziel liegt nur noch hier.'},
 erfolge:{name:'Verlauf · Erfolge',title:'Verlauf',nav:'tabs',tab:'verlauf',actions:gear,
  html:seg([['Touren','data-go="verlauf"'],['Fortschritt','data-go="fortschritt"'],['Erfolge','data-go="erfolge"',1]])+
  `<div class="p-card"><h5>Stufe 3</h5><div class="p-prog"><i style="width:55%"></i></div><small>9 von 30 Abzeichen</small></div>
  <div class="p-card"><h5>Wochenserie: 5 Wochen</h5><small>Eine Pausenwoche hält die Serie.</small><button type="button" class="p-btn tonal" data-toast="Diese Woche zählt als Pause">Diese Woche als Pause</button></div>
  <div class="p-h">Touren</div><div class="p-grid2"><div class="p-tile"><span class="em">🥇</span><b>Erste 100 km</b><span>erreicht</span></div><div class="p-tile"><span class="em">🌄</span><b>1 000 Hm an einem Tag</b><span>erreicht</span></div></div>
  <div class="p-h">Drinnen</div><div class="p-grid2"><div class="p-tile"><span class="em">🏔</span><b>Erster Pass</b><span>erreicht</span></div><div class="p-tile" style="opacity:.55"><span class="em">🔒</span><b>Fünf Strecken</b><span>2 von 5</span></div></div>`,
  note:'Eigenes Segment. Ist „Erfolge anzeigen“ in den Einstellungen aus, fehlt es ganz.',neu:'Kommt mit Branch spiel-1.'},
 tour:{name:'Tourdetail',title:'Feierabendrunde',sub:'Di 24.11.',nav:'push',actions:'<button type="button" class="ib" data-sheet="teilen" aria-label="Teilen">⤴</button><button type="button" class="ib" data-sheet="tourmenu" aria-label="Mehr">⋮</button>',html:tour(false),
  note:'Ein fester Knopf unten, „Teilen“ oben, alles Seltene im Menü. Vorher lagen bis zu neun Aktionen im Inhalt, „Löschen“ neben „Teilen“.'},
 tour_in:{name:'Tourdetail (Indoor)',title:'Alpe d’Huez',sub:'So 22.11.',nav:'push',actions:'<button type="button" class="ib" data-sheet="teilen" aria-label="Teilen">⤴</button><button type="button" class="ib" data-sheet="tourmenu" aria-label="Mehr">⋮</button>',html:tour(true),
  note:'Dieselbe Seite für Indoor-Touren. Anstiege und der Vergleich mit der Außenfahrt stehen hier statt in der Auswertung nach der Fahrt.',neu:'Anstiege (idee-11) und Vergleich (idee-01).'},
 einst:{name:'Einstellungen',title:'Einstellungen',nav:'push',
  html:`<div class="p-h">Du</div><div class="p-list">${row('☺','Profil','41 Jahre · 74 kg · FTP 240 W','data-go="e_profil"')}${row('⌚','Uhr & Gesundheitsdaten','Verbunden',nope)}</div>
  <div class="p-h">Geräte</div><div class="p-list">${row('📶','Sensoren & Trainer','Pulsgurt, Trainer','data-go="e_sensor"')}</div>
  <div class="p-h">Fahren</div><div class="p-list">${row('●','Aufzeichnung & Ansagen','Auto-Pause an',nope)}${row('🏠','Indoor','Fahrgefühl, 3D-Ansicht','data-go="e_indoor"')}${row('🔔','Erinnerungen & Motivation','Tagesplan 7:30 · Erfolge an',nope)}</div>
  <div class="p-h">Daten</div><div class="p-list">${row('⤓','Backup','Noch nie gesichert','data-go="e_backup"')}${row('▦','Karten offline','2 Ausschnitte',nope)}${row('S','Strava','Nicht verbunden',nope)}${row('⇄','Sync mit eigenem Server','Aus',nope)}</div>
  <div class="p-h">App</div><div class="p-list">${row('文','Sprache','System',nope)}${row('i','Über Trailscape','Version 1.4',nope)}</div>`,
  note:'Die einzige Seite mit diesem Namen. Zwölf Zeilen in fünf benannten Gruppen; vorher 13 Zeilen, davon sieben ohne Überschrift, plus eine zweite Einstellungsseite im Indoor.'},
 e_sensor:{name:'Sensoren & Trainer',title:'Sensoren & Trainer',nav:'push',
  html:`<div class="p-list">${row('♥','Pulsgurt','Gekoppelt',nope,'<span class="p-muted">Vergessen</span>')}${row('⚡','Leistungsmesser','Nicht gekoppelt',nope,' ')}${row('🏠','Trainer','Gekoppelt',nope,'<span class="p-muted">Vergessen</span>')}${row('⇅','Schalttaster','Nicht gekoppelt',nope,' ')}</div>
  <button type="button" class="p-btn" data-toast="Suche läuft …">Geräte suchen</button>
  <div class="p-list">${row('▷','Ohne Trainer ausprobieren','Demo mit Reglern für Leistung und Trittfrequenz',nope,sw(false))}</div>`,
  note:'Der einzige Ort zum Koppeln, mit einer Suche. Vorher zwei Orte mit zwei Suchknöpfen („Suche beenden“ und „Stopp“).'},
 e_indoor:{name:'Einstellungen · Indoor',title:'Indoor',sub:'Einstellungen',nav:'push',
  html:`<div class="p-h">Fahrgefühl</div><div class="p-list">${row('⚙','Kettenblatt am Trainer','34 Zähne',nope)}${row('⇅','Virtuelle Gänge','Gleichmäßig (24 Gänge)',nope)}${row('≈','Feinabstimmung '+neu,'Gesamtwiderstand, Stärke des Belags',nope)}${row('◔','Fahrgefühl einstellen '+neu,'Geführter Abgleich, 2 bis 3 Minuten',nope)}</div>
  <div class="p-h">3D-Ansicht</div><div class="p-list">${row('◆','Grafikstufe '+neu,'Standard',nope)}${row('☀','Tageszeit','Automatisch',nope)}${row('👥','Andere Radfahrer anzeigen','',nope,sw(true))}${row('⛰','Einblendungen auf der Strecke '+neu,'Anstiege, Bestzeiten',nope,sw(true))}</div>
  <div class="p-h">Fahrt</div><div class="p-list">${row('◐','Im Hintergrund weiterfahren '+neu,'',nope,sw(true))}</div>
  <div class="p-list">${row('▸','Speicher & Diagnose','Strecken auf dem Gerät, Fahrprotokolle, Diagnose','data-go="e_speicher"')}</div>`,
  note:'Nimmt die bisherige zweite Einstellungsseite auf. Die acht neuen Einträge aus fünf Branches haben hier je eine feste Gruppe; Technisches ist eingeklappt.'},
 e_speicher:{name:'Speicher & Diagnose',title:'Speicher & Diagnose',sub:'Einstellungen · Indoor',nav:'push',
  html:`<div class="p-list">${row('▦','Strecken auf dem Gerät','3 Strecken · 210 MB',nope)}${row('≡','Fahrprotokolle '+neu,'4 Protokolle',nope)}${row('↻','Liste der bekannten Strecken aktualisieren','',' data-toast="Liste aktualisiert"','<span class="p-muted">Jetzt</span>')}${row('🔧','Diagnose','Testszene, Teststrecke, Probefahrt',nope)}</div>`,
  note:'Vier technische Seiten an einem Ort. Die Diagnose war doppelt erreichbar, „Strecken auf dem Gerät“ nur über drei Ebenen.'},
 e_backup:{name:'Backup',title:'Backup',nav:'push',
  html:`<div class="p-card"><small>Alle Touren, Planungen, Ziele und Einstellungen in einer Datei. Noch nie gesichert.</small><button type="button" class="p-btn" data-toast="Systemdialog zum Speichern">Backup exportieren</button><button type="button" class="p-btn outline" data-toast="Dateiauswahl">Backup importieren</button></div><small class="p-muted">Touren aus GPX, FIT oder einem Archiv importierst du im Verlauf über das +.</small>`,
  note:'Nur noch Backup. Vorher standen hier drei Import-Knöpfe nebeneinander.'},
 e_profil:{name:'Profil',title:'Profil',nav:'push',
  html:`<div class="p-list">${row('','Alter','41',nope,' ')}${row('','Gewicht (kg)','74',nope,' ')}${row('','Rad + Gepäck (kg)','11',nope,' ')}</div><div class="p-h">Puls und Leistung</div><div class="p-list">${row('','HFmax','182',nope,' ')}${row('','Ruhepuls','52',nope,' ')}${row('','Schwellenleistung FTP (Watt)','240',nope,' ')}</div><small class="p-muted">Das Zeitbudget pro Woche stellst du beim Ziel ein.</small>`,
  note:'Puls und FTP stehen sichtbar. Training und Indoor rechnen damit; bisher lagen sie hinter „Erweitert“.'},
};
const SH={
 losfahren:{name:'Blatt „Losfahren“',html:`<h5>Losfahren</h5><small class="p-muted">Die App zeichnet auf, die Route entsteht unterwegs.</small><button type="button" class="p-btn" data-go="fahrmodus">Aufzeichnung starten</button><button type="button" class="p-btn tonal" data-go="vorschl">Runde für heute bauen</button><small class="p-muted">Liegt eine geplante Route vor, stehen hier „Mit Route starten“, „Ohne Route starten“ und „Route verwerfen“.</small>`,
  note:'Der eine Startpunkt für draußen, von jedem Tab aus. Die Indoor-Zeile entfällt, weil Indoor ein Tab ist.'},
 warum:{name:'Blatt „Warum diese Empfehlung?“',html:`<h5>Warum eine lockere Runde?</h5><div class="p-list">${row('☾','Schlaf','7:20 h · gut','',' ')}${row('♥','Ruhepuls','52 · normal','',' ')}${row('◔','Erholung (HRV)','im Bereich','',' ')}${row('▲','Belastung','gestern hart','',' ')}</div><button type="button" class="p-btn" data-closesheet>Verstanden</button>`,note:'Unverändert.'},
 ziel:{name:'Blatt „Ziel ändern“',html:`<h5>Ziel ändern</h5>${seg([['Event','',1],['Fitness','']])}<div class="p-list">${row('','Name','Gran Fondo','',' ')}${row('','Distanz (km)','120','',' ')}${row('','Zieldatum','14. Februar','',' ')}${row('','Zeitbudget pro Woche (Stunden)','7','',' ')}</div><button type="button" class="p-btn" data-toast="Plan erstellt">Plan erstellen</button>`,note:'Das Zeitbudget steht nur noch hier, nicht zusätzlich im Profil.'},
 rezept:{name:'Menü „Plan-Rezept“',html:`<h5>Plan-Rezept</h5><small class="p-muted">Pläne als Datei tauschen, ohne Konto und ohne persönliche Daten.</small><div class="p-list">${row('⤴','Plan teilen','','data-toast="System-Teilen"')}${row('⤓','Als Datei speichern','','data-toast="Rezept gespeichert."')}${row('▤','Rezept öffnen','','data-toast="Dateiauswahl"')}</div>`,note:'Liegt im Menü der Seite „Plan“ statt als Karte am Ende der Liste.'},
 suche:{name:'Suche',html:`<h5>Wohin?</h5><div class="p-search" style="box-shadow:none">Ort, Stadt oder Straße</div><div class="p-h">Zuletzt gesucht</div><div class="p-list">${row('⌖','Stilfser Joch','','data-sheet="ort"')}${row('⌖','Mont Ventoux','','data-sheet="ort"')}</div>`,note:'Unverändert.'},
 ort:{name:'Ortskarte',html:`<h5>Markierter Punkt</h5><small class="p-muted">12 km entfernt</small><button type="button" class="p-btn" data-go="planung">Route hierher</button><button type="button" class="p-btn tonal" data-sheet="runde">Runde ab hier</button>`,note:'Zwei Aktionen statt drei. Ein einfacher Tipp auf die Karte öffnet diese Karte.'},
 runde:{name:'Blatt „Runde bauen“',html:`<h5>Runde bauen</h5><small class="p-muted">ab deinem Standort</small>${seg([['20 km',''],['40 km','',1],['60 km',''],['80 km','']])}${seg([['Gemischt','',1],['Asphalt',''],['Schotter','']])}<div class="p-list">${row('🌬','Wind berücksichtigen','','',sw(true))}${row('✦','Neu entdecken','Wege','',' ')}</div><button type="button" class="p-btn" data-go="vorschl">Vorschläge zeigen</button>`,note:'Heißt überall „Runde bauen“.'},
 ansicht:{name:'Blatt „Kartenansicht“',html:`<h5>Kartenansicht</h5>${seg([['Straßenkarte','',1],['CyclOSM',''],['Gelände',''],['Satellit','']])}<div class="p-list">${row('▦','Entdeckt-Kacheln','','',sw(true))}</div><button type="button" class="p-btn outline" data-toast="Lade Kartendaten …">Diesen Ausschnitt offline speichern</button><small class="p-muted">Gespeicherte Ausschnitte verwaltest du unter Einstellungen → Karten offline.</small>`,note:'Ein Einstieg statt drei.'},
 vorfahrt:{name:'Blatt „Vor der Fahrt“',html:`<h5>Vor der Fahrt</h5><small class="p-muted">Alpe d’Huez · 13,8 km · Trainer gekoppelt</small><div class="p-h" style="margin:0">Wo startest du?</div>${seg([['Von vorn','',1],['Weiter bei km 6,2','']])}<div class="p-h" style="margin:0">Fahrweise</div>${seg([['Frei fahren',''],['Einheit des Tages','',1]])}<div class="p-h" style="margin:0">Mitfahrer</div>${seg([['Keiner',''],['Frühere Fahrt','',1],['Feste Leistung',''],['Herausforderung','']])}<button type="button" class="p-btn" data-go="ifahrt">Losfahren</button>`,
  note:'Ersetzt die Karte „Optionen“, die Seite „Bereit zum Fahren“ und die Karte „Wo startest du?“. Ein „Losfahren“ statt zwei. Muss eine Strecke erst geladen oder vorbereitet werden, zeigt dieses Blatt den Fortschritt und startet danach.'},
 ipause:{name:'Blatt „Pausiert“',html:`<h5>Pausiert</h5><button type="button" class="p-btn" data-closesheet>Weiter</button><button type="button" class="p-btn danger" data-go="iausw">Fahrt beenden</button>`,note:'Ein Dialog statt zwei mit gleichem Titel.'},
 nochmal:{name:'Blatt „Nochmal fahren“',html:`<h5>Nochmal fahren</h5><div class="p-list">${row('➤','Draußen mit Navigation','Die Tour als Route auf der Karte','data-go="fahrmodus"')}${row('🏠','Indoor fahren '+neu,'Deine Außenfahrt fährt als Mitfahrer mit','data-sheet="vorfahrt"')}${row('◆','In 3D ansehen '+neu,'Kamerafahrt ohne Trainer','data-go="vorschau"')}</div>`,
  note:'Fester Platz für die drei Arten des Nachfahrens.',neu:'„Indoor fahren“ (idee-01) und „In 3D ansehen“ (idee-04).'},
 nochmal_in:{name:'Blatt „Nochmal fahren“',html:`<h5>Nochmal fahren</h5><div class="p-list">${row('🏠','Indoor fahren','Deine frühere Fahrt als Mitfahrer','data-sheet="vorfahrt"')}${row('◆','In 3D ansehen '+neu,'Kamerafahrt ohne Trainer','data-go="vorschau"')}</div>`,note:'Bei einer Indoor-Tour ohne die Zeile „Draußen“.'},
 teilen:{name:'Blatt „Teilen“',html:`<h5>Teilen</h5><div class="p-list">${row('▣','Bild','Story oder Quadrat · Start und Ziel ausblenden','data-toast="Vorschau, dann System-Teilen"')}${row('⤓','GPX-Datei','','data-toast="System-Teilen"')}${row('S','Zu Strava hochladen','Noch nicht verbunden · Strava verbinden','data-toast="Einstellungen → Strava"')}${row('⚑','Als Herausforderung '+neu,'Nur bei Indoor-Touren','data-toast="Dialog „Herausforderung teilen“"')}</div>`,
  note:'Alles, was die App verlässt, an einem Ort. Strava ist auch ohne Verbindung sichtbar.',neu:'„Als Herausforderung“ kommt mit Branch spiel-3.'},
 tourmenu:{name:'Menü der Tour',html:`<div class="p-list">${row('✎','Umbenennen','','data-toast="Dialog „Tour umbenennen“"')}${row('▦','Auf der Karte zeigen','','data-toast="Öffnet die Tour auf der Karte"')}${row('⇄','In Planung umwandeln','','data-toast="Rückfrage „In Planung umwandeln?“"')}${row('🗑','Löschen','','data-toast="Tour gelöscht · Rückgängig"')}</div>`,note:'Vier seltene Aktionen eingeklappt. „Löschen“ behält „Rückgängig“.'},
 import:{name:'Menü „Importieren“',html:`<h5>Touren importieren</h5><div class="p-list">${row('▤','Dateien (GPX, FIT)','','data-toast="Dateiauswahl"')}${row('▥','Archiv (Strava/Garmin ZIP)','','data-toast="Dateiauswahl"')}</div>`,note:'Beide Einträge öffnen direkt die Dateiauswahl. Der einzige Ort für Tour-Import.'},
};
window.PROTO={start:'heute',
 tabs:[{id:'heute',label:'Heute',ic:'☀',screen:'heute'},{id:'karte',label:'Karte',ic:'▦',screen:'karte'},{id:'indoor',label:'Indoor',ic:'⌂',screen:'indoor'},{id:'verlauf',label:'Verlauf',ic:'≋',screen:'verlauf'}],
 screens:S,sheets:SH,
 paths:[
  {t:'Start',s:'„Heute“ mit Einheit des Tages',stack:['heute']},
  {t:'Draußen aufzeichnen',s:'Fahren → Aufzeichnung starten',stack:['heute'],sheet:'losfahren'},
  {t:'Runde für heute',s:'Runde für heute bauen → Übernehmen → Losfahren',stack:['heute','vorschl']},
  {t:'Route planen',s:'Karte → Route planen',stack:['karte']},
  {t:'Indoor: Strecke wählen',s:'Indoor → Fahren → Losfahren',stack:['indoor']},
  {t:'Indoor: Einheit des Tages',s:'Drinnen fahren → Losfahren',stack:['heute_in']},
  {t:'Tourdetail mit Aktionen',s:'Nochmal fahren, Teilen, Menü',stack:['verlauf','tour']},
  {t:'Plan und Ziel',s:'Heute → Plan ansehen',stack:['heute','plan']},
  {t:'Erfolge',s:'Verlauf → Erfolge',stack:['erfolge']},
  {t:'Garage',s:'Indoor → Garage',stack:['indoor','garage']},
  {t:'Einstellungen',s:'Eine Seite, fünf Gruppen',stack:['heute','einst']},
  {t:'Indoor-Einstellungen',s:'Zahnrad → Indoor',stack:['heute','einst','e_indoor']},
 ]};
})();
