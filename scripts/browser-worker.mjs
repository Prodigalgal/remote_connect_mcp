#!/usr/bin/env node

import { randomUUID } from "node:crypto";
import { mkdir, readFile, rename, stat, writeFile } from "node:fs/promises";
import path from "node:path";

const MAX_COMMAND_BYTES = 64 * 1024;
const MAX_OUTPUT_BYTES = 64 * 1024;
const MAX_SELECTOR_LENGTH = 2048;
const MAX_TEXT_BYTES = 64 * 1024;
const MAX_SCRIPT_BYTES = 32 * 1024;
const MAX_WAIT_MS = 30_000;
const MAX_EVENT_ENTRIES = 64;
const MAX_EVENT_TEXT_BYTES = 1024;
const MAX_EVENT_URL_BYTES = 2048;

const requestFile = requiredEnv("RCM_BROWSER_TASK_REQUEST_FILE");
const resultFile = requiredEnv("RCM_BROWSER_RESULT_FILE");
const artifactDir = path.resolve(requiredEnv("RCM_BROWSER_ARTIFACT_DIR"));
const timeoutMs = boundedInteger(process.env.RCM_BROWSER_TASK_TIMEOUT_SECONDS, 300, 1, 24 * 60 * 60) * 1000;
const engineName = (process.env.RCM_BROWSER_ENGINE || "playwright").trim().toLowerCase();
const browserName = (process.env.RCM_BROWSER_BROWSER || "chromium").trim().toLowerCase();
const profileDir = process.env.RCM_BROWSER_PROFILE_DIR?.trim();
const headless = !["0", "false", "no"].includes((process.env.RCM_BROWSER_HEADLESS || "1").trim().toLowerCase());

let context;
let browser;

try {
  const task = JSON.parse(await readFile(requestFile, "utf8"));
  const command = parseCommand(task.command);
  const result = await run(command);
  await writeResult({ status: "completed", output: result.output, ...(result.artifact ? { artifact: result.artifact } : {}) });
} catch (error) {
  const message = compactError(error instanceof Error ? error.message : String(error));
  try {
    await writeResult({ status: "failed", error: message });
  } catch (writeError) {
    console.error("[rcm-browser] result manifest failed: " + compactError(writeError instanceof Error ? writeError.message : String(writeError)));
  }
  console.error("[rcm-browser] " + message);
  process.exitCode = 1;
} finally {
  try {
    if (context) await context.close();
  } catch {
    // The Agent owns process termination; a close failure must not hide the task result.
  }
  try {
    if (browser) await browser.close();
  } catch {
    // Persistent contexts already close their browser in context.close().
  }
}

async function run(command) {
  const operation = text(command.operation || "snapshot").toLowerCase();
  const module = await loadEngine(engineName);
  const browserType = module[browserName];
  if (!browserType || typeof browserType.launch !== "function") {
    throw new Error("browser engine " + engineName + " does not expose " + browserName);
  }
  await mkdir(artifactDir, { recursive: true });
  if (profileDir) await mkdir(path.resolve(profileDir), { recursive: true });

  const launchOptions = { headless, timeout: timeoutMs, acceptDownloads: true };
  if (profileDir && typeof browserType.launchPersistentContext === "function") {
    context = await browserType.launchPersistentContext(path.resolve(profileDir), launchOptions);
  } else {
    browser = await browserType.launch(launchOptions);
    context = await browser.newContext();
  }
  const page = context.pages()[0] || await context.newPage();
  page.setDefaultTimeout(timeoutMs);
  const events = { network: [], console: [], pageErrors: [] };
  const observedPages = new WeakSet();
  const observe = (candidate) => attachObservers(candidate, events, observedPages);
  observe(page);
  if (typeof context.on === "function") context.on("page", observe);

  const result = await executeOperation(page, command, operation);
  return withEventSummary(result, events);
}

async function executeOperation(page, command, operation) {
  switch (operation) {
    case "navigate": {
      const url = safeUrl(command.url);
      await page.goto(url, { waitUntil: "domcontentloaded", timeout: timeoutMs });
      return { output: JSON.stringify({ operation, url: safeEventUrl(page.url()), title: await page.title() }) };
    }
    case "snapshot": {
      const snapshot = await snapshotPage(page);
      return { output: limitText(JSON.stringify({ operation, url: safeEventUrl(page.url()), title: await page.title(), snapshot }), MAX_OUTPUT_BYTES) };
    }
    case "click": {
      await locator(page, command.selector).click();
      return { output: JSON.stringify({ operation, selector: command.selector }) };
    }
    case "fill": {
      const value = boundedText(command.value ?? command.text, MAX_TEXT_BYTES, "value");
      await locator(page, command.selector).fill(value);
      return { output: JSON.stringify({ operation, selector: command.selector }) };
    }
    case "press": {
      const key = boundedText(command.key, 128, "key");
      await locator(page, command.selector).press(key);
      return { output: JSON.stringify({ operation, selector: command.selector, key }) };
    }
    case "wait": {
      const waitMs = boundedInteger(command.wait_ms, 0, 0, MAX_WAIT_MS);
      await page.waitForTimeout(waitMs);
      return { output: JSON.stringify({ operation, wait_ms: waitMs }) };
    }
    case "title":
      return { output: JSON.stringify({ operation, title: await page.title(), url: safeEventUrl(page.url()) }) };
    case "url":
      return { output: JSON.stringify({ operation, url: safeEventUrl(page.url()) }) };
    case "screenshot": {
      const file = path.join(artifactDir, "screenshot.png");
      await page.screenshot({ path: file, type: "png", fullPage: command.full_page === true });
      await assertRegularFile(file);
      return {
        output: JSON.stringify({ operation, url: safeEventUrl(page.url()), title: await page.title() }),
        artifact: { path: "screenshot.png", mime_type: "image/png" }
      };
    }
    case "download": {
      const downloadPromise = page.waitForEvent("download", { timeout: timeoutMs });
      await locator(page, command.selector).click();
      const download = await downloadPromise;
      const extension = safeExtension(download.suggestedFilename());
      const fileName = "download-" + Date.now() + extension;
      const file = path.join(artifactDir, fileName);
      await download.saveAs(file);
      await assertRegularFile(file);
      return {
        output: JSON.stringify({ operation, file_name: fileName, url: safeEventUrl(page.url()) }),
        artifact: { path: fileName, mime_type: "application/octet-stream" }
      };
    }
    case "evaluate": {
      const script = boundedText(command.script, MAX_SCRIPT_BYTES, "script");
      const value = await page.evaluate(script);
      return { output: limitText(JSON.stringify({ operation, result: value }), MAX_OUTPUT_BYTES) };
    }
    default:
      throw new Error("unsupported browser operation: " + operation);
  }
}

/** Attach a deliberately small, redacted event buffer to one page. */
function attachObservers(page, events, observedPages) {
  if (!page || observedPages.has(page) || typeof page.on !== "function") return;
  observedPages.add(page);
  page.on("response", (response) => {
    try {
      const request = response.request();
      pushBounded(events.network, {
        type: "response",
        method: compactEventText(request.method(), 32),
        url: safeEventUrl(response.url()),
        status: Number(response.status()) || 0,
      });
    } catch {
      // Event listeners must never fail the browser task.
    }
  });
  page.on("requestfailed", (request) => {
    try {
      pushBounded(events.network, {
        type: "requestfailed",
        method: compactEventText(request.method(), 32),
        url: safeEventUrl(request.url()),
        error: compactEventText(request.failure()?.errorText, MAX_EVENT_TEXT_BYTES),
      });
    } catch {
      // Event listeners must never fail the browser task.
    }
  });
  page.on("console", (message) => {
    try {
      pushBounded(events.console, {
        type: compactEventText(message.type(), 32),
        text: compactEventText(message.text(), MAX_EVENT_TEXT_BYTES),
      });
    } catch {
      // Event listeners must never fail the browser task.
    }
  });
  page.on("pageerror", (error) => {
    pushBounded(events.pageErrors, { text: compactEventText(error?.message ?? error, MAX_EVENT_TEXT_BYTES) });
  });
}

function pushBounded(list, value) {
  if (list.length < MAX_EVENT_ENTRIES) list.push(value);
}

function withEventSummary(result, events) {
  const summary = {};
  if (events.network.length > 0) summary.network = events.network;
  if (events.console.length > 0) summary.console = events.console;
  if (events.pageErrors.length > 0) summary.page_errors = events.pageErrors;
  if (Object.keys(summary).length === 0) return result;
  let payload;
  try {
    payload = JSON.parse(result.output);
  } catch {
    payload = { result: result.output };
  }
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) payload = { result: payload };
  Object.assign(payload, summary);
  return { ...result, output: limitText(JSON.stringify(payload), MAX_OUTPUT_BYTES) };
}

function safeEventUrl(value) {
  try {
    const parsed = new URL(String(value));
    // Query strings and fragments frequently contain credentials or session
    // data.  Keep origin/path for diagnostics and mark their presence only.
    const query = parsed.search ? "?[redacted]" : "";
    return compactEventText(parsed.origin + parsed.pathname + query, MAX_EVENT_URL_BYTES);
  } catch {
    return compactEventText(String(value).replace(/[?#].*$/, "?[redacted]"), MAX_EVENT_URL_BYTES);
  }
}

function compactEventText(value, maxBytes) {
  const output = redactEventText(String(value ?? "").replace(/[\r\n]+/g, " ").trim());
  if (Buffer.byteLength(output, "utf8") <= maxBytes) return output;
  let result = output;
  while (Buffer.byteLength(result, "utf8") > maxBytes - 16) result = result.slice(0, -1);
  return result + "…";
}

function redactEventText(value) {
  return value
    .replace(/\b(?:authorization|cookie|token|password|secret|api[_-]?key)\b\s*[:=]\s*["']?[^"'\s,;]+/gi, "$&".replace(/[:=].*$/, "=[redacted]"))
    .replace(/\bBearer\s+[A-Za-z0-9._~+/=-]+/gi, "Bearer [redacted]");
}

async function snapshotPage(page) {
  const body = page.locator("body");
  if (typeof body.ariaSnapshot === "function") {
    return limitText(await body.ariaSnapshot(), 48 * 1024);
  }
  return limitText(await body.innerText(), 48 * 1024);
}

function locator(page, value) {
  if (typeof value === "string") {
    return page.locator(boundedText(value, MAX_SELECTOR_LENGTH, "selector")).first();
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("selector must be a CSS string or a structured locator");
  }
  if (value.css !== undefined) {
    return page.locator(boundedText(value.css, MAX_SELECTOR_LENGTH, "selector")).first();
  }
  if (value.role !== undefined) {
    const role = boundedText(value.role, 128, "role");
    const options = value.name === undefined ? undefined : {
      name: boundedText(value.name, MAX_TEXT_BYTES, "name"),
      exact: value.exact === true,
    };
    if (typeof page.getByRole !== "function") throw new Error("browser engine does not support role locators");
    return page.getByRole(role, options).first();
  }
  if (value.label !== undefined) {
    if (typeof page.getByLabel !== "function") throw new Error("browser engine does not support label locators");
    return page.getByLabel(boundedText(value.label, MAX_TEXT_BYTES, "label"), { exact: value.exact === true }).first();
  }
  if (value.placeholder !== undefined) {
    if (typeof page.getByPlaceholder !== "function") throw new Error("browser engine does not support placeholder locators");
    return page.getByPlaceholder(boundedText(value.placeholder, MAX_TEXT_BYTES, "placeholder"), { exact: value.exact === true }).first();
  }
  if (value.text !== undefined) {
    if (typeof page.getByText !== "function") throw new Error("browser engine does not support text locators");
    return page.getByText(boundedText(value.text, MAX_TEXT_BYTES, "text"), { exact: value.exact === true }).first();
  }
  if (value.test_id !== undefined) {
    if (typeof page.getByTestId !== "function") throw new Error("browser engine does not support test-id locators");
    return page.getByTestId(boundedText(value.test_id, MAX_SELECTOR_LENGTH, "test_id")).first();
  }
  throw new Error("structured locator requires css, role, label, placeholder, text, or test_id");
}

async function loadEngine(name) {
  if (!["playwright", "patchright", "comoufox"].includes(name)) {
    throw new Error("RCM_BROWSER_ENGINE must be playwright, patchright, or comoufox");
  }
  let imported;
  try {
    imported = await import(name);
  } catch (error) {
    throw new Error("browser package " + name + " is not installed: " + (error instanceof Error ? error.message : String(error)));
  }
  return imported.default && typeof imported.default === "object"
    ? Object.assign({}, imported.default, imported)
    : imported;
}

async function writeResult(value) {
  const target = path.resolve(resultFile);
  await mkdir(path.dirname(target), { recursive: true });
  const payload = JSON.stringify(value);
  if (Buffer.byteLength(payload, "utf8") > MAX_OUTPUT_BYTES) throw new Error("browser result manifest exceeds 64 KiB");
  const temporary = target + "." + randomUUID() + ".tmp";
  await writeFile(temporary, payload, { encoding: "utf8", mode: 0o600 });
  await rename(temporary, target);
}

async function assertRegularFile(file) {
  const info = await stat(file);
  if (!info.isFile() || info.size <= 0) throw new Error("browser artifact is empty or not a regular file");
}

function parseCommand(command) {
  if (typeof command !== "string" || Buffer.byteLength(command, "utf8") > MAX_COMMAND_BYTES) {
    throw new Error("browser task command must be a JSON string no larger than 64 KiB");
  }
  const value = JSON.parse(command);
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("browser task command must be a JSON object");
  return value;
}

function safeUrl(value) {
  const url = boundedText(value, 4096, "url");
  let parsed;
  try {
    parsed = new URL(url);
  } catch {
    throw new Error("url is invalid");
  }
  if (!["http:", "https:"].includes(parsed.protocol)) throw new Error("only http and https URLs are allowed");
  return parsed.toString();
}

function boundedText(value, maxBytes, name) {
  if (typeof value !== "string" || value.length === 0) throw new Error(name + " is required");
  if (Buffer.byteLength(value, "utf8") > maxBytes) throw new Error(name + " exceeds " + maxBytes + " bytes");
  if (value.includes("\u0000")) throw new Error(name + " contains NUL");
  return value;
}

function boundedInteger(value, fallback, minimum, maximum) {
  if (value === undefined || value === null || value === "") return fallback;
  const number = Number(value);
  if (!Number.isInteger(number) || number < minimum || number > maximum) {
    throw new Error("integer must be between " + minimum + " and " + maximum);
  }
  return number;
}

function safeExtension(value) {
  if (typeof value !== "string") return ".bin";
  const match = value.match(/(\.[a-zA-Z0-9]{1,12})$/);
  return match ? match[1].toLowerCase() : ".bin";
}

function limitText(value, maxBytes) {
  const output = String(value ?? "");
  if (Buffer.byteLength(output, "utf8") <= maxBytes) return output;
  let result = output;
  while (Buffer.byteLength(result, "utf8") > maxBytes - 32) result = result.slice(0, -1);
  return result + "\n[truncated]";
}

function compactError(value) {
  const output = String(value || "browser task failed").replace(/[\r\n]+/g, " ").trim();
  return output.length <= 4096 ? output : output.slice(0, 4093) + "...";
}

function text(value) {
  return value === undefined || value === null ? "" : String(value).trim();
}

function requiredEnv(name) {
  const value = process.env[name];
  if (!value || !value.trim()) throw new Error(name + " is required");
  return value.trim();
}
