// The whole page state as a pure function of what the server sent. No DOM, no timers: unit-tested in test/reducer.test.js.
// The rules mirror docs/design/webgui-bridge.md ("What a payload does when it arrives").

import { freshUi, uiAfterReply } from './features.js';

export const initial = Object.freeze({
  screen: null,      // 'intermission' | 'draft' | 'hall' | 'scout' | null (nothing to show yet)
  inter: null, interAt: 0,
  draft: null,
  reward: null,      // shown over any screen until dismissed
  vendor: null,      // the vendor catalog; vendorOpen says whether it is showing
  vendorOpen: false,
  hall: null,        // TowerHallState
  play: null,        // PlayState (also carried inside TowerHallState)
  scout: null,       // ScoutingReveal (screen 'scout')
  spec: null, specAt: 0, // SpectatorPanel, for the HUD page, and when it last arrived
  tray: null, trayAt: 0, // HauntedRareTray (open ones only), for the HUD page, and when it last arrived
  mast: null,        // MasteryScreen (view 'mastery')
  ranks: null,       // RanksState (the Ranks tab's region duel)
  reg: null,         // RegistrationState (the party chooser, view 'party')
  view: 'hall',      // inside the hall screen: 'hall' | 'tower' | 'trial' | 'lobby' | 'party' | 'mastery' | 'settings' | 'guide'
  tab: 'towers',     // hall tab: 'towers' | 'trials' | 'social' | 'progress' | 'ranks'
  page: 0,           // tower tile page in the hall
  pick: '',          // the tower id or trial kind a briefing shows
  stack: [],         // views to go back to
  busyAt: 0,         // when a hall request went out and was not answered yet (0 = none)
  tracks: null,      // TrackState (the Progress tab's battle tracks)
  tracksAt: 0,       // when it arrived: the season timer counts down from it
  lane: 'mastery',   // which lane shows
  records: false,    // the Progress card grid instead of the tracks
  tSel: {},          // selected tile per lane
  feature: null,     // TowerFeatureState
  fSection: '',      // the section last asked for
  fReq: 0,           // the page's own request counter
  fPending: 0,       // when a feature request went out and was not answered (0 = none)
  fUncertain: false, // no reply in time: refresh before another action
  ret: null,         // the screen a feature menu was opened over (e.g. 'intermission'), returned to when it closes
  fui: freshUi(),
  look: 'native',    // 'native' or 'desk': which look the Hall's Play page has (P51), from the page settings
  offline: false,    // the page has heard nothing from the server for a while (a banner says so)
  notice: null,      // {text, id}: the last message the server attached to a payload
  noticeId: 0,
});

function withNotice(state, text) {
  if (!text) return state;
  return { ...state, notice: { text, id: state.noticeId + 1 }, noticeId: state.noticeId + 1 };
}

/** @param {object} state @param {string} kind the payload kind @param {object} data @param {number} now ms */
export function reduce(state, kind, data, now) {
  switch (kind) {
    case 'IntermissionState': {
      const screen = data.open || state.screen === 'intermission' ? 'intermission' : state.screen;
      return { ...state, inter: data, interAt: now, screen };
    }
    case 'RentalDraft':
      return withNotice({ ...state, draft: data, screen: 'draft' }, data.message);
    case 'RewardReveal':
      return { ...state, reward: data, vendorOpen: false };
    case 'VendorCatalog':
      return withNotice({ ...state, vendor: data, vendorOpen: !state.reward }, data.message);
    case 'TowerHallState':
      return withNotice({ ...state, ret: null, hall: data, play: data.play, screen: 'hall', busyAt: 0, view: state.screen === 'hall' ? state.view : 'hall', stack: state.screen === 'hall' ? state.stack : [] }, data.play.message);
    case 'PlayState': {
      // Only a flagged state opens the lobby; an unflagged one just refreshes it.
      const base = { ...state, play: data, busyAt: 0 };
      return withNotice(data.open ? { ...showLobby(base) } : base, data.message);
    }
    case 'RegistrationState': {
      // Opens the chooser only when flagged (the player asked); an unflagged one refreshes an open chooser.
      const base = { ...state, reg: data, busyAt: 0 };
      return withNotice(data.open ? showParty(base) : base, data.message);
    }
    case 'MasteryScreen': {
      // Only a flagged one (the command) opens the screen; a reply to the page's own request fills the open one.
      const base = { ...state, mast: data };
      return data.open ? showView(base, 'mastery') : base;
    }
    case 'RanksState':
      return withNotice({ ...state, ranks: data }, data.message);
    case 'TrackState': {
      const towerMoved = state.tracks && state.tracks.selectedTower !== data.selectedTower;
      return withNotice({ ...state, tracks: data, tracksAt: now, tSel: towerMoved ? { ...state.tSel, mastery: null } : state.tSel }, data.message);
    }
    case 'TowerFeatureState': {
      // A reply for another request is a late one; id 0 is the server opening the menu itself (a command).
      if (data.requestId !== 0 && (data.requestId !== state.fReq || data.section !== state.fSection)) return state;
      const same = state.feature && state.feature.section === data.section;
      const open = state.screen === 'hall' && state.view === 'feature';
      const next = { ...state, ret: state.screen !== 'hall' ? (state.ret || state.screen) : state.ret, feature: data, fSection: data.section, fPending: 0, fUncertain: false, screen: 'hall', view: 'feature',
        stack: open ? state.stack : state.screen === 'hall' ? [...state.stack, state.view] : [], fui: uiAfterReply(same ? state.fui : freshUi(), data) };
      return withNotice(next, data.message);
    }
    case 'ScoutingReveal':
      return { ...state, scout: data, screen: 'scout' };
    case 'SpectatorPanel':
      return { ...state, spec: data, specAt: now };
    case 'HauntedRareTray':
      return { ...state, tray: data && data.open ? data : null, trayAt: now };
    default:
      return state;
  }
}

/** The player dismissed the scouting report. */
export const closeScout = (state) => ({ ...state, scout: null, screen: state.screen === 'scout' ? null : state.screen });

/** The player dismissed the reward reveal. */
export const closeReward = (state) => ({ ...state, reward: null });
export const openVendor = (state, open) => ({ ...state, vendorOpen: open });

function showView(state, view) {
  if (state.screen === 'hall' && state.view === view) return state;
  const stack = state.screen === 'hall' ? [...state.stack, state.view] : [];
  return { ...state, screen: 'hall', view, stack };
}
const showParty = (state) => showView(state, 'party');

function showLobby(state) {
  if (state.screen === 'hall' && state.view === 'lobby') return state;
  // The chooser's Done answers with the lobby: that is a return, not a new view.
  if (state.screen === 'hall' && state.view === 'party') return goBack(state);
  const stack = state.screen === 'hall' ? [...state.stack, state.view] : [];
  return { ...state, screen: 'hall', view: 'lobby', stack };
}

/** Moves inside the hall screen. `pick` names the tower or trial a briefing shows. */
export const goTo = (state, view, pick = '') => ({ ...state, view, pick, stack: [...state.stack, state.view] });
export const goBack = (state) => (state.stack.length
  ? { ...state, view: state.stack[state.stack.length - 1], stack: state.stack.slice(0, -1) } : { ...state, view: 'hall' });
export const setTab = (state, tab) => ({ ...state, tab, view: 'hall', stack: [], page: 0 });
export const setLook = (state, look) => (state.look === look ? state : { ...state, look });
export const setPage = (state, page) => ({ ...state, page });
/** A hall request went out; the next hall or lobby state (or the timeout) clears it. */
export const begin = (state, now) => (state.busyAt ? state : { ...state, busyAt: now });
export const timeout = (state) => (state.busyAt ? { ...state, busyAt: 0, notice: { text: 'No response. Refresh to check the latest state.', id: state.noticeId + 1 }, noticeId: state.noticeId + 1 } : state);

/** The page asks for a menu section (or an action in it). The new request number is `state.fReq`. */
export function beginFeature(state, section, now) {
  const changed = section !== state.fSection;
  const open = state.view === 'feature';
  return { ...state, fReq: state.fReq + 1, fSection: section, fPending: now, fUncertain: false, view: 'feature',
    stack: open ? state.stack : [...state.stack, state.view], feature: changed ? null : state.feature, fui: changed ? freshUi() : state.fui };
}
export const featureDelayed = (state) => (state.fPending ? { ...state, fPending: 0, fUncertain: true } : state);
/** A feature menu closed with nowhere to step back to inside the hall: return to the screen it was opened over, if any. */
export const leaveFeature = (state) => (state.ret ? { ...state, screen: state.ret, ret: null, view: 'hall', stack: [] } : null);
export const setFui = (state, patch) => ({ ...state, fui: { ...state.fui, ...patch } });

export const setLane = (state, lane) => ({ ...state, lane, records: false });
export const setRecords = (state, records) => ({ ...state, records });
export const selectNode = (state, kind, index) => ({ ...state, tSel: { ...state.tSel, [kind]: index } });
