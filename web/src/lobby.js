const ROMAN = ['0', 'I', 'II', 'III', 'IV', 'V', 'VI', 'VII'];

/**
 * The Threat picker (balance phase 2), or null when the server does not offer one. Advice is a colour, never a lock:
 * at or under the advice is green, one over is yellow, further is red. 0 is the legacy tiers.
 * @param {{chosen:number, advice:number, offered:boolean}|undefined} threat
 */
export function threatView(threat, enabled) {
  if (!threat || !threat.offered) return null;
  const tone = (band) => band === 0 || threat.advice <= 0 ? 'plain' : band <= threat.advice ? 'good' : band === threat.advice + 1 ? 'warn' : 'bad';
  return {
    chosen: threat.chosen,
    advice: threat.advice,
    adviceLabel: threat.advice > 0 ? 'Recommended: Threat ' + ROMAN[threat.advice] : '',
    label: threat.chosen === 0 ? 'Threat: off' : 'Threat ' + ROMAN[threat.chosen],
    tone: tone(threat.chosen),
    next: threat.chosen >= 6 ? 0 : threat.chosen + 1,
    bands: [0, 1, 2, 3, 4, 5, 6].map((band) => ({ band, label: band === 0 ? 'Off' : ROMAN[band], tone: tone(band) })),
    enabled,
  };
}

// What the lobby page offers, as a pure function of PlayState (mirrors the native PlayScreen's rules). The server re-checks every action.

/** @param {object} play PlayState */
export function lobbyView(play) {
  const lobby = play.lobby;
  const { depth, modes } = lobby.options;
  const role = lobby.role; // 0 none, 1 host, 2 invited, 3 on the team
  const idle = lobby.countdown < 0;
  const ready = modes.readiness;
  const host = role === 1;
  const rentalPlayer = modes.rental && (host || role === 3) && ready.confirmed && idle;
  return {
    role,
    canChoose: (role === 0 || host) && idle,
    invite: host && idle,
    depth: host && depth.offered ? { label: depth.chosen === 0 ? 'Floor 1' : 'Ascension ' + depth.chosen, next: depth.chosen >= depth.max ? 0 : depth.chosen + 1, enabled: depth.max > 0 && idle, none: depth.max === 0 } : null,
    threat: host ? threatView(lobby.options.threat, idle) : null,
    modes: host && modes.ids.length ? { ids: ['standard', ...modes.ids], names: ['Standard', ...modes.names], chosen: modes.chosen || 'standard', enabled: idle } : null,
    confirm: host && lobby.selected !== '' && !ready.confirmed && idle,
    draft: rentalPlayer,
    ready: rentalPlayer ? { mine: ready.mine, enabled: ready.drafted } : null,
    answer: role === 2,
    start: host ? { label: idle ? 'Start' : 'Starting in ' + lobby.countdown + '...', enabled: lobby.selected !== '' && idle && ready.confirmed } : null,
    leave: role === 0 ? null : host ? 'End team' : 'Leave',
    waitingForHost: role === 3 && !ready.confirmed,
    team: role === 0 ? [] : [
      { name: lobby.hostName, host: true, tone: modes.rental && ready.host ? 'good' : 'plain', status: modes.rental && ready.host ? 'ready' : 'host' },
      ...lobby.members.map((m) => {
        const good = modes.rental ? m.ready : m.accepted;
        return { name: m.name, host: false, tone: good ? 'good' : 'plain', status: modes.rental ? (m.accepted ? (m.ready ? 'ready' : 'drafting') : 'invited') : (m.accepted ? 'ready' : 'invited') };
      })],
  };
}

/** Whether a tower or trial can be selected from a briefing: no run, no countdown, and not a guest in someone else's lobby. */
export const canSelect = (hall, busy) => !busy && hall.runStatus === '' && hall.play.lobby.countdown < 0 && (hall.play.lobby.role === 0 || hall.play.lobby.role === 1);

/** "Lv 50, Lv 52" or "no Pokemon" */
export const partyLine = (levels) => (levels.length ? levels.map((l) => 'Lv ' + l).join(', ') : 'no Pokemon');

/** Region of a tower id for its banner colours. */
export const regionOf = (id) => (/tideforge/.test(id) ? 'tideforge' : /rootvale/.test(id) ? 'rootvale' : /duskvale/.test(id) ? 'duskvale' : /halloween/.test(id) ? 'halloween' : 'neutral');
