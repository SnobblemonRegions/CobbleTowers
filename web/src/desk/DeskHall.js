// The Frontier Desk Hall (P51, docs/design/P51-frontier-desk-hall.md): the Play page. Your team, the run you can return to, the towers
// with their residents and your mastery, and the way into a climb. A view of the payload plus the same requests the native Hall sends.
import { useEffect, useRef, useState } from 'preact/hooks';
import { html } from '../html.js';
import { ATLAS } from '../gen/atlas.js';
import { act, closeGui } from '../bridge.js';
import { update } from '../store.js';
import { goTo, setTab } from '../reducer.js';
import { firstVisit } from '../screens/Guide.js';
import { regionOf } from '../lobby.js';
import { paintPanorama } from '../art/panorama.js';
import { request } from '../screens/Hall.js';
import { sendTrack } from '../screens/Tracks.js';
import { sendFeature } from '../screens/Features.js';
import { defaultPick, modeLine, prettyStatus, rankTone, residentsOf, shortName, standingOf, standingText, teamSummary } from './standings.js';
import { Logo } from './Logo.js';
import { Emblem } from './emblem.js';
import { endsText } from './briefing.js';

/** A partner sprite from the shared sheet at a width in --u units; a ball when the sheet has no picture of it. */
export function DeskSprite({ name, w = 5 }) {
  const e = ATLAS.map[name];
  const size = `width:calc(var(--u) * ${w})`;
  if (!e) return html`<i class="sp ball" style=${size + `;margin:0 calc(var(--u) * ${w / 6})`}></i>`;
  return html`<i class="sp" style=${`--x:${e[0]};--y:${e[1]};--w:${e[2]};--h:${e[3]};--aw:${ATLAS.width};--ah:${ATLAS.height};${size}`}></i>`;
}

const PANOS = new Map();
/** A region's landscape in a canvas that fills its box. */
export function DeskPano({ region }) {
  const ref = useRef(null);
  useEffect(() => {
    let data = PANOS.get(region);
    if (!data) { data = new ImageData(paintPanorama(region, 240, 60), 240, 60); PANOS.set(region, data); }
    ref.current.getContext('2d').putImageData(data, 0, 0);
  }, [region]);
  return html`<canvas ref=${ref} class="dpano" width="240" height="60"></canvas>`;
}

const PILLS = [['towers', 'Play'], ['trials', 'Trials'], ['progress', 'Progress'], ['social', 'Social'], ['ranks', 'Ranks'], ['codex', 'Codex']];

/** Moves to another page: the ones the Desk look does not draw yet open in the native look. */
function openPill(id) {
  if (id === 'codex') return sendFeature('modifiers');
  update((s) => setTab(s, id));
  if (id === 'progress') sendTrack('refresh');
  if (id === 'ranks') act('RanksRequest', { left: '', right: '' });
  return undefined;
}

export function TopBar({ active = 'towers', back }) {
  return html`<div class="dtop">${back ? html`<button class="tl dback" onClick=${back}>← Back</button>` : null}<${Logo} />
    <div class="pills" role="tablist">${PILLS.map(([id, name]) => html`<a key=${id} role="tab" tabindex="0" aria-selected=${id === active} class=${id === active ? 'on' : ''}
      onClick=${() => openPill(id)} onKeyDown=${(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openPill(id); } }}>${name}</a>`)}</div>
    <span class="sp2"></span>
    <button class="tl" onClick=${() => request('refresh')}>Refresh</button>
    <button class="tl" onClick=${() => update((s) => goTo(s, 'guide'))}>Guide</button>
    <button class="tl" onClick=${() => update((s) => goTo(s, 'settings'))}>Settings</button>
    <button class="x" aria-label="Close" onClick=${closeGui}>×</button></div>`;
}

function Team({ party, mode }) {
  const sum = teamSummary(party);
  return html`<div class="dcard dcol"><div class="ch"><h3>Your team</h3><span class="lbl">${sum.text}</span></div>
    ${party.length === 0 ? html`<div class="empty">Put Pokémon in your party to climb.</div>` : html`<div class="team">${party.map((m, i) => html`<div class=${'mon' + (m.fainted ? ' out' : '')} key=${i}>
      <${DeskSprite} name=${m.species} w=${6.4} />
      <div><b>${m.name}</b><small>Lv ${m.level}${m.fainted ? html` <span class="out">Fainted</span>` : null}</small><div class="tys">${m.types.map((t) => html`<span key=${t} class=${'ty t-' + t}>${t}</span>`)}</div></div></div>`)}</div>`}
    ${sum.fainted ? html`<div class="elig bad">${sum.fainted === 1 ? '1 Pokémon is fainted' : sum.fainted + ' Pokémon are fainted'}. Heal before you climb.</div>` : null}
    ${mode ? html`<div class=${'elig' + (mode.ok ? '' : ' warn')}>${mode.text}</div>` : null}</div>`;
}

function TowerRow({ t, extras, selected, onPick }) {
  const st = standingOf(extras, t.id);
  const res = residentsOf(t.id);
  const run = extras?.runTower === t.id;
  return html`<button class=${'trow' + (selected ? ' sel' : '')} onClick=${onPick} aria-pressed=${selected} title=${t.name}>
    <div class="pbox"><${DeskPano} region=${regionOf(t.id)} /></div>
    <div><b class="dtname"><${Emblem} tower=${t.id} w=${2.6} />${shortName(t.name)}</b><small>${t.floors} floors · ${t.milestones} milestones${t.ascends ? ' · Ascends' : ''}</small>
      <span class=${'medal m-' + rankTone(st)}>${standingText(st)}</span>
      ${st && st.ascension > 0 ? html` <span class="medal m-m">Ascension ${st.ascension}</span>` : null}
      ${t.endsAt > 0 ? html` <span class="medal m-g" title=${'Closes ' + endsText(t.endsAt)}>Limited time</span>` : null}
      ${run ? html` <span class="medal m-p">Run in progress</span>` : null}</div>
    <div class="res">${res.map((s) => html`<${DeskSprite} key=${s} name=${s} w=${4.4} />`)}</div></button>`;
}

/** @param {{hall: object, busy: boolean}} props */
export function DeskHall({ hall, busy }) {
  const extras = hall.extras;
  const [pick, setPick] = useState(() => defaultPick(hall));
  // a hub host or portal opening the Hall again, on another tower, moves the pick there
  useEffect(() => { if (extras?.focusTower) setPick(extras.focusTower); }, [extras?.focusTower]);
  // the guide opens by itself the first time this browser shows the Hall
  useEffect(() => { if (firstVisit()) update((s) => (s.view === 'hall' ? goTo(s, 'guide') : s)); }, []);
  const chosen = hall.towers.find((t) => t.id === pick) || hall.towers[0];
  const runTower = extras?.runTower ? hall.towers.find((t) => t.id === extras.runTower) : null;
  const trial = (kind) => hall.trials.find((t) => t.kind === kind);
  const inRun = hall.runStatus !== '';
  const lobbyRole = hall.play.lobby.role;
  return html`<div class="fd"><${TopBar} />
    <div class="dbody" style="grid-template-columns:34fr 66fr;flex:1">
      <${Team} party=${extras?.party ?? []} mode=${modeLine(extras)} />
      <div style="display:flex;flex-direction:column;gap:calc(var(--u) * .9);min-height:0">
        ${inRun ? html`<div class="cont run"><${Emblem} tower=${extras?.runTower || ''} w=${4.6} /><div style="flex:1"><small>Run in progress</small><b>${runTower ? shortName(runTower.name) + ' Tower' : 'Your tower'} · ${prettyStatus(hall.runStatus)}</b></div>
          <button class="dbtn dsm" disabled=${busy || !hall.canResume} onClick=${() => request('resume')}>${hall.canResume ? 'Return to run' : 'Back at the next intermission'}</button></div>`
          : lobbyRole !== 0 ? html`<div class="cont run"><${Emblem} tower="other" w=${4.6} /><div style="flex:1"><small>Expedition lobby</small><b>Your party is gathering</b></div><button class="dbtn dsm" disabled=${busy} onClick=${() => request('lobby')}>Open lobby</button></div>` : null}
        <div class="rows">${hall.towers.length === 0 ? html`<div class="empty">No towers are available.</div>` : [...hall.towers].sort((a, b) => (b.endsAt > 0) - (a.endsAt > 0)).map((t) => html`<${TowerRow} key=${t.id} t=${t} extras=${extras} selected=${t.id === chosen?.id} onPick=${() => setPick(t.id)} />`)}</div>
        <div style="display:flex;gap:calc(var(--u) * 1);align-items:center;flex-wrap:wrap">
          <button class="dbtn gh dsm" disabled=${!trial('daily')} onClick=${() => update((s) => goTo(s, 'trial', 'daily'))}>${trial('daily') ? trial('daily').title.replace(/^Daily Trial: /, 'Daily · ') : 'Daily'}</button>
          <button class="dbtn gh dsm" disabled=${!trial('weekly')} onClick=${() => update((s) => goTo(s, 'trial', 'weekly'))}>${trial('weekly') ? trial('weekly').title.replace(/^Weekly Trial: /, 'Weekly · ') : 'Weekly'}</button>
          <button class="dbtn gh dsm" disabled=${busy} onClick=${() => request('lobby')}>Lobby</button>
          <span style="flex:1"></span>
          <button class="dbtn" disabled=${!chosen} onClick=${() => chosen && update((s) => goTo(s, 'tower', chosen.id))}>${chosen ? 'Climb ' + shortName(chosen.name) + ' →' : 'Choose a tower'}</button></div>
      </div></div>
    <div class="dft"><span class="st">${prettyStatus(hall.runStatus) || (lobbyRole === 0 ? 'Choose a tower to begin.' : 'Your expedition lobby is available.')}</span></div></div>`;
}
