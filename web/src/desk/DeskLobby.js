// The Frontier Desk look for the lobby (P51 slice 3): the tower, the party's seats with who is ready, and the settings with Start.
// Same actions and the same rules as the native lobby (screens/Lobby.js); lobbyView() decides what is on offer.
import { useState } from 'preact/hooks';
import { html } from '../html.js';
import { act, closeGui, myName } from '../bridge.js';
import { get, update } from '../store.js';
import { goBack } from '../reducer.js';
import { lobbyView, partyLine, regionOf } from '../lobby.js';
import { DeskPano, DeskSprite } from './DeskHall.js';
import { modeName, rosterOf, seatLine, seatsOf, startHint } from './seats.js';
import { shortName } from './standings.js';
import { Logo } from './Logo.js';

const play = (action, argument = '') => act('PlayAction', { action, argument });

function Invite() {
  const [name, setName] = useState('');
  const send = () => { if (name.trim()) { play('INVITE', name.trim()); setName(''); } };
  return html`<div class="dinvite"><input class="dedit" maxlength="16" placeholder="Player name" aria-label="Player to invite" value=${name}
    onInput=${(e) => setName(e.target.value)} onKeyDown=${(e) => { e.stopPropagation(); if (e.key === 'Enter') send(); }} />
    <button class="dbtn dsm" onClick=${send}>Invite</button></div>`;
}

function Seat({ seat, invite, party, levels, roster }) {
  if (seat.kind === 'open') {
    return html`<div class="dseat open"><div class="dopen">Open seat</div>${invite ? html`<${Invite} />` : null}</div>`;
  }
  return html`<div class=${'dseat' + (seat.me ? ' me' : '')}>
    <div class="dseathead"><span class="av">${seat.name.charAt(0).toUpperCase()}</span>
      <div style="flex:1;min-width:0"><b>${seat.name}${seat.me ? ' (you)' : ''}</b><small>${seatLine(seat)}</small></div>
      ${seat.status ? html`<span class=${'stp stp-' + seat.pill}>${seat.status.charAt(0).toUpperCase() + seat.status.slice(1)}</span>` : null}</div>
    ${seat.me ? html`<div class="dseatparty">${party.length ? party.map((m, i) => html`<span key=${i} class=${m.fainted ? 'out' : ''} title=${m.name + ' Lv ' + m.level}><${DeskSprite} name=${m.species} w=${4.6} /></span>`) : html`<small>Your party: ${partyLine(levels)}</small>`}</div>` : roster.length ? html`<div class="dseatparty">${roster.map((m, i) => html`<span key=${i} class=${m.fainted ? 'out' : ''} title=${m.name + ' Lv ' + m.level}><${DeskSprite} name=${m.species} w=${4.6} /></span>`)}</div>` : null}
  </div>`;
}

/** @param {{state: object}} props state is the PlayState */
export function DeskLobby({ state }) {
  const lobby = state.lobby;
  const v = lobbyView(state);
  const me = myName();
  const seats = seatsOf(v, me);
  const firstOpen = seats.findIndex((s) => s.kind === 'open'); // one invite box, on the first open seat
  const hall = get().hall;
  const party = hall?.extras?.party ?? [];
  const modes = lobby.options.modes;
  const host = v.role === 1;
  // the server's own messages arrive as toasts; this line is only for a state the page can see by itself
  const message = v.waitingForHost ? 'Waiting for the host to confirm the mode.' : '';
  return html`<div class="fd">
    <div class="dtop"><${Logo} /><span class="dcrumb"><b>Lobby</b>${lobby.hostName ? ' · Host ' + lobby.hostName : ''}</span>
      <span class="sp2"></span><button class="tl" onClick=${() => update(goBack)}>Back</button><button class="x" aria-label="Close" onClick=${closeGui}>×</button></div>
    <div class="dbody" style="grid-template-columns:27fr 43fr 30fr;flex:1">
      <div class="dcard dcol"><div class="ch"><h3>Tower</h3><span class="lbl">${v.canChoose ? 'Choose one' : 'Set by the host'}</span></div>
        <div class="rows">${state.towers.map((t) => html`<button key=${t.id} class=${'trow dl' + (t.id === lobby.selected ? ' sel' : '')} disabled=${!v.canChoose} aria-pressed=${t.id === lobby.selected}
          onClick=${() => play('SELECT_TOWER', t.id)}><div class="pbox"><${DeskPano} region=${regionOf(t.id)} /></div><div><b>${shortName(t.displayName)}</b><small>${t.id === lobby.selected ? 'Selected' : 'Open expedition'}</small></div></button>`)}</div></div>
      <div class="dcard dcol"><div class="ch"><h3>Party</h3><span class="lbl">${seats.filter((s) => s.kind === 'player').length} of ${seats.length}</span></div>
        <div class="dseats">${seats.map((s, i) => html`<${Seat} key=${i} seat=${s} invite=${v.invite && i === firstOpen} party=${party} levels=${state.partyLevels} roster=${s.kind === 'player' ? rosterOf(state, s.name) : []} />`)}</div>
        ${message ? html`<div class="elig info">${message}</div>` : null}</div>
      <div class="dcard dcol dside"><div class="ch"><h3>Settings</h3><span class="lbl">${modeName(state)}</span></div>
        <div class="dctl">
          ${v.modes ? html`<span class="lbl">Mode</span><div class="seg">${v.modes.ids.map((id, i) => html`<button key=${id} class=${v.modes.chosen === id ? 'on' : ''} disabled=${!v.modes.enabled} aria-pressed=${v.modes.chosen === id}
            onClick=${() => play('SET_PLAYLIST', id)}>${v.modes.names[i]}</button>`)}</div>` : null}
          ${v.threat ? html`<span class="lbl">Threat${v.threat.adviceLabel ? html` <span class="hint">(${v.threat.adviceLabel})</span>` : null}</span><div class="seg threat">${v.threat.bands.map((b) => html`<button key=${b.band} class=${(v.threat.chosen === b.band ? 'on ' : '') + 'tone-' + b.tone} disabled=${!v.threat.enabled} aria-pressed=${v.threat.chosen === b.band}
            onClick=${() => play('SET_THREAT', String(b.band))}>${b.label}</button>`)}</div>` : null}
          ${v.depth ? html`<button class="dbtn gh dsm" disabled=${!v.depth.enabled} title=${v.depth.none ? 'No Ascension reached yet' : ''} onClick=${() => play('SET_ASCENSION', String(v.depth.next))}>Start at: ${v.depth.label}</button>` : null}
          ${v.confirm ? html`<button class="dbtn dsm" onClick=${() => play('CONFIRM_MODE')}>Confirm mode</button>` : null}
          ${v.draft ? html`<button class="dbtn gh dsm" onClick=${() => act('RentalDraftAction', { action: 'OPEN', a: 0, b: 0 })}>Draft your team</button>
            <button class=${'dbtn dsm' + (v.ready.mine ? ' gh' : '')} disabled=${!v.ready.enabled} onClick=${() => play(v.ready.mine ? 'UNREADY' : 'READY')}>${v.ready.mine ? 'Ready (click to undo)' : 'Ready up'}</button>` : null}
          ${v.answer ? html`<div style="display:flex;gap:calc(var(--u) * .8)"><button class="dbtn dsm" style="flex:1" onClick=${() => play('ACCEPT', lobby.hostName)}>Accept</button>
            <button class="dbtn gh dsm" style="flex:1" onClick=${() => play('DECLINE', lobby.hostName)}>Decline</button></div>` : null}
          ${v.role === 1 || v.role === 3 ? html`<button class="dbtn gh dsm" onClick=${() => play('OPEN_CHOOSER')}>Choose party</button>` : null}
        </div>
        <div class="dstart">
          ${v.start ? html`<button class="dbtn" disabled=${!v.start.enabled} onClick=${() => play('START')}>${v.start.label}</button><small>${startHint(v, state)}</small>` : null}
          ${v.leave ? html`<button class="dbtn gh dsm" onClick=${() => play('LEAVE')}>${v.leave}</button>` : html`<button class="dbtn gh dsm" onClick=${() => update(goBack)}>Close</button>`}
        </div></div>
    </div></div>`;
}
