import { mkdir, access, writeFile, chmod } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { spawnSync } from "node:child_process";
const dir = fileURLToPath(new URL("../certificates/", import.meta.url));
await mkdir(dir, { recursive: true });
const key = path.join(dir, "localhost-key.pem"),
  cert = path.join(dir, "localhost.pem");
const exists = async (p) => {
  try {
    await access(p);
    return true;
  } catch {
    return false;
  }
};
if ((await exists(key)) !== (await exists(cert)))
  throw new Error(
    "A partial certificate pair exists; refusing to overwrite it.",
  );
if (!(await exists(cert))) {
  const config = path.join(dir, "localhost.cnf");
  await writeFile(
    config,
    "[req]\ndistinguished_name=dn\nx509_extensions=ext\nprompt=no\n[dn]\nCN=Hotel Reservation local preview\n[ext]\nsubjectAltName=DNS:localhost,IP:127.0.0.1\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n",
  );
  const generated = spawnSync(
    "openssl",
    [
      "req",
      "-x509",
      "-newkey",
      "rsa:2048",
      "-nodes",
      "-days",
      "14",
      "-keyout",
      key,
      "-out",
      cert,
      "-config",
      config,
    ],
    { stdio: "pipe" },
  );
  if (generated.status !== 0)
    throw new Error("Could not generate the local certificate with OpenSSL.");
  await chmod(key, 0o600);
}
if (
  spawnSync("openssl", ["x509", "-in", cert, "-checkend", "0", "-noout"], {
    stdio: "ignore",
  }).status !== 0
)
  throw new Error(
    "Local certificate expired. Replace this preview certificate explicitly before HTTPS use.",
  );
console.log("Local certificate ready. OS/browser trust was not changed.");
spawnSync(
  "openssl",
  ["x509", "-in", cert, "-noout", "-fingerprint", "-sha256", "-dates"],
  { stdio: "inherit" },
);
