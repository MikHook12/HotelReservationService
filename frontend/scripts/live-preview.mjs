// Owns a single opt-in Maven process. Stop this preview before any other Maven build.
import { spawn, spawnSync } from "node:child_process";
import { mkdtemp, readFile, writeFile, open } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";
const frontend = fileURLToPath(new URL("../", import.meta.url));
const repo = path.dirname(frontend.replace(/\/$/, ""));
const javaHome = process.env.JAVA_HOME;
const java = javaHome ? path.join(javaHome, "bin/java") : "java";
const version = spawnSync(java, ["-version"], { encoding: "utf8" });
if (
  version.status !== 0 ||
  !/version "21[.\"]/.test(version.stderr + version.stdout)
)
  throw new Error(
    "Set JAVA_HOME to a JDK 21 installation before launching the isolated preview.",
  );
const dir = await mkdtemp(path.join(tmpdir(), "hotel-preview-"));
const readyFile = path.join(dir, "ready.json"),
  stopFile = path.join(dir, "stop"),
  logFile = path.join(dir, "backend.log");
const log = await open(logFile, "wx", 0o600);
const args = [
  "-B",
  "-Pintegration",
  "-DexcludedTestGroups=none",
  "-Dtest=FrontendLiveIntegrationTest",
  "-Dfrontend.live=true",
  `-Dfrontend.ready-file=${readyFile}`,
  `-Dfrontend.stop-file=${stopFile}`,
  "-Dfrontend.timeout-seconds=10800",
  "test",
];
const backend = spawn(path.join(repo, "mvnw"), args, {
  detached: true,
  cwd: repo,
  env: process.env,
  stdio: ["ignore", log.fd, log.fd],
});
let exited = false,
  web,
  stopping = false;
const backendExit = new Promise((resolve) =>
  backend.once("exit", (code) => {
    exited = true;
    resolve(code);
  }),
);
backend.on("error", (e) => {
  exited = true;
  console.error("Backend startup failed:", e.message);
});
async function stop() {
  if (stopping) return;
  stopping = true;
  web?.kill("SIGTERM");
  await writeFile(stopFile, "stop\n");
  if (!exited) {
    const timer = setTimeout(() => backend.kill("SIGTERM"), 30000);
    await backendExit;
    clearTimeout(timer);
  }
  await log.close();
  console.log("Isolated preview stopped. Working databases were not used.");
}
for (const signal of ["SIGINT", "SIGTERM", "SIGHUP"])
  process.once(signal, () => void stop());
try {
  console.log(
    "Starting isolated PostgreSQL/Kafka/MongoDB and Spring. Do not run another Maven process.",
  );
  console.log("Backend log:", logFile);
  let ready;
  for (let n = 0; n < 180 && !stopping; n++) {
    if (exited)
      throw new Error("Backend exited before readiness. Read the log above.");
    try {
      ready = JSON.parse(await readFile(readyFile, "utf8"));
      break;
    } catch {}
    await delay(1000);
  }
  if (stopping) process.exit(0);
  if (!ready || !/^http:\/\/127\.0\.0\.1:\d+$/.test(ready.backendOrigin))
    throw new Error("Backend did not become ready within 180 seconds.");
  web = spawn(
    process.execPath,
    [
      path.join(frontend, "node_modules/next/dist/bin/next"),
      "dev",
      "--hostname",
      "127.0.0.1",
      "--webpack",
      "--port",
      "3000",
    ],
    {
      detached: true,
    cwd: frontend,
      env: {
        ...process.env,
        BROWSER_BACKEND_ORIGIN: ready.backendOrigin,
        PUBLIC_ORIGIN: "http://127.0.0.1:3000",
        LOCAL_HTTP_MODE: "1",
        FRONTEND_QA_MODE: "0",
      },
      stdio: "inherit",
    },
  );
  console.log("Real isolated preview: http://127.0.0.1:3000/demo/hotels");
  console.log(
    "Throwaway test login:",
    ready.username,
    "(password is documented in README)",
  );
  console.log("Ready file for protocol verification:", readyFile);
  console.log(
    "Local HTTP preview is bound to loopback. Stop with Ctrl+C.",
  );
  const webExit = new Promise((resolve) => web.once("exit", resolve));
  web.on("error", (e) => console.error("Frontend startup failed:", e.message));
  await Promise.race([webExit, backendExit]);
} finally {
  await stop();
}
