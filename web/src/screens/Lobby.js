import { useState } from 'preact/hooks';
import { html } from '../html.js';
import { act } from '../bridge.js';
import { update } from '../store.js';
import { goBack } from '../reducer.js';
import { lobbyView, partyLine } from '../lobby.js';
import { at, Icon, Label, NB, Panel, screen, useScreen } from '../components/native.js';

const play = (action, argument = '') => act('PlayAction', { action, argument });

function Invite({ x, y, w }) {
  const [name, setName] = useState('');
  const send = () => { if (name.trim()) { play('INVITE', name.trim()); setName(''); } };
  return html`
    <input class="n-edit" style=${at(x, y, w - 70, 20)} maxlength="16" placeholder="Name" value=${name} onInput=${(e) => setName(e.target.value)}
      onKeyDown=${(e) => { e.stopPropagation(); if (e.key === 'Enter') send(); }} />
    <${NB} x=${x + w - 66} y=${y} w=${66} onClick=${send}>Invite<//>`;
}

/** TrainerCardButton for a tower: name, "Open expedition" or "Selected", and a picture. */
function TowerCard({ x, y, w, h, name, selected, disabled, onClick }) {
  return html`<button class=${'n-btn n-fr n-dark card' + (selected ? ' sel' : '')} disabled=${disabled} style=${at(x, y, w, h)} onClick=${onClick}>
    <${Label} x=${-2} y=${h > 26 ? -2 : -5} w=${w - 30}>${name}<//>
    <${Label} x=${-2} y=${h > 26 ? 11 : 4} w=${w - 30} color="muted">${selected ? 'Selected' : 'Open expedition'}<//>
    <${Icon} name="tower" x=${w - 30} y=${h / 2 - 16} /><i class="brk"></i></button>`;
}

export function Lobby({ state }) {
  useScreen();
  const SW = screen.w, SH = screen.h, STATUS_W = Math.max(104, Math.floor(SW * 0.3));
  const lobby = state.lobby;
  const v = lobbyView(state);
  const column = Math.max(96, Math.floor((SW - STATUS_W - 32) / 2));
  const half = Math.floor((column - 4) / 2);
  const leftX = 12, rightX = 18 + column;
  const step = Math.max(18, Math.min(40, Math.floor((SH - 66) / Math.max(1, state.towers.length))));
  let ry = 40;
  const next = () => { const y = ry; ry += 22; return y; };

  const controls = [];
  if (v.invite) controls.push(html`<${Invite} x=${rightX} y=${next()} w=${column} />`);
  if (v.depth) controls.push(html`<${NB} x=${rightX} y=${next()} w=${column} disabled=${!v.depth.enabled} tip=${v.depth.none ? 'No Ascension reached yet' : ''} onClick=${() => play('SET_ASCENSION', String(v.depth.next))}>Start at: ${v.depth.label}<//>`);
  if (v.threat) controls.push(html`<${NB} x=${rightX} y=${next()} w=${column} disabled=${!v.threat.enabled} tip=${v.threat.adviceLabel} onClick=${() => play('SET_THREAT', String(v.threat.next))}>${v.threat.label}<//>`);
  if (v.modes) {
    const i = v.modes.ids.indexOf(v.modes.chosen);
    const nextId = v.modes.ids[(i + 1) % v.modes.ids.length];
    controls.push(html`<${NB} x=${rightX} y=${next()} w=${column} disabled=${!v.modes.enabled} onClick=${() => play('SET_PLAYLIST', nextId)}>Mode: ${v.modes.names[Math.max(0, i)]}<//>`);
  }
  if (v.confirm) controls.push(html`<${NB} x=${rightX} y=${next()} w=${column} onClick=${() => play('CONFIRM_MODE')}>Confirm mode<//>`);
  if (v.draft) {
    controls.push(html`<${NB} x=${rightX} y=${next()} w=${column} onClick=${() => act('RentalDraftAction', { action: 'OPEN', a: 0, b: 0 })}>Draft your team<//>`);
    controls.push(html`<${NB} x=${rightX} y=${next()} w=${column} disabled=${!v.ready.enabled} onClick=${() => play(v.ready.mine ? 'UNREADY' : 'READY')}>${v.ready.mine ? 'Ready (click to undo)' : 'Ready up'}<//>`);
  }
  if (v.answer) {
    const y = next();
    controls.push(html`<${NB} x=${rightX} y=${y} w=${half} onClick=${() => play('ACCEPT', lobby.hostName)}>Accept<//>`);
    controls.push(html`<${NB} x=${rightX + half + 4} y=${y} w=${half} onClick=${() => play('DECLINE', lobby.hostName)}>Decline<//>`);
  }
  if (v.leave) {
    const host = v.role === 1;
    controls.push(html`<${NB} x=${host ? rightX + half + 4 : rightX} y=${next()} w=${host ? half : column} onClick=${() => play('LEAVE')}>${v.leave}<//>`);
  } else controls.push(html`<${NB} x=${rightX} y=${next()} w=${column} onClick=${() => update(goBack)}>Close<//>`);
  if (v.role === 1 || v.role === 3) controls.push(html`<${NB} x=${rightX} y=${next()} w=${column} onClick=${() => play('OPEN_CHOOSER')}>Choose party<//>`);

  const teamX = SW - STATUS_W + 7;
  const textW = STATUS_W - 16;
  return html`
    <div class="ns" style=${`--g:${screen.g}px`}>
      <${Label} x=${0} y=${14} w=${SW} center>Battle Tower<//>
      <${Label} x=${12} y=${26} w=${SW - STATUS_W - 24} color="muted">Your party: ${partyLine(state.partyLevels)}<//>
      <${Panel} x=${6} y=${34} w=${SW - STATUS_W - 10} h=${SH - 56} />
      <${Panel} x=${SW - STATUS_W} y=${34} w=${STATUS_W - 8} h=${SH - 56} />
      <${Label} x=${teamX} y=${42} color="muted">TEAM STATUS<//>
      ${state.towers.map((t, i) => html`<${TowerCard} key=${t.id} x=${leftX} y=${40 + i * step} w=${column} h=${step - 4} name=${t.displayName} selected=${t.id === lobby.selected}
        disabled=${!v.canChoose} onClick=${() => play('SELECT_TOWER', t.id)} />`)}
      ${controls}
      ${v.team.map((m, i) => html`<${Label} key=${m.name} x=${teamX} y=${55 + i * 10} w=${textW} color=${m.tone === 'good' ? 'sage' : m.host ? 'bronze' : 'muted'}>${m.host ? 'Host: ' : ''}${m.name}  ${m.status === 'host' ? '' : m.status}<//>`)}
      ${v.waitingForHost ? html`<${Label} x=${teamX} y=${55 + v.team.length * 10 + 4} w=${textW} color="muted">Waiting for the host to confirm the mode<//>` : null}
      ${v.start ? html`<${NB} x=${SW - STATUS_W + 6} y=${SH - 48} w=${STATUS_W - 20} h=${22} disabled=${!v.start.enabled} onClick=${() => play('START')}>${v.start.label}<//>` : null}
      <${NB} x=${SW - 16} y=${4} w=${12} h=${12} kind="dark" onClick=${() => update(goBack)} tip="Back">X<//>
    </div>`;
}
