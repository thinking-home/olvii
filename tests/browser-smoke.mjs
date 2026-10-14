// Run with Node 22+: node tests/browser-smoke.mjs <Chromium executable> [server URL]
// Uses isolated browser data and synthetic audio; never opens a real microphone.
import { spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve, dirname, basename } from "node:path";
import assert from "node:assert/strict";

// Validate the browser path and server credentials.
const binary = process.argv[2];
if (!binary || !process.env.Olvii__AccessKey) throw Error("Provide browser executable and Olvii__AccessKey");
const url = process.argv[3] || "http://localhost:5080";

// Keep all browser state in a disposable isolated profile.
const profile = await mkdtemp(join(tmpdir(), "olvii-browser-test-"));
const browser = spawn(
  binary,
  [
    "--headless=new",
    "--remote-debugging-pipe",
    `--user-data-dir=${profile}`,

    // Avoid first-run UI in automated sessions.
    "--no-first-run",
    "--no-default-browser-check",

    // Use synthetic media instead of the real microphone.
    "--use-fake-device-for-media-stream",
    "--use-fake-ui-for-media-stream",
    "--autoplay-policy=no-user-gesture-required",
    "about:blank",
  ],
  { stdio: ["ignore", "ignore", "ignore", "pipe", "pipe"], windowsHide: true },
);

// Track request IDs and partial CDP frames.
let sequence = 0,
  buffer = "";
const requests = new Map();
browser.stdio[4].on("data", (data) => {
  // Reassemble null-delimited protocol messages.
  buffer += data.toString();
  let index;
  while ((index = buffer.indexOf("\0")) >= 0) {
    // Decode one complete CDP response at a time.
    const message = JSON.parse(buffer.slice(0, index));
    buffer = buffer.slice(index + 1);
    const pending = requests.get(message.id);

    // Resolve the matching request and cancel its timeout.
    if (pending) {
      requests.delete(message.id);
      clearTimeout(pending.timer);
      message.error ? pending.reject(Error(JSON.stringify(message.error))) : pending.resolve(message.result);
    }
  }
});

// Send one CDP command with a bounded response wait.
function cdp(method, params = {}, sessionId) {
  return new Promise((resolve, reject) => {
    const id = ++sequence;

    // Remove a timed-out request before rejecting it.
    const timer = setTimeout(() => {
      requests.delete(id);
      reject(Error(`Timeout: ${method}`));
    }, 15000);

    // Register the response handler before writing the command.
    requests.set(id, { resolve, reject, timer });
    browser.stdio[3].write(JSON.stringify({ id, method, params, sessionId }) + "\0");
  });
}

// Evaluate in a chosen tab and propagate script exceptions.
async function evaluate(session, expression) {
  const value = await cdp(
    "Runtime.evaluate",
    { expression, awaitPromise: true, returnByValue: true },
    session,
  );

  // Return only the value after checking execution errors.
  if (value.exceptionDetails) throw Error(JSON.stringify(value.exceptionDetails));
  return value.result.value;
}

// Poll a browser condition with a fixed deadline.
async function until(session, expression) {
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    if (await evaluate(session, expression)) return;
    await new Promise((r) => setTimeout(r, 100));
  }
  // Include visible page state in timeout diagnostics.
  throw Error(
    `Condition timed out: ${expression}; status: ${await evaluate(session, "document.getElementById('status')?.textContent")}`,
  );
}

// Open a tab and attach a flat CDP session.
async function page(name) {
  const { targetId } = await cdp("Target.createTarget", { url });
  const { sessionId } = await cdp("Target.attachToTarget", {
    targetId,
    flatten: true,
  });

  // Wait for the form before entering credentials.
  await until(sessionId, "!!document.getElementById('connection')");
  await evaluate(
    sessionId,
    `document.getElementById('name').value = ${JSON.stringify(name)};
     document.getElementById('key').value = ${JSON.stringify(process.env.Olvii__AccessKey)};
     document.getElementById('connection').requestSubmit();`,
  );

  // Return only after server registration is confirmed.
  await until(sessionId, "connected");
  return sessionId;
}

// Use two independent page identities for the end-to-end call.
try {
  const a = await page("Browser A"),
    b = await page("Browser B");
  await until(
    a,
    "document.querySelector('#devices button') && !document.querySelector('#devices button').disabled",
  );

  // Call the recipient without allowing automatic acceptance.
  await evaluate(a, "document.querySelector('#devices button').click()");
  await until(b, "call?.state === 'incoming'");

  // Check that ringing alone never captures audio.
  assert.equal(
    await evaluate(b, "!!call.pc || !!call.stream"),
    false,
    "No microphone or peer before acceptance",
  );

  // Reject the first call and wait for released busy flags.
  await evaluate(b, "document.getElementById('reject').click()");
  await until(a, "call === null");
  await until(a, "!document.querySelector('#devices button').disabled");
  await evaluate(a, "document.querySelector('#devices button').click()");
  await until(b, "call?.state === 'incoming'");

  // Accept the second call explicitly.
  await evaluate(b, "document.getElementById('accept').click()");

  // Verify received audio packets on both peers.
  for (const session of [a, b]) {
    await until(session, "call?.pc?.connectionState === 'connected'");
    await until(
      session,
      "call.pc.getStats().then(stats => [...stats.values()].some(s => s.type === 'inbound-rtp' && s.kind === 'audio' && s.bytesReceived > 0))",
    );
  }

  // Mute the existing microphone track.
  await evaluate(a, "document.getElementById('mute').click()");
  assert.equal(await evaluate(a, "call.stream.getAudioTracks()[0].enabled"), false, "Mute disables track");

  // Retain the track reference to verify it stops after hangup.
  await evaluate(a, "globalThis.testTrack = call.stream.getAudioTracks()[0]");
  await evaluate(a, "document.getElementById('hangup').click()");
  await until(b, "call === null");
  assert.equal(await evaluate(a, "testTrack.readyState"), "ended", "Hangup releases microphone");

  // Announce success after media and cleanup assertions.
  console.log(
    "PASS: two browser clients, explicit acceptance, rejection, bidirectional synthetic audio, mute, hangup, microphone cleanup.",
  );
} finally {
  // Stop the isolated process and cancel pending response timers.
  browser.kill();
  for (const request of requests.values()) clearTimeout(request.timer);
  await new Promise((resolve) => (browser.exitCode !== null ? resolve() : browser.once("exit", resolve)));

  // Delete only the temporary profile created by this test.
  if (dirname(resolve(profile)) === resolve(tmpdir()) && basename(profile).startsWith("olvii-browser-test-"))
    await rm(profile, {
      recursive: true,
      force: true,
      maxRetries: 5,
      retryDelay: 200,
    }).catch(() => {});
}
