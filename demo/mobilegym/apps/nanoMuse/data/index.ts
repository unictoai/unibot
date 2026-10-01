import defaults from './defaults.json';
import { manifest } from '../manifest';

/**
 * Initial runtime state. Leave `serverUrl` empty to get the setup page on first launch, or
 * fill it in (e.g. "http://127.0.0.1:8787") to have the phone come up already connected.
 *
 * `demoGateway` is the hosted showcase's gateway (`/api/demo` on the public site), set at
 * build time with `VITE_UNIBOT_DEMO`. Empty in a normal checkout: the app then asks for the
 * address of your own unibot.
 */
export const UNIBOT_CONFIG = {
  ...defaults,
  appId: manifest.id,
  demoGateway: String(import.meta.env.VITE_UNIBOT_DEMO ?? '').trim(),
};
