import { createRequire } from "node:module"
import { createHash } from "node:crypto"
import { mkdir, readFile, writeFile } from "node:fs/promises"
import path from "node:path"
import { fileURLToPath } from "node:url"

const root = fileURLToPath(new URL("../", import.meta.url))
const args = process.argv.slice(2)
if (args.some((arg) => arg !== "--check" && !arg.startsWith("--web-root=")))
  throw new Error("Use [--check] [--web-root=<CustodySim checkout>]")
const webRoot = path.resolve(args.find((arg) => arg.startsWith("--web-root="))?.slice(11) ?? path.join(root, "..", "CustodySim"))
const ts = createRequire(path.join(webRoot, "package.json"))("typescript")
const versionsSource = await readFile(path.join(webRoot, "lib/legal-version.ts"), "utf8")
const versions = Object.fromEntries([...versionsSource.matchAll(/export const (\w+) = "([^"]+)"/g)].map((match) => [match[1], match[2]]))

// Match JSX's handling of indentation and wrapped source lines.
function jsxText(text) {
  const lines = text.replaceAll("\t", " ").split(/\r\n|\n|\r/)
  const last = lines.findLastIndex((line) => /[^ ]/.test(line))
  return lines.map((line, index) => {
    let value = line
    if (index !== 0) value = value.replace(/^ +/, "")
    if (index !== lines.length - 1) value = value.replace(/ +$/, "")
    return value ? value + (index !== last ? " " : "") : ""
  }).join("")
}
function attribute(element, name) {
  const value = element.openingElement.attributes.properties.find((item) => item.name?.text === name)?.initializer
  if (!value || !ts.isStringLiteral(value)) throw new Error(`Expected a static ${name}`)
  return value.text
}
function plainText(node) {
  if (ts.isJsxText(node)) return jsxText(node.text)
  if (ts.isJsxExpression(node)) {
    if (!node.expression) return ""
    if (ts.isStringLiteral(node.expression)) return node.expression.text
    throw new Error("Dynamic legal text must be reviewed before exporting")
  }
  if (!ts.isJsxElement(node)) throw new Error("Unsupported legal content")
  const text = node.children.map(plainText).join("")
  return node.openingElement.tagName.getText() === "a"
    ? `${text} (${attribute(node, "href")})`
    : text
}
for (const [kind, versionKey] of [["privacy", "PRIVACY_VERSION"], ["terms", "TERMS_VERSION"]]) {
  const source = await readFile(path.join(webRoot, `app/${kind}/page.tsx`), "utf8")
  const file = ts.createSourceFile(`${kind}.tsx`, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX)
  let layout
  function find(node) {
    if (ts.isJsxElement(node) && node.openingElement.tagName.getText() === "LegalLayout") layout = node
    ts.forEachChild(node, find)
  }
  find(file)
  if (!layout) throw new Error(`Missing legal layout in ${kind}`)
  const blocks = []
  function collect(node) {
    if (ts.isJsxText(node) && !node.text.trim()) return
    if (!ts.isJsxElement(node)) throw new Error(`Unsupported legal block in ${kind}`)
    const tag = node.openingElement.tagName.getText()
    if (["section", "ul"].includes(tag)) return node.children.forEach(collect)
    if (!["p", "h2", "li"].includes(tag)) throw new Error(`Unsupported legal tag ${tag}`)
    const text = plainText(node).trim()
    if (!text) throw new Error(`Empty legal block in ${kind}`)
    blocks.push({ type: tag === "h2" ? "heading" : "paragraph", text: (tag === "li" ? "• " : "") + text })
  }
  layout.children.forEach(collect)
  const version = versions[versionKey], effectiveDate = versions.LEGAL_EFFECTIVE_DATE
  if (!version || !effectiveDate) throw new Error("Missing published legal version")
  const document = {
    title: attribute(layout, "title"), version, effectiveDate,
    source: `app/${kind}/page.tsx`,
    sourceSha256: createHash("sha256").update(source.replaceAll("\r\n", "\n")).digest("hex"),
    blocks,
  }
  const output = path.join(root, "app/src/main/assets/legal", `${kind}.json`)
  const expected = JSON.stringify(document, null, 2) + "\n"
  if (args.includes("--check")) {
    if ((await readFile(output, "utf8")).replaceAll("\r\n", "\n") !== expected)
      throw new Error(`${kind} differs from the published Web document; sync before release`)
  } else {
    await mkdir(path.dirname(output), { recursive: true })
    await writeFile(output, expected)
  }
  console.log(`${kind}: ${version}, ${blocks.filter((block) => block.type === "heading").length} sections, ${blocks.length} blocks${args.includes("--check") ? " verified" : " exported"}`)
}
