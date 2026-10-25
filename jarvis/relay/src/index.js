// Jarvis relay: carries what you say to the "Jarvis" Alexa skill to the TV, which can't be reached
// from the internet, and carries the TV's answer back for Alexa to speak.
//
//   POST /alexa          the skill's endpoint (Alexa → here)
//   GET  /tv/poll?key=   the TV waits here for commands (long poll)
//   POST /tv/reply?key=  the TV's answer to a command
//
// One Durable Object holds the queue, so Alexa's request and the TV's poll meet in the same place.
//
//   POST /tonight/alexa  the "Tonight" skill's endpoint: passes a search to the TV, which searches its own
//                        index (in the TONIGHT. app) and answers with the top match. Nothing is stored here.
//
//   GET  /talk           "Talk to Jarvis" on a phone: a page that needs HTTPS for the microphone, which the
//                        TV's own phone page can't have. POST /talk/ask (with the phone key) carries what was
//                        said, as text or as a short recording the TV transcribes, the same way as Alexa's.

import { TALK_PAGE, TALK_MANIFEST, TALK_ICON } from './talk.js';

const ANSWER_WAIT_MS = 6500;   // Alexa allows 8 s for the whole answer
const POLL_WAIT_MS = 20000;   // the TV gives up on a silent poll after 25 s (a redeploy leaves one hanging)
const PHONE_WAIT_MS = 70000;   // a phone waits longer than Alexa: the TV answers once Jarvis has finished speaking
const MAX_AUDIO = 600000;      // base64 characters, about 30 s of speech

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === '/alexa' && request.method === 'POST') return alexa(request, env);
    if (url.pathname === '/tonight/alexa' && request.method === 'POST') return tonightSkill(request, env);
    if (url.pathname === '/talk' || url.pathname === '/talk/') return new Response(TALK_PAGE, { headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' } });
    if (url.pathname === '/talk/manifest.webmanifest') return Response.json(TALK_MANIFEST);
    if (url.pathname === '/talk/icon.svg') return new Response(TALK_ICON, { headers: { 'content-type': 'image/svg+xml' } });
    if (url.pathname === '/talk/ask' && request.method === 'POST') return talk(request, env);
    if (url.pathname === '/talk/log' && request.method === 'POST') {   // the phone page's diagnostics: to the live log only
      if (!env.PHONE_KEY || request.headers.get('authorization') !== 'Bearer ' + env.PHONE_KEY) return new Response('forbidden', { status: 403 });
      console.log('talk', (await request.text()).slice(0, 600));
      return new Response(null, { status: 204 });
    }
    if (url.pathname.startsWith('/tv/')) {
      if (!env.TV_KEY || url.searchParams.get('key') !== env.TV_KEY) return new Response('forbidden', { status: 403 });
      return env.RELAY.get(env.RELAY.idFromName('jarvis')).fetch(request);
    }
    return new Response('Jarvis relay', { status: 200 });
  },
};

// ---- The phone: what was said (text, or a recording for the TV to transcribe) goes to Jarvis like Alexa's.

async function talk(request, env) {
  const auth = request.headers.get('authorization') || '';
  if (!env.PHONE_KEY || auth !== 'Bearer ' + env.PHONE_KEY) return Response.json({ error: 'This phone isn’t paired. Open the link from the TV’s phone page again.' }, { status: 403 });
  let body;
  try { body = await request.json(); } catch { return new Response('bad request', { status: 400 }); }
  const text = String(body.text || '').trim().slice(0, 500);
  const audio = typeof body.audio === 'string' && body.audio.length <= MAX_AUDIO ? body.audio : '';
  if (!text && !audio) return Response.json({ error: 'Nothing heard.' }, { status: 400 });
  const stub = env.RELAY.get(env.RELAY.idFromName('jarvis'));
  const r = await stub.fetch('https://relay/command', { method: 'POST',
    body: JSON.stringify({ text, audio, mime: String(body.mime || '').slice(0, 60), from: 'phone', wait: PHONE_WAIT_MS }) });
  return Response.json(await r.json());
}

// ---- The Tonight skill: "Alexa, ask Tonight to search for Inception"

async function tonightSkill(request, env) {
  let body;
  try { body = await request.json(); } catch { return new Response('bad request', { status: 400 }); }
  const appId = body?.context?.System?.application?.applicationId || body?.session?.application?.applicationId;
  if (!env.TONIGHT_SKILL_ID || appId !== env.TONIGHT_SKILL_ID) return new Response('forbidden', { status: 403 });
  const ts = Date.parse(body?.request?.timestamp || '');
  if (!ts || Math.abs(Date.now() - ts) > 150000) return new Response('stale', { status: 400 });

  const req = body.request;
  if (req.type === 'SessionEndedRequest') return speak(null);
  if (req.type === 'LaunchRequest') return speak('What should I search for on Tonight?', true, true);
  if (req.type !== 'IntentRequest') return speak(null);
  const name = req.intent.name;
  const conversation = !!body.session?.attributes?.conversation;
  if (name === 'AMAZON.StopIntent' || name === 'AMAZON.CancelIntent') return speak('Okay.');
  if (name === 'AMAZON.HelpIntent') return speak('Say a film or series, like: search for Inception, or find Stranger Things.', true, conversation);
  if (name !== 'SearchIntent') return speak('Try saying: search for Inception.', true, conversation);

  const q = (req.intent.slots?.query?.value || '').trim()
    .replace(/^(?:for|me|the (?:movie|film|show|series))\s+/i, '')
    .replace(/\s+(?:on|in|using) tonight$/i, '')
    .replace(/\s+(?:movie|film)$/i, '');
  if (!q) return speak('What should I search for?', true, conversation);

  const stub = env.RELAY.get(env.RELAY.idFromName('jarvis'));
  const tv = await (await stub.fetch('https://relay/command', { method: 'POST', body: JSON.stringify({ text: 'tonight search: ' + q }) })).json();
  const speech = tv.answered ? tv.speech : 'I couldn\u2019t reach the TV. Is it switched on?';
  return speak(speech, conversation, conversation);
}

// ---- Alexa

const INTENT_PREFIX = { PlayIntent: 'play ', QueueIntent: 'queue ', NoteIntent: 'note ', RemindIntent: 'remind me ', CommandIntent: '' };
const BUILT_IN = {
  'AMAZON.PauseIntent': 'pause', 'AMAZON.ResumeIntent': 'resume', 'AMAZON.NextIntent': 'next',
  'AMAZON.PreviousIntent': 'previous', 'AMAZON.StartOverIntent': 'start over',
  ClearNotesIntent: 'clear notes',
};

async function alexa(request, env) {
  let body;
  try { body = await request.json(); } catch { return new Response('bad request', { status: 400 }); }
  const appId = body?.context?.System?.application?.applicationId || body?.session?.application?.applicationId;
  if (!env.SKILL_ID || appId !== env.SKILL_ID) return new Response('forbidden', { status: 403 });
  const ts = Date.parse(body?.request?.timestamp || '');
  if (!ts || Math.abs(Date.now() - ts) > 150000) return new Response('stale', { status: 400 });

  const req = body.request;
  if (req.type === 'SessionEndedRequest') return speak(null);
  if (req.type === 'LaunchRequest') return speak('Jarvis here. What would you like?', true, true);
  if (req.type !== 'IntentRequest') return speak(null);

  const name = req.intent.name;
  if (name === 'AMAZON.StopIntent' || name === 'AMAZON.CancelIntent' || name === 'DoneIntent') return speak('Okay.');
  if (name === 'AMAZON.FallbackIntent') return speak('Sorry, I didn’t catch that. Try: ask Jarvis to play a song.', true);

  // Everything else goes to Jarvis on the TV, "help" included: it answers and acts, whatever was said.
  let text = name === 'AMAZON.HelpIntent' ? 'help: what can you do?' : BUILT_IN[name];
  // AnythingIntent: whatever was said, word for word, when no other intent's opening words fit.
  if (name === 'AnythingIntent') {
    text = req.intent.slots?.text?.value || '';
    if (!text) return speak('Sorry, I didn\u2019t catch that.', true);
  }
  if (text === undefined) {
    const q = req.intent.slots?.query?.value || '';
    // L_tell_me, L_how_many…: the opening words Alexa matched, put back in front of the rest.
    const prefix = name === 'L_noun' ? '' : name.startsWith('L_') ? name.slice(2).replace(/_/g, ' ').replace(/^(what|how|who|where)s\b/, "$1's") + ' ' : INTENT_PREFIX[name];
    if (prefix === undefined || !q) return speak('Sorry, I didn\u2019t catch that.', true);
    text = prefix + q;
  }
  const stub = env.RELAY.get(env.RELAY.idFromName('jarvis'));
  const r = await stub.fetch('https://relay/command', { method: 'POST', body: JSON.stringify({ text }) });
  const { speech, spoken } = await r.json();
  // Conversation mode: after "Alexa, open Jarvis" the session stays open, so follow-ups need no
  // "ask Jarvis". A one-shot "Alexa, ask Jarvis to..." ends after the answer.
  const conversation = !!body.session?.attributes?.conversation;
  return speak(spoken ? null : speech, conversation, conversation);
}

function speak(text, keepOpen = false, conversation = false) {
  const response = { shouldEndSession: !keepOpen };
  if (text) response.outputSpeech = { type: 'PlainText', text };
  if (keepOpen && text) response.reprompt = { outputSpeech: { type: 'PlainText', text: 'Anything else?' } };
  return Response.json({ version: '1.0', sessionAttributes: conversation ? { conversation: true } : {}, response });
}

// ---- The queue between Alexa and the TV

export class Relay {
  constructor(state, env) {
    this.queue = [];          // commands the TV hasn't collected yet
    this.pollers = [];        // TV polls waiting for a command
    this.answers = new Map(); // command id → resolve(speech)
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === '/command') {
      const { text, audio, mime, from, wait } = await request.json();
      const id = crypto.randomUUID();
      const answer = new Promise((resolve) => {
        this.answers.set(id, resolve);
        setTimeout(() => resolve(null), Math.min(wait || ANSWER_WAIT_MS, 75000));
      });
      this.queue.push({ id, text, audio, mime, from, at: Date.now() });
      this.flush();
      const got = await answer;
      this.answers.delete(id);
      return Response.json(got ? { ...got, answered: true } : { speech: 'I sent that to Jarvis, but the TV didn’t answer. Is it switched on?', spoken: false, answered: false });
    }
    if (url.pathname === '/tv/poll') {
      // Commands nobody collected for a minute are dropped, so the TV doesn't act on old requests.
      this.queue = this.queue.filter((c) => Date.now() - c.at < 60000);
      if (this.queue.length) return this.take();
      return new Promise((resolve) => {
        const p = { resolve, timer: setTimeout(() => { this.pollers = this.pollers.filter((x) => x !== p); resolve(Response.json({ commands: [] })); }, POLL_WAIT_MS) };
        this.pollers.push(p);
      });
    }
    if (url.pathname === '/tv/reply' && request.method === 'POST') {
      const { id, speech, spoken, heard, ignored } = await request.json();
      const resolve = this.answers.get(id);
      if (resolve) resolve({ speech: String(speech || '').slice(0, 600), spoken: !!spoken, heard: String(heard || '').slice(0, 500), ignored: !!ignored });
      return Response.json({ ok: true });
    }
    return new Response('not found', { status: 404 });
  }

  take() {
    const commands = this.queue.map(({ id, text, audio, mime, from }) => ({ id, text, audio, mime, from }));
    this.queue = [];
    return Response.json({ commands });
  }

  flush() {
    const p = this.pollers.shift();
    if (!p) return;
    clearTimeout(p.timer);
    p.resolve(this.take());
  }
}
