import { mkdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

// Original deterministic text; no accounts, documents or service addresses enter this fixture.
const paragraph = '阅读性能样本包含中文、English、数字12345与🙂。每次滑动只提交一屏，段落顺序和文字锚点保持一致。';
const escape = value => value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
function section(index, paragraphs) {
  let text = '', html = '', offset = 0;
  for (let p = 0; p < paragraphs; p++) {
    const value = `第${index + 1}节第${p + 1}段。${paragraph.repeat(3)}`;
    text += `\n${value}\n`; offset++;
    html += `<p><span data-reader-text-offset="${offset}">${escape(value)}</span></p>`;
    offset += Array.from(value).length + 1;
  }
  return { path: `section-${index}.xhtml`, title: `性能样本第${index + 1}节`, html, text, start: 0, length: offset, linear: true };
}
function document(chapters, key) {
  let start = 0;
  const result = chapters.map(chapter => { const value = { ...chapter, start }; start += chapter.length; return value; });
  return { version: 1, chapters: result, toc: result.map((c, chapter) => ({ title: c.title, chapter, fragment: '', depth: 0 })),
    pages: Math.max(1, Math.ceil(start / 2000)), revision: 'benchmark-v1', readerKey: key, layout: 'reflow', styles: '', startChapter: 0 };
}
const flow = document(Array.from({ length: 24 }, (_, i) => section(i, 70)), 'segmented');
const source = flow.chapters.map(chapter => chapter.text).join('');
let offset = 0;
const joinedHtml = flow.chapters.map(chapter => {
  const html = chapter.html.replace(/data-reader-text-offset="(\d+)"/g, (_, local) => `data-reader-text-offset="${Number(local) + offset}"`);
  offset += chapter.length; return html;
}).join('');
const plain = document([{ path: 'document', title: '性能样本', text: source, html: joinedHtml, length: offset, start: 0, linear: true }], 'single');
const cross = document(Array.from({ length: 24 }, (_, i) => section(i, 1)), 'cross');
const image = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j8l0AAAAASUVORK5CYII=';
const pictures = document(flow.chapters.map((chapter, index) => ({ ...chapter,
  html: `<img id="picture-${index}" src="${image}" style="width:160px;height:220px" alt="性能样本插图">${chapter.html}` })), 'pictures');
const fixture = { version: 1, sha256: createHash('sha256').update(source).digest('hex'), codePoints: Array.from(source).length,
  single: plain, segmented: flow, cross, pictures };
const directory = fileURLToPath(new URL('../app/src/benchmark/assets/reader-benchmark/', import.meta.url));
mkdirSync(directory, { recursive: true });
writeFileSync(`${directory}/book.json`, JSON.stringify(fixture));
console.log(`Reader fixture: ${fixture.codePoints} code points; SHA256 ${fixture.sha256}`);
