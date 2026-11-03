import test from 'node:test';
import assert from 'node:assert/strict';
import { initial, reduce, closeReward, goTo, goBack, begin, timeout } from '../src/reducer.js';

const inter = (open) => ({ floor: 2, draft: null, members: [], countdown: -1, message: '', open });

test('an intermission that is not flagged open does not open a page by itself', () => {
  const s = reduce(initial, 'IntermissionState', inter(false), 1);
  assert.equal(s.screen, null);
  assert.equal(s.inter.floor, 2);
});

test('an open intermission shows, and later updates keep showing it', () => {
  let s = reduce(initial, 'IntermissionState', inter(true), 1);
  assert.equal(s.screen, 'intermission');
  s = reduce(s, 'IntermissionState', inter(false), 5);
  assert.equal(s.screen, 'intermission');
  assert.equal(s.interAt, 5);
});

test('the reward reveal sits over the screen and closes the vendor', () => {
  let s = reduce(initial, 'VendorCatalog', { services: [], team: [], cobbleDollars: 1, message: '' }, 1);
  assert.equal(s.vendorOpen, true);
  s = reduce(s, 'RewardReveal', { floorIndex: 3, grants: [] }, 2);
  assert.equal(s.vendorOpen, false);
  assert.equal(s.reward.floorIndex, 3);
  assert.equal(closeReward(s).reward, null);
});

test('a vendor catalog that arrives behind a reward does not pop over it', () => {
  let s = reduce(initial, 'RewardReveal', { floorIndex: 1, grants: [] }, 1);
  s = reduce(s, 'VendorCatalog', { services: [], team: [], cobbleDollars: 1, message: '' }, 2);
  assert.equal(s.vendorOpen, false);
});

test('a rental draft opens its screen and its message becomes a notice', () => {
  const s = reduce(initial, 'RentalDraft', { packs: [], current: 0, complete: false, message: 'Pick two.' }, 1);
  assert.equal(s.screen, 'draft');
  assert.equal(s.notice.text, 'Pick two.');
  const t = reduce(s, 'RentalDraft', { packs: [], current: 1, complete: false, message: 'Pick two.' }, 2);
  assert.notEqual(t.notice.id, s.notice.id, 'the same text again is a new notice');
});

test('a spectator panel only feeds the HUD slice, and remembers when it arrived', () => {
  const s = reduce(initial, 'SpectatorPanel', { teammateName: 'Ash', remainingCount: 4, totalCount: 6, floorIndex: 7, runStateLabel: 'In battle' }, 1234);
  assert.equal(s.spec.teammateName, 'Ash');
  assert.equal(s.specAt, 1234);
  assert.equal(s.screen, null);
});

const play = (over = {}) => ({ towers: [], lobby: { role: 0, selected: '', hostName: '', members: [], countdown: -1, options: { depth: { chosen: 0, max: 0, offered: false }, modes: { ids: [], names: [], chosen: '', rental: false, readiness: { confirmed: false, drafted: false, mine: false, host: false } } } }, partyLevels: [], message: '', open: false, ...over });
const hallData = (p = play()) => ({ towers: [], trials: [], play: p, runStatus: '', canResume: false });

test('the hall payload opens the hall and clears a pending request', () => {
  let s = begin(initial, 5);
  assert.equal(s.busyAt, 5);
  s = reduce(s, 'TowerHallState', hallData(), 9);
  assert.equal(s.screen, 'hall');
  assert.equal(s.view, 'hall');
  assert.equal(s.busyAt, 0);
  assert.equal(s.play.lobby.role, 0);
});

test('a flagged play state opens the lobby over the hall and back returns to it', () => {
  let s = reduce(initial, 'TowerHallState', hallData(), 1);
  s = goTo(s, 'tower', 'cobbletowers:neutral');
  s = reduce(s, 'PlayState', play({ open: true }), 2);
  assert.equal(s.view, 'lobby');
  s = goBack(s);
  assert.equal(s.view, 'tower');
  s = goBack(goBack(s));
  assert.equal(s.view, 'hall');
});

test('an unflagged play state only refreshes the lobby slice', () => {
  const s = reduce(initial, 'PlayState', play({ open: false }), 1);
  assert.equal(s.screen, null);
  assert.equal(s.play.lobby.role, 0);
  const t = reduce(reduce(initial, 'TowerHallState', hallData(), 1), 'PlayState', play({ open: false }), 2);
  assert.equal(t.view, 'hall');
});

test('a repeated hall payload keeps the view the player was on', () => {
  let s = reduce(initial, 'TowerHallState', hallData(), 1);
  s = goTo(s, 'trial', 'daily');
  s = reduce(s, 'TowerHallState', hallData(), 2);
  assert.equal(s.view, 'trial');
});

test('a request that gets no answer times out into a notice', () => {
  const s = timeout(begin(initial, 1));
  assert.equal(s.busyAt, 0);
  assert.match(s.notice.text, /No response/);
  assert.equal(timeout(initial), initial);
});

test('an unknown kind changes nothing', () => {
  assert.equal(reduce(initial, 'SomethingNew', {}, 1), initial);
});

test('an opening RegistrationState shows the chooser; Done (the lobby state) returns without growing the stack', async () => {
  const { reduce, initial } = await import('../src/reducer.js');
  const lobby = reduce(initial, 'PlayState', { open: true, message: '', lobby: {}, towers: [], partyLevels: [] }, 1);
  const open = reduce(lobby, 'RegistrationState', { pokemon: [], chosen: [], max: 6, message: '', open: true }, 2);
  assert.equal(open.view, 'party');
  const pushed = reduce(open, 'RegistrationState', { pokemon: [], chosen: ['a'], max: 6, message: '', open: false }, 3);
  assert.equal(pushed.view, 'party');
  assert.deepEqual(pushed.reg.chosen, ['a']);
  const back = reduce(pushed, 'PlayState', { open: true, message: '', lobby: {}, towers: [], partyLevels: [] }, 4);
  assert.equal(back.view, 'lobby');
  assert.equal(back.stack.length, lobby.stack.length);
});

test('the mastery screen opens only when flagged; a reply fills it without opening', async () => {
  const { reduce, initial, goTo } = await import('../src/reducer.js');
  const hall = reduce(initial, 'TowerHallState', { play: {}, towers: [] }, 1);
  const reply = reduce(hall, 'MasteryScreen', { open: false, towers: [], selected: '', tab: 'mastery' }, 2);
  assert.equal(reply.view, 'hall');
  assert.equal(goTo(reply, 'mastery').view, 'mastery');
  const opened = reduce(hall, 'MasteryScreen', { open: true, towers: [], selected: '', tab: 'mastery' }, 3);
  assert.equal(opened.view, 'mastery');
});

test('the Haunted rare tray feeds only the HUD slice, and a closed one clears it', () => {
  const open = { open: true, name: 'Phantump', level: 22, chancePercent: 27, marks: '---', message: '' };
  const s = reduce(initial, 'HauntedRareTray', open, 77);
  assert.equal(s.tray.name, 'Phantump');
  assert.equal(s.trayAt, 77);
  assert.equal(s.screen, null);
  const t = reduce(s, 'HauntedRareTray', { ...open, open: false }, 99);
  assert.equal(t.tray, null);
  assert.equal(t.trayAt, 99);
});
