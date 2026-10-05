import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';

// Import a pinned, reviewable dependency closure; never run upstream build scripts.
const root = path.resolve('artifacts/episteme-upstream');
const destination = path.resolve('episteme-core');
const revision = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
const pinnedRevision = '92b9d0abbd0f28a950006cb655c1bd6ab3ef097a';
if (revision !== pinnedRevision) throw new Error('Upstream checkout differs from the reviewed revision');
function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const name = path.join(dir, entry.name);
    return entry.isDirectory() ? walk(name) : name.endsWith('.kt') ? [name] : [];
  });
}
const files = [...['commonMain', 'readerJvmMain'].flatMap(source => walk(path.join(root, 'shared/src', source, 'kotlin'))),
  path.join(root, 'app/src/main/java/com/aryan/reader/paginatedreader/Paginator.kt')]
  .map(file => {
    const text = fs.readFileSync(file, 'utf8');
    const pkg = text.match(/^package ([\w.]+)/m)?.[1];
    const symbols = [...text.matchAll(/^(?:(?:internal|public|inline|suspend|expect|actual|data|sealed|enum|annotation|value|open|abstract)\s+)*(?:class|interface|object|typealias|fun|val|const val)\s+(?:[\w<>?.]+\.)?([A-Za-z_]\w*)/gm)].map(match => match[1]);
    return { file, text, pkg, symbols };
  });
const seeds = new Set(['HtmlParser.kt', 'CssParser.kt', 'SemanticModel.kt', 'SharedContentStyler.kt', 'SharedPaginator.kt', 'PaginatedReaderData.kt', 'Paginator.kt', 'RubyRendering.kt', 'ReaderBlockModifiers.kt']);
const selected = new Set(files.filter(file => seeds.has(path.basename(file.file))));
let changed = true;
while (changed) {
  changed = false;
  for (const current of [...selected]) {
    const imports = [...current.text.matchAll(/^import (com\.aryan\.reader\.[\w.*]+)/gm)].map(match => match[1]);
    for (const candidate of files) {
      if (selected.has(candidate)) continue;
      const imported = imports.some(value => value === candidate.pkg + '.*' || candidate.symbols.some(name => value === candidate.pkg + '.' + name));
      const code = current.text.replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*/g, '');
      const localReference = candidate.pkg === current.pkg && candidate.symbols.some(name => new RegExp('\\b' + name + '\\b').test(code));
      if (imported || localReference) { selected.add(candidate); changed = true; }
    }
  }
}
const manifest = [];
// Remove only previously generated files named in our own import manifest.
const manifestPath = path.join(destination, 'UPSTREAM.json');
if (fs.existsSync(manifestPath)) {
  for (const old of JSON.parse(fs.readFileSync(manifestPath, 'utf8')).files) {
    const target = path.resolve(destination, old.local);
    if (!target.startsWith(destination + path.sep)) throw new Error('Invalid generated path');
    fs.rmSync(target, { force: true });
  }
}
for (const item of selected) {
  const relative = path.relative(root, item.file).replaceAll('\\', '/');
  const source = relative.includes('/kotlin/') ? relative.split('/kotlin/')[1] : relative.split('/java/')[1];
  const target = path.join(destination, 'src/main/java', source);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  let adapted = path.basename(item.file) === 'Paginator.kt'
    ? item.text.replace('import com.aryan.reader.BuildConfig', 'import com.custodysim.reader.episteme.BuildConfig')
      .replace(/^@RequiresApi\([^\n]*\)\r?\n/gm, '').replace(/^import android.os.Build\r?\n|^import androidx.annotation.RequiresApi\r?\n/gm, '')
    : item.text;
  if (path.basename(item.file) === 'CssParser.kt') adapted = adapted.replace('val result = parseExpression?.invoke()', 'val result = parseExpression()');
  if (path.basename(item.file) === 'ReaderLinkStyle.kt') {
    // Publication links and footnotes should not look permanently selected.
    adapted = adapted.replace(/    val backgroundAlpha = [^\r\n]*\r?\n/, '')
      .replace('background = linkColor.copy(alpha = backgroundAlpha)', 'background = Color.Transparent');
  }
  if (path.basename(item.file) === 'Paginator.kt') {
    adapted = adapted.replace('maxHeightPx = pageBoundHeightPx.toFloat()', 'maxHeightPx = pageBoundHeightPx')
      .replace('        else -> part\r\n', '').replace('        else -> part\n', '')
      .replace('bestCandidate ?: return null', 'bestCandidate');
    adapted = adapted.replace('private data class BlockBoxMetrics', 'data class BlockBoxMetrics')
      .replace('private fun computeBlockBoxMetrics', 'fun computeBlockBoxMetrics')
      .replace('private fun measureScaledImageSizePx', 'fun measureScaledImageSizePx');
    adapted = adapted.replace('import android.util.Log\r\n', '').replace('import android.util.Log\n', '')
      .replace('Log.d(AndroidEpubCutoffLogTag, message)', 'Timber.tag(AndroidEpubCutoffLogTag).d(message)')
      .replace('Log.d(AndroidEpubPageGapDiagLogTag, message)', 'Timber.tag(AndroidEpubPageGapDiagLogTag).d(message)');
  }
  fs.writeFileSync(target, adapted);
  manifest.push({ upstream: relative, local: 'src/main/java/' + source, upstreamSha256: crypto.createHash('sha256').update(item.text).digest('hex'), sha256: crypto.createHash('sha256').update(adapted).digest('hex'), modified: adapted !== item.text });
}
fs.copyFileSync(path.join(root, 'LICENSE'), path.join(destination, 'LICENSE'));
fs.writeFileSync(path.join(destination, 'UPSTREAM.json'), JSON.stringify({ repository: 'https://github.com/Aryan-Raj3112/episteme', revision, license: 'AGPL-3.0-only', files: manifest.sort((a, b) => a.local.localeCompare(b.local)) }, null, 2) + '\n');
console.log(`Imported ${selected.size} files from ${revision}`);
