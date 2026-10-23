// Persistent, loopback-only development. Never reads .env or removes Docker volumes.
import { spawn, spawnSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import {
  open,
  mkdir,
  readFile,
  writeFile,
  unlink,
  copyFile,
  access,
  lstat,
  chmod,
  rename,
} from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import net from "node:net";
import { setTimeout as delay } from "node:timers/promises";
const repo = fileURLToPath(new URL("../", import.meta.url));
const state = path.join(repo, ".local");
const lockPath = path.join(state, "run.lock");
const stopPath = path.join(state, "stop.request");
const credentialPath = path.join(state, "credentials.json");
const envPath = path.join(state, "compose.env");
const project = "hotel-local-app";
const marker = "hotel-local-app-v1";
const origin = "http://127.0.0.1:3000";
const backendOrigin = "http://127.0.0.1:18080";
const command = process.argv[2] || "start";
if (!["start", "stop", "status"].includes(command))
  throw new Error("Usage: ./dev [start|stop|status]");
const compose = [
  "compose",
  "--project-name",
  project,
  "--project-directory",
  repo,
  "--env-file",
  envPath,
  "-f",
  path.join(repo, "compose.local.yml"),
];
async function exists(file) {
  try {
    await access(file);
    return true;
  } catch {
    return false;
  }
}
async function privateDirectory(dir) {
  await mkdir(dir, { recursive: true, mode: 0o700 });
  const stat = await lstat(dir);
  if (!stat.isDirectory() || stat.isSymbolicLink())
    throw new Error("Local runtime path must be a real directory");
  await chmod(dir, 0o700);
}
async function privateJson(file, value) {
  const handle = await open(file, "wx", 0o600);
  try {
    await handle.writeFile(JSON.stringify(value, null, 2) + "\n");
  } finally {
    await handle.close();
  }
}
async function readPrivateJson(file) {
  const stat = await lstat(file);
  if (!stat.isFile() || stat.isSymbolicLink())
    throw new Error("Local runtime file must be a regular file");
  await chmod(file, 0o600);
  return JSON.parse(await readFile(file, "utf8"));
}
let activeCommand, activeCommandDone;
const composeProcessEnv = Object.fromEntries(
  Object.entries(process.env).filter(
    ([key]) => !/^(LOCAL_|COMPOSE_)/.test(key),
  ),
);
function run(bin, args, options = {}) {
  const track = options.track !== false;
  const { track: ignored, ...spawnOptions } = options;
  const promise = new Promise((resolve, reject) => {
    const child = spawn(bin, args, {
      cwd: repo,
      env: composeProcessEnv,
      stdio: "inherit",
      ...spawnOptions,
    });
    if (track) activeCommand = child;
    child.once("error", reject);
    child.once("exit", (code, signal) =>
      code === 0
        ? resolve()
        : reject(
            new Error(
              `${path.basename(bin)} exited (${code ?? signal}); see local logs`,
            ),
          ),
    );
  });
  if (track) activeCommandDone = promise.catch(() => {});
  return promise;
}
async function output(bin, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(bin, args, {
      cwd: repo,
      env: process.env,
      stdio: ["ignore", "pipe", "pipe"],
    });
    let text = "";
    child.stdout.on("data", (value) => {
      text += value;
    });
    child.once("error", reject);
    child.once("exit", (code) =>
      code === 0
        ? resolve(text.trim())
        : reject(new Error(`${bin} failed; check Docker Desktop`)),
    );
  });
}
async function freePort(port) {
  await new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once("error", () =>
      reject(
        new Error(
          `Port ${port} is occupied. Stop the other preview before starting ./dev.`,
        ),
      ),
    );
    server.listen(port, "127.0.0.1", () => server.close(resolve));
  });
}
function javaHome() {
  const cleanEnv = Object.fromEntries(
    Object.entries(process.env).filter(
      ([key]) =>
        !["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"].includes(
          key,
        ),
    ),
  );
  let home = cleanEnv.JAVA_HOME;
  if (!home && process.platform === "darwin") {
    const found = spawnSync("/usr/libexec/java_home", ["-v", "21"], {
      encoding: "utf8",
    });
    if (found.status === 0) home = found.stdout.trim();
  }
  const java = home ? path.join(home, "bin/java") : "java";
  const version = spawnSync(java, ["-version"], {
    encoding: "utf8",
    env: cleanEnv,
  });
  if (
    version.status !== 0 ||
    !/version "21[.\"]/.test(version.stderr + version.stdout)
  )
    throw new Error(
      "JDK 21 is required. Set JAVA_HOME to your Java 21 installation.",
    );
  return {
    java,
    env: home ? { ...cleanEnv, JAVA_HOME: home } : cleanEnv,
  };
}
await privateDirectory(state);
if (command === "status") {
  if (!(await exists(envPath))) {
    console.log("Local environment has not been initialized. Run ./dev start.");
    process.exit(0);
  }
  await run("docker", [...compose, "ps"]);
  console.log(
    "Application:",
    (await exists(lockPath))
      ? "launcher lock exists (see .local/backend.log and ./dev stop)"
      : "stopped",
  );
  process.exit(0);
}
if (command === "stop") {
  if (await exists(lockPath)) {
    await writeFile(stopPath, "stop\n", { mode: 0o600 });
    for (let n = 0; n < 180 && (await exists(lockPath)); n++) await delay(500);
    if (await exists(lockPath))
      throw new Error(
        "Launcher did not stop; inspect .local/backend.log. No volumes were removed.",
      );
  } else if (await exists(envPath))
    await run("docker", [...compose, "stop", "--timeout", "20"]);
  console.log(
    "Local environment stopped. All data and credentials are retained.",
  );
  process.exit(0);
}
await freePort(3000);
await freePort(18080);
await output("docker", ["info", "--format", "{{.ServerVersion}}"]);
const java = javaHome();
if (
  !(await exists(path.join(repo, "frontend/node_modules/next/dist/bin/next")))
)
  throw new Error("Install frontend dependencies first: cd frontend && npm ci");
if (await exists(lockPath)) {
  const lock = await readPrivateJson(lockPath);
  if (lock.environmentId !== marker || !Number.isSafeInteger(lock.pid))
    throw new Error("Unrecognized local lock; refusing to overwrite");
  try {
    process.kill(lock.pid, 0);
    throw new Error("Local launcher is already running. Use ./dev stop first.");
  } catch (error) {
    if (error.code !== "ESRCH") throw error;
  }
  await unlink(lockPath);
}
await privateJson(lockPath, { environmentId: marker, pid: process.pid });
let backend,
  web,
  timer,
  stopping = false;
const handles = [];
let backendDone, webDone;
function child(bin, args, options) {
  const proc = spawn(bin, args, {
    cwd: repo,
    env: process.env,
    stdio: "inherit",
    ...options,
  });
  const done = new Promise((resolve) => {
    proc.once("error", (error) => {
      console.error("Startup failed:", error.message);
      resolve(1);
    });
    proc.once("exit", (code) => resolve(code ?? 1));
  });
  return { proc, done };
}
async function terminate(proc, done) {
  if (!proc || proc.exitCode !== null) return;
  proc.kill("SIGTERM");
  const timeout = setTimeout(() => proc.kill("SIGKILL"), 20000);
  await done;
  clearTimeout(timeout);
}
async function stop() {
  if (stopping) return;
  stopping = true;
  clearInterval(timer);
  await terminate(activeCommand, activeCommandDone);
  await terminate(web, webDone);
  await terminate(backend, backendDone);
  try {
    if (await exists(envPath))
      await run("docker", [...compose, "stop", "--timeout", "20"], {
        track: false,
      });
  } finally {
    for (const handle of handles) await handle.close();
    await unlink(lockPath).catch(() => {});
    await unlink(stopPath).catch(() => {});
    console.log(
      "Stopped. PostgreSQL, MongoDB and Kafka data remain in named local volumes.",
    );
  }
}
for (const signal of ["SIGINT", "SIGTERM", "SIGHUP"])
  process.once(signal, () => void stop());
timer = setInterval(async () => {
  if (await exists(stopPath)) void stop();
}, 500);
try {
  await unlink(stopPath).catch(() => {});
  let credentials;
  if (await exists(credentialPath))
    credentials = await readPrivateJson(credentialPath);
  else {
    const volumes = await output("docker", [
      "volume",
      "ls",
      "--filter",
      `label=com.docker.compose.project=${project}`,
      "--format",
      "{{.Name}}",
    ]);
    if (volumes)
      throw new Error(
        "Local data volumes exist but credentials.json is missing. Restore its backup; refusing to generate replacement passwords.",
      );
    credentials = {
      version: 1,
      environmentId: marker,
      username: "local-user",
      password: randomBytes(24).toString("base64url"),
      dbPassword: randomBytes(24).toString("base64url"),
      mongoPassword: randomBytes(24).toString("base64url"),
      quoteKey: randomBytes(32).toString("base64"),
      kafkaClusterId: randomBytes(16).toString("base64url"),
    };
    await privateJson(credentialPath, credentials);
  }
  if (
    credentials.version !== 1 ||
    credentials.environmentId !== marker ||
    credentials.username !== "local-user" ||
    ![
      credentials.password,
      credentials.dbPassword,
      credentials.mongoPassword,
    ].every((s) => typeof s === "string" && /^[A-Za-z0-9_-]{32}$/.test(s)) ||
    typeof credentials.quoteKey !== "string" ||
    !/^[A-Za-z0-9+/]{43}=$/.test(credentials.quoteKey) ||
    typeof credentials.kafkaClusterId !== "string" ||
    !/^[A-Za-z0-9_-]{22}$/.test(credentials.kafkaClusterId)
  )
    throw new Error(
      "Invalid local credentials file. Restore its backup; values were not changed.",
    );
  if (
    credentials.adminUsername === undefined &&
    credentials.adminPassword === undefined
  ) {
    const previous = { ...credentials };
    credentials.adminUsername = "local-admin";
    credentials.adminPassword = randomBytes(24).toString("base64url");
    await privateJson(
      path.join(
        state,
        `credentials-before-admin-${randomBytes(6).toString("hex")}.json`,
      ),
      previous,
    );
    const temporary = path.join(
      state,
      `credentials-${randomBytes(6).toString("hex")}.tmp`,
    );
    await privateJson(temporary, credentials);
    await rename(temporary, credentialPath);
  }
  if (
    credentials.adminUsername !== "local-admin" ||
    typeof credentials.adminPassword !== "string" ||
    !/^[A-Za-z0-9_-]{32}$/.test(credentials.adminPassword)
  )
    throw new Error(
      "Invalid local administrator credentials; existing credentials were not reset.",
    );
  const composeEnv = `LOCAL_DB_PASSWORD=${credentials.dbPassword}\nLOCAL_MONGO_PASSWORD=${credentials.mongoPassword}\nLOCAL_KAFKA_CLUSTER_ID=${credentials.kafkaClusterId}\n`;
  if (await exists(envPath)) {
    const stat = await lstat(envPath);
    if (
      stat.isSymbolicLink() ||
      !stat.isFile() ||
      (await readFile(envPath, "utf8")) !== composeEnv
    )
      throw new Error(
        "Local compose.env differs from credentials.json. Refusing to overwrite; restore matching backups.",
      );
    await chmod(envPath, 0o600);
  } else await writeFile(envPath, composeEnv, { flag: "wx", mode: 0o600 });
  if (stopping) throw new Error("Startup cancelled");
  console.log(
    "Starting dedicated persistent containers. Existing working databases are not used.",
  );
  await run("docker", [
    ...compose,
    "up",
    "-d",
    "--wait",
    "--wait-timeout",
    "180",
  ]);
  if (stopping) throw new Error("Startup cancelled");
  const buildLog = await open(path.join(state, "build.log"), "w", 0o600);
  handles.push(buildLog);
  console.log(
    "Building Java application (tests are a separate verification step). Do not run another Maven build.",
  );
  await run(path.join(repo, "mvnw"), ["-B", "-DskipTests", "package"], {
    env: java.env,
    stdio: ["ignore", buildLog.fd, buildLog.fd],
  });
  if (stopping) throw new Error("Startup cancelled");
  const readyFile = path.join(state, "backend-ready.json");
  const runId = randomBytes(16).toString("hex");
  await unlink(readyFile).catch((error) => {
    if (error.code !== "ENOENT") throw error;
  });
  const loginFile = path.join(state, "login.txt");
  const loginText = `Site: ${origin}/\nUsername: ${credentials.username}\nPassword: ${credentials.password}\n`;
  if (!(await exists(loginFile)))
    await writeFile(loginFile, loginText, { flag: "wx", mode: 0o600 });
  const adminLogin = path.join(state, "admin-login.txt");
  if (!(await exists(adminLogin)))
    await writeFile(
      adminLogin,
      `Site: ${origin}/admin\nUsername: ${credentials.adminUsername}\nPassword: ${credentials.adminPassword}\n`,
      { flag: "wx", mode: 0o600 },
    );
  const mediaDirectory = path.join(state, "media");
  await privateDirectory(mediaDirectory);
  const jar = path.join(state, "application.jar");
  await copyFile(
    path.join(repo, "target/HotelReservationService-0.0.1-SNAPSHOT.jar"),
    jar,
  );
  const backendLog = await open(path.join(state, "backend.log"), "w", 0o600);
  handles.push(backendLog);
  const app = child(java.java, ["-jar", jar], {
    env: {
      ...Object.fromEntries(
        Object.entries(java.env).filter(
          ([key]) =>
            !/^(SPRING_|SERVER_|APP_|DB_|MONGO_|KAFKA_|BROWSER_|BOOTSTRAP_|LOCAL_|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$|_JAVA_OPTIONS$)/.test(
              key,
            ),
        ),
      ),
      SPRING_CONFIG_LOCATION: "classpath:/application.yml",
      SPRING_CONFIG_IMPORT: "",
      SPRING_PROFILES_ACTIVE: "local",
      SERVER_ADDRESS: "127.0.0.1",
      SERVER_PORT: "18080",
      SPRING_DATASOURCE_URL: "jdbc:postgresql://127.0.0.1:15433/hotel_local",
      SPRING_DATASOURCE_USERNAME: "hotel_local",
      SPRING_DATASOURCE_PASSWORD: credentials.dbPassword,
      SPRING_DATA_MONGODB_URI: `mongodb://hotel_local:${credentials.mongoPassword}@127.0.0.1:17017/hotel_local?authSource=admin`,
      SPRING_KAFKA_BOOTSTRAP_SERVERS: "127.0.0.1:19092",
      LOCAL_DB_PASSWORD: credentials.dbPassword,
      LOCAL_MONGO_PASSWORD: credentials.mongoPassword,
      LOCAL_USER_PASSWORD: credentials.password,
      LOCAL_QUOTE_KEY: credentials.quoteKey,
      APP_LOCAL_READY_FILE: readyFile,
      APP_LOCAL_RUN_ID: runId,
      APP_LOCAL_ENVIRONMENT_ID: marker,
      APP_LOCAL_SEED_ENABLED: "true",
      APP_LOCAL_USERNAME: credentials.username,
      APP_LOCAL_PASSWORD: credentials.password,
      APP_BOOTSTRAP_ADMIN_ENABLED: "true",
      APP_BOOTSTRAP_ADMIN_NAME: credentials.adminUsername,
      APP_BOOTSTRAP_ADMIN_EMAIL: "local-admin@example.invalid",
      APP_BOOTSTRAP_ADMIN_PASSWORD: credentials.adminPassword,
      LOCAL_ADMIN_PASSWORD: credentials.adminPassword,
      LOCAL_MEDIA_DIRECTORY: mediaDirectory,
      APP_MEDIA_DIRECTORY: mediaDirectory,
      APP_WEB_V2_ENABLED: "true",
      APP_WEB_V2_REGISTRATION_MODE: "CLOSED",
      APP_WEB_V2_QUOTE_ENCRYPTION_KEY: credentials.quoteKey,
      SPRING_FLYWAY_BASELINE_ON_MIGRATE: "false",
      SPRING_JPA_HIBERNATE_DDL_AUTO: "validate",
    },
    stdio: ["ignore", backendLog.fd, backendLog.fd],
  });
  backend = app.proc;
  backendDone = app.done;
  let ready = false;
  for (let n = 0; n < 120 && !stopping; n++) {
    if (backend.exitCode !== null) break;
    try {
      const signal = JSON.parse(await readFile(readyFile, "utf8"));
      if (signal.runId !== runId || signal.environmentId !== marker)
        throw new Error("Unexpected readiness marker");
      const res = await fetch(`${backendOrigin}/api/v2/cities`, {
        signal: AbortSignal.timeout(1500),
      });
      if (res.ok) {
        ready = true;
        break;
      }
    } catch {}
    await delay(1000);
  }
  if (!ready)
    throw new Error("Backend did not become ready; see .local/backend.log");
  if (stopping) throw new Error("Startup cancelled");
  const ui = child(
    process.execPath,
    [
      path.join(repo, "frontend/node_modules/next/dist/bin/next"),
      "dev",
      "--hostname",
      "127.0.0.1",
      "--webpack",
      "--port",
      "3000",
    ],
    {
      cwd: path.join(repo, "frontend"),
      env: {
        ...process.env,
        NODE_ENV: "development",
        BROWSER_BACKEND_ORIGIN: backendOrigin,
        PUBLIC_ORIGIN: origin,
        LOCAL_HTTP_MODE: "1",
        FRONTEND_QA_MODE: "0",
      },
    },
  );
  web = ui.proc;
  webDone = ui.done;
  console.log(`Persistent local site: ${origin}/`);
  console.log(
    "Login username: local-user. Password is in .local/login.txt (owner-only file).",
  );
  console.log(
    "Stop: Ctrl+C or ./dev stop. Status: ./dev status. Data survives both commands and restarts.",
  );
  const exited = await Promise.race([
    backendDone.then((code) => ({ name: "Backend", code })),
    webDone.then((code) => ({ name: "Frontend", code })),
  ]);
  if (!stopping)
    throw new Error(
      `${exited.name} exited unexpectedly (${exited.code}); see local logs`,
    );
} catch (error) {
  process.exitCode = 1;
  console.error(error.message);
} finally {
  await stop();
}
