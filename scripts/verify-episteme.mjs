import { createHash } from "node:crypto"
import { readFileSync } from "node:fs"
import { resolve, sep } from "node:path"

const root = resolve("episteme-core")
const manifest = JSON.parse(readFileSync(resolve(root, "UPSTREAM.json"), "utf8"))
for (const entry of manifest.files) {
  const path = resolve(root, entry.local)
  if (!path.startsWith(root + sep)) throw new Error("Invalid vendored source path")
  const sha = createHash("sha256").update(readFileSync(path)).digest("hex")
  if (sha !== entry.sha256) throw new Error(`Stale Episteme manifest: ${entry.local}`)
}
console.log(`Verified ${manifest.files.length} vendored files at ${manifest.revision}`)
