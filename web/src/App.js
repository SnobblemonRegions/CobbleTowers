import { useEffect } from 'preact/hooks';
import { html } from './html.js';
import { useStore, update } from './store.js';
import { closeReward, openVendor } from './reducer.js';
import { act, isHud } from './bridge.js';
import { Toast } from './components/ui.js';
import { Intermission } from './screens/Intermission.js';
import { Draft } from './screens/Draft.js';
import { Hud } from './screens/Hud.js';
import { Hall, useBusyTimeout } from './screens/Hall.js';
import { DeskHall } from './desk/DeskHall.js';
import { DeskIntermission } from './desk/DeskIntermission.js';
import { DeskLobby } from './desk/DeskLobby.js';
import { DeskToast } from './desk/DeskToast.js';
import { DeskProgress } from './desk/DeskProgress.js';
import { DeskRanks } from './desk/DeskRanks.js';
import { DeskSocial, DeskTowerBrief, DeskTrialBrief, DeskTrials } from './desk/DeskBriefs.js';
import { DeskFeatures } from './desk/DeskFeatures.js';
import { DeskGuide, DeskMastery, DeskParty, DeskSettings } from './desk/DeskMore.js';
import { DeskDraft, DeskReward, DeskScouting, DeskVendor, DeskWaiting } from './desk/DeskModals.js';
import { Lobby } from './screens/Lobby.js';
import { Party } from './screens/Party.js';
import { Mastery } from './screens/Mastery.js';
import { Scouting } from './screens/Scouting.js';
import { RewardModal, VendorModal } from './screens/Modals.js';

/** Fits the 1280x720 stage to the window with a single scale. */
function useFit() {
  useEffect(() => {
    const fit = () => document.getElementById('stage')?.style.setProperty('--k', Math.min(window.innerWidth / 1280, window.innerHeight / 720));
    window.addEventListener('resize', fit);
    fit();
    return () => window.removeEventListener('resize', fit);
  }, []);
}

/** The server's one-line messages show for a few seconds. */
function useNoticeTimeout(notice, clear) {
  useEffect(() => {
    if (!notice) return undefined;
    const id = setTimeout(clear, 4000);
    return () => clearTimeout(id);
  }, [notice]);
}

function Waiting() {
  return html`<div class="grow" style="display:grid;place-items:center"><div class="nine dark" style="text-align:center"><h2>CobbleTowers</h2><div class="sub">Waiting for the tower…</div></div></div>`;
}

export function App() {
  const screen = useStore((s) => s.screen);
  const inter = useStore((s) => s.inter);
  const interAt = useStore((s) => s.interAt);
  const draft = useStore((s) => s.draft);
  const reward = useStore((s) => s.reward);
  const vendor = useStore((s) => s.vendor);
  const vendorOpen = useStore((s) => s.vendorOpen);
  const notice = useStore((s) => s.notice);
  const offline = useStore((s) => s.offline);
  const hall = useStore((s) => s.hall);
  const play = useStore((s) => s.play);
  const reg = useStore((s) => s.reg);
  const mast = useStore((s) => s.mast);
  const ranks = useStore((s) => s.ranks);
  const scout = useStore((s) => s.scout);
  const view = useStore((s) => s.view);
  const tab = useStore((s) => s.tab);
  const pick = useStore((s) => s.pick);
  const page = useStore((s) => s.page);
  const busyAt = useStore((s) => s.busyAt);
  const look = useStore((s) => s.look);
  useBusyTimeout(busyAt);
  useFit();
  // a reward or the vendor drawn over the waiting page is still drawn in GUI pixels, so it needs the native layout too
  const native = !!reward || (vendorOpen && !!vendor) || screen === 'scout' || screen === 'intermission' || screen === 'draft' || screen === 'hall' && (view === 'lobby' ? !!play : view === 'party' ? !!reg : view === 'mastery' ? !!mast : !!hall || view === 'feature');
  useEffect(() => { document.documentElement.classList.toggle('native', native); }, [native]);
  useNoticeTimeout(notice, () => update((s) => (s.notice === notice ? { ...s, notice: null } : s)));

  // the Frontier Desk look has its own toast, on the screens it draws
  const deskNow = look === 'desk' && (!!reward || (vendorOpen && !!vendor) || (screen === 'intermission' ? !!inter
    : screen === 'hall' && (view === 'lobby' ? !!play : (view === 'hall' ? !!hall : view === 'tower' || view === 'trial' ? !!hall : view === 'mastery' ? !!mast : view === 'party' ? !!reg : true))));
  const closeRewardNow = () => { update(closeReward); act('IntermissionAction', { action: 'REFRESH', argument: 0 }); };

  return html`
    <div class="main">
      ${screen === 'scout' && scout ? (look === 'desk' ? html`<${DeskScouting} scout=${scout} />` : html`<${Scouting} scout=${scout} />`)
        : screen === 'intermission' && inter && look === 'desk' ? html`<${DeskIntermission} inter=${inter} at=${interAt} />`
        : screen === 'intermission' && inter ? html`<${Intermission} inter=${inter} at=${interAt} />`
        : screen === 'draft' && draft ? (look === 'desk' ? html`<${DeskDraft} draft=${draft} />` : html`<${Draft} draft=${draft} />`)
        : screen === 'hall' && look === 'desk' && view === 'mastery' && mast ? html`<${DeskMastery} state=${mast} />`
        : screen === 'hall' && look === 'desk' && view === 'party' && reg ? html`<${DeskParty} reg=${reg} />`
        : screen === 'hall' && look === 'desk' && view === 'guide' ? html`<${DeskGuide} />`
        : screen === 'hall' && look === 'desk' && view === 'settings' ? html`<${DeskSettings} />`
        : screen === 'hall' && view === 'mastery' && mast ? html`<${Mastery} state=${mast} />`
        : screen === 'hall' && view === 'party' && reg ? html`<${Party} reg=${reg} />`
        : screen === 'hall' && view === 'lobby' && play && look === 'desk' ? html`<${DeskLobby} state=${play} />`
        : screen === 'hall' && view === 'lobby' && play ? html`<${Lobby} state=${play} />`
        : screen === 'hall' && look === 'desk' && view === 'hall' && tab === 'towers' && hall ? html`<${DeskHall} hall=${hall} busy=${!!busyAt} />`
        : screen === 'hall' && look === 'desk' && view === 'hall' && tab === 'progress' && hall ? html`<${DeskProgress} hall=${hall} />`
        : screen === 'hall' && look === 'desk' && view === 'hall' && tab === 'trials' && hall ? html`<${DeskTrials} hall=${hall} />`
        : screen === 'hall' && look === 'desk' && view === 'hall' && tab === 'social' && hall ? html`<${DeskSocial} />`
        : screen === 'hall' && look === 'desk' && view === 'hall' && tab === 'ranks' && hall ? html`<${DeskRanks} ranks=${ranks} />`
        : screen === 'hall' && look === 'desk' && view === 'tower' && hall ? html`<${DeskTowerBrief} hall=${hall} id=${pick} busy=${!!busyAt} />`
        : screen === 'hall' && look === 'desk' && view === 'trial' && hall ? html`<${DeskTrialBrief} hall=${hall} kind=${pick} busy=${!!busyAt} />`
        : screen === 'hall' && look === 'desk' && view === 'feature' ? html`<${DeskFeatures} />`
        : screen === 'hall' && (hall || view === 'feature') ? html`<${Hall} hall=${hall} tab=${tab} page=${page} view=${view} pick=${pick} busy=${!!busyAt} />`
        : look === 'desk' ? html`<${DeskWaiting} />` : html`<${Waiting} />`}
      ${reward ? (look === 'desk' ? html`<${DeskReward} reward=${reward} onClose=${closeRewardNow} />` : html`<${RewardModal} reward=${reward} onClose=${closeRewardNow} />`)
        : vendorOpen && vendor ? (look === 'desk' ? html`<${DeskVendor} vendor=${vendor} onClose=${() => update((s) => openVendor(s, false))} />` : html`<${VendorModal} vendor=${vendor} onClose=${() => update((s) => openVendor(s, false))} />`) : null}
      ${offline ? html`<div class="offline">Lost contact with the server. Waiting for it to answer...</div>` : null}
      ${deskNow ? html`<${DeskToast} notice=${notice} />` : html`<${Toast} notice=${notice} />`}
    </div>`;
}

export function HudApp() {
  const spec = useStore((s) => s.spec);
  const specAt = useStore((s) => s.specAt);
  const tray = useStore((s) => s.tray);
  const trayAt = useStore((s) => s.trayAt);
  return html`<${Hud} spec=${spec} specAt=${specAt} tray=${tray} trayAt=${trayAt} />`;
}

export { isHud };
