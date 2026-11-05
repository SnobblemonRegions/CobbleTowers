import test from 'node:test';
import assert from 'node:assert/strict';
import { lobbyView, canSelect, partyLine, regionOf } from '../src/lobby.js';

const readiness = (o = {}) => ({ confirmed: false, drafted: false, mine: false, host: false, ...o });
const play = (lobby = {}, modes = {}, depth = {}) => ({ towers: [], partyLevels: [], message: '', open: true, lobby: {
  role: 1, selected: 'cobbletowers:tideforge', hostName: 'Host', members: [], countdown: -1,
  options: { depth: { chosen: 0, max: 0, offered: false, ...depth }, modes: { ids: ['rental', 'monotype'], names: ['Rental Draft', 'Monotype'], chosen: '', rental: false, readiness: readiness(), ...modes } }, ...lobby } });

test('a host who has not confirmed the mode sees Confirm and cannot start', () => {
  const v = lobbyView(play());
  assert.equal(v.confirm, true);
  assert.equal(v.start.enabled, false);
  assert.equal(v.leave, 'End team');
  assert.deepEqual(v.modes.ids, ['standard', 'rental', 'monotype']);
  assert.equal(v.modes.chosen, 'standard');
});

test('after confirming, a rental host drafts, readies once drafted, and can start', () => {
  const v = lobbyView(play({}, { rental: true, chosen: 'rental', readiness: readiness({ confirmed: true, drafted: false }) }));
  assert.equal(v.confirm, false);
  assert.equal(v.draft, true);
  assert.equal(v.ready.enabled, false);
  assert.equal(v.start.enabled, true);
  assert.equal(lobbyView(play({}, { rental: true, readiness: readiness({ confirmed: true, drafted: true }) })).ready.enabled, true);
});

test('a countdown locks every host control and shows in the start label', () => {
  const v = lobbyView(play({ countdown: 7 }, { readiness: readiness({ confirmed: true }) }));
  assert.equal(v.start.label, 'Starting in 7...');
  assert.equal(v.start.enabled, false);
  assert.equal(v.invite, false);
  assert.equal(v.canChoose, false);
  assert.equal(v.modes.enabled, false);
});

test('ascension depth cycles and wraps to the start', () => {
  assert.equal(lobbyView(play({}, {}, { offered: true, chosen: 1, max: 2 })).depth.next, 2);
  assert.equal(lobbyView(play({}, {}, { offered: true, chosen: 2, max: 2 })).depth.next, 0);
  assert.equal(lobbyView(play({}, {}, { offered: true, chosen: 0, max: 0 })).depth.enabled, false);
  assert.equal(lobbyView(play()).depth, null);
});

test('an invited player answers; a member waits for the host and can leave', () => {
  const invited = lobbyView(play({ role: 2 }));
  assert.equal(invited.answer, true);
  assert.equal(invited.start, null);
  assert.equal(invited.modes, null);
  const member = lobbyView(play({ role: 3 }));
  assert.equal(member.waitingForHost, true);
  assert.equal(member.leave, 'Leave');
});

test('with no lobby there is nothing to leave and towers can be chosen', () => {
  const v = lobbyView(play({ role: 0, selected: '' }));
  assert.equal(v.leave, null);
  assert.equal(v.canChoose, true);
  assert.deepEqual(v.team, []);
});

test('team statuses follow the rental ready-up, else acceptance', () => {
  const members = [{ name: 'A', accepted: true, ready: true }, { name: 'B', accepted: true, ready: false }, { name: 'C', accepted: false, ready: false }];
  const rental = lobbyView(play({ members }, { rental: true, readiness: readiness({ host: true }) })).team;
  assert.deepEqual(rental.map((m) => m.status), ['ready', 'ready', 'drafting', 'invited']);
  const plain = lobbyView(play({ members })).team;
  assert.deepEqual(plain.map((m) => m.status), ['host', 'ready', 'ready', 'invited']);
});

test('selecting from a briefing needs no run, no countdown, no guest role, and no pending request', () => {
  const hall = (role, countdown = -1, runStatus = '') => ({ runStatus, play: { lobby: { role, countdown } } });
  assert.equal(canSelect(hall(0), false), true);
  assert.equal(canSelect(hall(1), false), true);
  assert.equal(canSelect(hall(3), false), false);
  assert.equal(canSelect(hall(0, 5), false), false);
  assert.equal(canSelect(hall(0, -1, 'In a run'), false), false);
  assert.equal(canSelect(hall(0), true), false);
});

test('small helpers', () => {
  assert.equal(partyLine([50, 52]), 'Lv 50, Lv 52');
  assert.equal(partyLine([]), 'no Pokemon');
  assert.equal(regionOf('cobbletowers:duskvale'), 'duskvale');
  assert.equal(regionOf('cobbletowers:test'), 'neutral');
});

test('the Threat picker colours the bands by advice and cycles 0-VI (balance phase 2)', async () => {
  const { threatView } = await import('../src/lobby.js');
  assert.equal(threatView(undefined, true), null);
  assert.equal(threatView({ chosen: 0, advice: 3, offered: false }, true), null, 'not offered: no picker');
  const t = threatView({ chosen: 4, advice: 3, offered: true }, true);
  assert.equal(t.label, 'Threat IV');
  assert.equal(t.tone, 'warn');
  assert.equal(t.adviceLabel, 'Recommended: Threat III');
  assert.deepEqual(t.bands.map((b) => b.tone), ['plain', 'good', 'good', 'good', 'warn', 'bad', 'bad']);
  assert.equal(threatView({ chosen: 6, advice: 3, offered: true }, true).next, 0);
  const v = lobbyView(play({ options: { depth: { chosen: 0, max: 0, offered: false }, modes: { ids: [], names: [], chosen: '', rental: false, readiness: readiness() }, threat: { chosen: 2, advice: 2, offered: true } } }));
  assert.equal(v.threat.tone, 'good');
});
