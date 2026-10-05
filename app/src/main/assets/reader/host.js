/* Trusted application code only. Book documents remain script-free sandboxed frames. */
const Reader = (() => {
  const config = window.readerConfig, stage = document.getElementById('stage');
  const notice = document.getElementById('loading');
  const frames = [0, 1].map(() => {
    const frame = document.createElement('iframe');
    frame.setAttribute('sandbox', 'allow-same-origin'); frame.setAttribute('title', config.title);
    frame.setAttribute('aria-hidden','true');
    stage.append(frame); return frame;
  });
  const parsed = new Map(), descriptors = new Map();
  let active = null, spare = null, generation = 0, serial = 0, frameSerial = 0;
  let w = innerWidth, h = innerHeight, animation = 0, animationKind = '', touch = null;
  let drag = 0, queue = [], pending = null, query = '', suppressed = 0;
  let nextJob = null, previousJob = null, preparation = 0, scrollTimer = 0, spareDirection = 0, scrollLoading = false;
  let spareTarget = null, lastDirection = 1;
  const paged = config.mode === 'paged' && !config.fixed;
  const pageMode = paged || config.fixed;
  let dragNeighbour = null, dragDirection = 1;
  const cp = text => Array.from(text).length;
  const attrs = source => {
    const root = new DOMParser().parseFromString('<body>' + source + '</body>', 'text/html').body;
    return root;
  };
  function publish(error = '') {
    if (!active || animation || touch) return;
    const value = location();
    value.generation = config.generation; value.error = error;
    window.ReaderBridge?.publish(JSON.stringify(value));
  }
  function splitNode(node, budget = 12000) {
    if (node.nodeType !== Node.ELEMENT_NODE || node.textContent.length <= budget ||
        node.matches('table,figure,pre,[style*="float"]')) return [node.outerHTML || node.textContent];
    // Split a large wrapper structurally, carrying ancestors, list numbering and text offsets.
    const result = []; let wrapper = node.cloneNode(false), size = 0, number = Number(node.getAttribute('start') || 1);
    function flush() {
      if (!wrapper.childNodes.length) return;
      result.push(wrapper.outerHTML); number += wrapper.children.length;
      wrapper = node.cloneNode(false); wrapper.setAttribute('data-continuation', '');
      if (node.tagName === 'OL') wrapper.setAttribute('start', String(number));
      size = 0;
    }
    for (const child of node.childNodes) {
      const parts = child.nodeType === Node.ELEMENT_NODE ? splitNode(child, budget) : [child.textContent];
      for (const part of parts) {
        const fragment = attrs(part);
        if (size >= budget) flush();
        while (fragment.firstChild) wrapper.append(fragment.firstChild);
        size += part.length;
      }
    }
    flush(); return result;
  }
  // Chapter markup is fetched one spine item at a time. The host document carries metadata only:
  // embedding the whole book made it as large as the book itself, and each fetch is deduplicated
  // like an in-flight fill promise so two callers cannot parse the same chapter twice.
  const chapterPayloads = new Map(), chapterJobs = new Map();
  async function chapterSource(chapter) {
    const loaded = chapterPayloads.get(chapter);
    if (loaded) return loaded;
    const inFlight = chapterJobs.get(chapter);
    if (inFlight) return inFlight;
    const job = (async () => {
      const response = await fetch('https://reader.invalid/reader/chapter/' + chapter, { cache: 'no-store' });
      if (!response.ok) throw new Error('章节暂不可用，请重试');
      const payload = await response.json();
      chapterPayloads.set(chapter, payload);
      while (chapterPayloads.size > 3) chapterPayloads.delete(chapterPayloads.keys().next().value);
      return payload;
    })();
    chapterJobs.set(chapter, job);
    try { return await job; } finally { chapterJobs.delete(chapter); }
  }
  async function sections(chapter) {
    if (parsed.has(chapter)) return parsed.get(chapter);
    const payload = await chapterSource(chapter);
    if(payload.parts?.length)return payload.parts;
    const source = config.chapters[chapter], content = payload.html ?? '', root = attrs(content), result = [];
    [...root.querySelectorAll('img')].forEach((image,index)=>{if(!image.id)image.id=`reader-image-${chapter}-${index}`;});
    if(config.fixed){const result=[{html:root.innerHTML,start:0,end:source.length||0,ids:[...root.querySelectorAll('[id]')].map(el=>el.id)}];parsed.set(chapter,result);return result;}
    let holder = document.createElement('div'), size = 0;
    const flush = () => {
      if (!holder.childNodes.length) return;
      const spans = [...holder.querySelectorAll('[data-reader-text-offset]')];
      const start = spans.length ? Number(spans[0].dataset.readerTextOffset) : (result.at(-1)?.end || 0);
      const last = spans.at(-1);
      const end = last ? Number(last.dataset.readerTextOffset) + cp(last.textContent) : start;
      result.push({html: holder.innerHTML, start, end, ids: [...holder.querySelectorAll('[id]')].map(el => el.id)});
      holder = document.createElement('div'); size = 0;
    };
    for (const node of [...root.childNodes]) for (const part of splitNode(node)) {
      if (size >= 18000) flush();
      const fragment = attrs(part); while (fragment.firstChild) holder.append(fragment.firstChild);
      size += part.length;
    }
    flush();
    if (!result.length) result.push({html: content, start: 0, end: 0, ids: []});
    // Source markup stays cached, detached DOMs do not. The file limits are enforced by the server.
    parsed.set(chapter, result);
    while (parsed.size > 3) parsed.delete(parsed.keys().next().value);
    return result;
  }
  async function descriptor(chapter, offset = 0, fragment = '') {
    const parts = await sections(chapter);
    let index = fragment ? parts.findIndex(part => part.ids.includes(fragment)) : -1;
    if (index < 0) index = offset === 2147483647 ? parts.length - 1 : Math.max(0, parts.findLastIndex(part => part.start <= offset));
    const first = Math.max(0, index - 1), end = Math.min(parts.length, first + 3);
    return remember({id: ++serial, chapter, html: parts.slice(first, end).map((part,i) => config.fixed ? part.html : `<section data-reader-piece="${first+i}">${part.html}</section>`).join(''), first,end,
      start: parts[first].start, previous: null, previousScreen: 0, final: end === parts.length});
  }
  function remember(value) {
    descriptors.set(value.id, value);
    while (descriptors.size > 8) descriptors.delete(descriptors.keys().next().value);
    return value;
  }
  function markup(desc) {
    const chapter = config.chapters[desc.chapter];
    const fixed = config.fixed;
    return '<!doctype html><html class="'+(!paged&&!fixed?'scrolled':'')+'"><head><meta name="viewport" content="width=device-width,initial-scale=1">' +
      '<link rel="stylesheet" href="https://reader.invalid/reader/document.css"><style>' + (chapter.styles ?? config.styles) +
      '</style><style>:root{background:' + config.background + ';color:' + config.ink + '}body{font:' + config.font +
      'px ' + config.family + ';line-height:' + config.spacing + '}</style></head><body class="' +
      (fixed ? 'fixed' : paged ? '' : 'scrolled') + '"><div id="viewport"><main id="flow">' +
      desc.html + '</main><i id="tail"></i></div></body></html>';
  }
  const nodeRect = el => { const range = el.ownerDocument.createRange(); range.selectNodeContents(el); return [...range.getClientRects()]; };
  function textPoint(view, offset) {
    const spans = view.spans; let low = 0, high = spans.length;
    while (low < high) { const mid = (low + high) >>> 1; if (Number(spans[mid].dataset.readerTextOffset) <= offset) low = mid + 1; else high = mid; }
    const el = spans[Math.max(0, low - 1)], node = el?.firstChild;
    if (!node || node.nodeType !== Node.TEXT_NODE) return null;
    const chars = [...node.textContent], index = Math.max(0, Math.min(offset - Number(el.dataset.readerTextOffset), chars.length));
    let unit = 0; for (let i = 0; i < index; i++) unit += chars[i].length;
    return {node, unit};
  }
  function pageAnchor(view, screen = view.screen) {
    if (view.anchors.has(screen)) return view.anchors.get(screen);
    // A page-slide transform moves the content without moving the scroll offset, so rects must be
    // measured against the layout position rather than the drawn one.
    const shift = view.slide || 0;
    const left = screen * w + 18, right = (screen + 1) * w - 18;
    // Current spans have a bounded count; previous cached anchors narrow the forward scan.
    const start = view.anchors.get(screen - 1)?.span || 0;
    for (let i = start; i < view.spans.length; i++) {
      const el = view.spans[i]; if (!el.textContent.trim()) continue;
      const rects = nodeRect(el).map(r => ({left:r.left + view.viewport.scrollLeft - shift,right:r.right + view.viewport.scrollLeft - shift,top:r.top,bottom:r.bottom}));
      if (!rects.some(r => r.right > left && r.left < right && r.bottom > 24 && r.top < h - 24)) continue;
      const node = el.firstChild;
      if (!node || node.nodeType !== Node.TEXT_NODE) continue;
      const units = [0]; for (const char of node.textContent) units.push(units.at(-1) + char.length);
      const range = view.doc.createRange(); range.setStart(node, 0);
      let low = 0, high = units.length - 1;
      while (low < high) {
        const mid = (low + high) >>> 1; range.setEnd(node, units[mid + 1]);
        if ([...range.getClientRects()].some(r => r.right + view.viewport.scrollLeft - shift > left && r.left + view.viewport.scrollLeft - shift < right && r.bottom > 24 && r.top < h - 24)) high = mid; else low = mid + 1;
      }
      const value = {offset:Number(el.dataset.readerTextOffset) + low,span:i,node,unit:units[low]};
      view.anchors.set(screen, value); return value;
    }
    // Image-only pages retain an element index independently from coarse text progress.
    const image = [...view.flow.querySelectorAll('img')].find(el => nodeRect(el).some(r => r.right + view.viewport.scrollLeft - shift > left && r.left + view.viewport.scrollLeft - shift < right));
    return {offset:view.desc.start,span:0,image: image?.id || '',fraction:screen / Math.max(1,view.pages - 1)};
  }
  function geometry(view) {
    view.viewport.style.width = w + 'px'; view.viewport.style.height = h + 'px';
    if (config.fixed) {
      const metadata = config.chapters[view.desc.chapter];
      const sw = metadata.viewportWidth || Math.max(w, view.flow.scrollWidth);
      const sh = metadata.viewportHeight || Math.max(h, view.flow.scrollHeight);
      view.flow.style.setProperty('--source-width', sw + 'px'); view.flow.style.setProperty('--source-height', sh + 'px');
      view.flow.style.setProperty('--page-scale', Math.min(w/sw,h/sh)); view.pages = 1;
    } else if (paged) {
      view.flow.style.width = (w-36) + 'px'; view.flow.style.height = (h-48) + 'px'; view.flow.style.columnWidth = (w-36) + 'px';
      view.pages = Math.max(1, Math.ceil((view.flow.scrollWidth + 35) / w));
      view.doc.getElementById('tail').style.left = (view.pages * w - 1) + 'px';
    } else view.pages = 1;
    view.anchors.clear(); view.screen = Math.min(view.screen, view.pages - 1);
  }
  /**
   * Bounded in-place window. Forward movement inside a chapter appends the next fragments to the live
   * flow and retires the ones far behind the reader, so a turn never rebuilds the document: there is
   * no re-parse, no style recalc and no iframe navigation. After retiring content the retained
   * passage is re-seeked, which keeps the visible text exactly where it was.
   */
  const WINDOW_PARTS = 4, WINDOW_KEEP = 3;
  function growWindow() {
    if (!active || config.fixed || !paged || active.desc.final) return false;
    const view = active, parts = parsed.get(view.desc.chapter);
    if (!parts || !Number.isFinite(view.desc.first)) return false;
    const end = Math.min(parts.length, Math.max(view.desc.end, view.desc.first + WINDOW_PARTS));
    if (end <= view.desc.end) return false;
    for (let index = view.desc.end; index < end; index++) {
      const holder = view.doc.createElement('section');
      holder.dataset.readerPiece = String(index); holder.innerHTML = parts[index].html;
      view.flow.append(holder);
    }
    view.desc.end = end; view.desc.final = end >= parts.length;
    view.spans = [...view.flow.querySelectorAll('[data-reader-text-offset]')];
    view.targets.clear();
    geometry(view);
    return true;
  }
  function trimWindow() {
    if (!active || config.fixed || !paged) return false;
    const view = active;
    if (!Number.isFinite(view.desc.first) || view.desc.first >= view.desc.end - WINDOW_KEEP) return false;
    const retained = location();
    let removed = false;
    while (view.desc.first < view.desc.end - WINDOW_KEEP && view.flow.firstElementChild?.hasAttribute('data-reader-piece')) {
      view.flow.firstElementChild.remove(); view.desc.first++; removed = true;
    }
    if (!removed) return false;
    const first = parsed.get(view.desc.chapter)?.[view.desc.first];
    if (first) view.desc.start = first.start;
    view.spans = [...view.flow.querySelectorAll('[data-reader-text-offset]')];
    view.anchors.clear(); view.targets.clear();
    geometry(view);
    seek(view, retained.offset, retained.fragment || '');
    return true;
  }
  async function load(desc, frame, target = 0, fragment = '') {
    const token = ++frameSerial; frame.dataset.token = String(token);
    const loaded = new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('文档加载超时')), 15000);
      frame.onload = () => { clearTimeout(timeout); resolve(); };
    });
    frame.srcdoc = markup(desc); await loaded;
    if (frame.dataset.token !== String(token)) throw new Error('superseded');
    const doc = frame.contentDocument;
    const view = {frame,doc,desc,flow:doc.getElementById('flow'),viewport:doc.getElementById('viewport'),
      spans:[...doc.querySelectorAll('[data-reader-text-offset]')],anchors:new Map(),targets:new Map(),screen:0,pages:1,slide:0};
    // Only the first pending picture gates readiness. Waiting for every picture in the fragment let
    // one slow image hold the whole cross-section turn for up to three seconds per image.
    for (const image of doc.images) image.decoding = 'async';
    const firstPicture = [...doc.images].find(image => !image.complete);
    await (firstPicture ? new Promise(resolve => { const done = () => resolve();
      firstPicture.addEventListener('load',done,{once:true}); firstPicture.addEventListener('error',done,{once:true}); setTimeout(done,3000); }) : Promise.resolve());
    if (frame.dataset.token !== String(token)) throw new Error('superseded');
    geometry(view); seek(view,target,fragment); bind(view);
    if(paged) {
      let changed=false;
      for(const table of view.flow.querySelectorAll('table'))if(table.scrollWidth>w-36||[...table.rows].some(row=>row.getBoundingClientRect().height>h-64)) {
        table.classList.add('reader-scroll-block');changed=true;
      }
      if(changed){geometry(view);seek(view,target,fragment);}
    }
    let relayout = 0;
    const settlePictures = () => {
      relayout = 0;
      if (view !== active || touch || animation) return;
      const retained = location(); geometry(view); seek(view,retained.offset,retained.fragment); prepare(); publish();
    };
    for (const image of doc.images) image.addEventListener('load', () => {
      // Every late picture used to re-run the whole layout and publish again; one frame per batch
      // is enough, and the retained anchor keeps the reader on the same passage.
      if (!relayout) relayout = requestAnimationFrame(settlePictures);
    });
    // Attached, non-display:none documents receive a drawing opportunity before readiness.
    frame.style.visibility = 'visible'; await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    return view;
  }
  function seek(view, offset = 0, fragment = '', fraction = null) {
    let rect = fragment ? view.doc.getElementById(fragment)?.getBoundingClientRect() : null;
    if (!rect && offset !== 2147483647) {
      const point = textPoint(view,offset);
      if (point) { const range = view.doc.createRange(); range.setStart(point.node,point.unit); range.setEnd(point.node,Math.min(point.node.length,point.unit+1)); rect = range.getClientRects()[0]; }
    }
    if (paged) {
      view.screen = offset === 2147483647 ? view.pages - 1 : rect ? Math.max(0,Math.min(view.pages - 1,Math.floor((rect.left + view.viewport.scrollLeft - (view.slide || 0) - 18 + .5) / w))) : Number.isFinite(fraction) ? Math.round(fraction*(view.pages-1)) : 0;
      slide(view, 0);
      view.viewport.scrollLeft = view.screen * w;
    } else if (!config.fixed) {
      const root = view.doc.scrollingElement;
      root.scrollTop = offset === 2147483647 ? root.scrollHeight : rect ? rect.top+root.scrollTop-24 : Number.isFinite(fraction) ? (root.scrollHeight-h)*fraction : 0;
    }
  }
  function location() {
    if (!active) return {chapter:config.chapter,offset:config.offset,screen:1,screens:1,atStart:true,atEnd:true};
    const root = active.doc.scrollingElement;
    let anchor = paged ? pageAnchor(active) : null;
    if (!anchor) {
      const el = active.spans.find(el => el.textContent.trim() && nodeRect(el).some(r => r.bottom>24 && r.top<h-24));
      anchor = {offset: el ? Number(el.dataset.readerTextOffset) : active.desc.start};
      if(el?.firstChild?.nodeType===Node.TEXT_NODE) {
        const node=el.firstChild,units=[0],range=active.doc.createRange();
        for(const char of node.textContent)units.push(units.at(-1)+char.length);
        let low=0,high=units.length-1;
        range.setStart(node,0);
        while(low<high){const mid=(low+high)>>>1;range.setEnd(node,units[mid+1]);if([...range.getClientRects()].some(r=>r.bottom>24))high=mid;else low=mid+1;}
        anchor.offset+=low;
      }
      const image=[...active.doc.images].find(image=>{const box=image.getBoundingClientRect();return box.bottom>24&&box.top<h-24&&(!el||box.top<el.getBoundingClientRect().top);});
      if(image)anchor.image=image.id;
    }
    const fraction = paged ? anchor.fraction || 0 : root.scrollTop / Math.max(1,root.scrollHeight-h);
    return {chapter:active.desc.chapter,offset:anchor.offset,fragment:anchor.image || '',scrollFraction:fraction,
      screen:active.screen+1,screens:paged ? active.pages : 1,
      atStart:paged ? active.screen===0 : root.scrollTop<=1,
      atEnd:paged ? active.screen>=active.pages-1 : root.scrollTop>=root.scrollHeight-h-4};
  }
  function stop() { cancelAnimationFrame(animation); animation=0; animationKind=''; }
  /**
   * Moves one page by transforming the column strip instead of writing `scrollLeft` every frame.
   * A per-frame scroll offset re-rasters the whole multi-column layout on the renderer main thread;
   * a transform stays on the compositor while the finger moves, and the offset is committed once.
   */
  function slide(view, value) {
    if (!view || config.fixed) return;
    view.slide = value;
    view.flow.style.willChange = value ? 'transform' : 'auto';
    view.flow.style.transform = value ? `translateX(${value}px)` : '';
  }
  function paint(value, neighbour = null, direction = 1) {
    drag = value;
    if (dragNeighbour && dragNeighbour !== neighbour) {
      active.frame.style.transform = 'translateX(0)';
      dragNeighbour.frame.style.transform = `translateX(${dragDirection*w}px)`;
      // The neighbour takes over the movement: a single-page slide would double the displacement.
      slide(active, 0);
    }
    dragNeighbour = neighbour; dragDirection = direction;
    if (neighbour) {
      active.frame.style.transform = `translateX(${value}px)`;
      neighbour.frame.style.transform = `translateX(${value+direction*w}px)`;
    } else slide(active, value);
  }
  function animateTo(to, apply, complete, enabled = true) {
    stop(); const from = drag;
    if (!enabled || Math.abs(to-from)<1) { apply(to); complete(); return; }
    const duration = Math.max(90,Math.min(200,200*Math.abs(to-from)/w)); let started;
    animationKind='settling';
    function tick(now) {
      started ??= now; const t=Math.min(1,(now-started)/duration); apply(from+(to-from)*(1-(1-t)**3));
      if (t<1) animation=requestAnimationFrame(tick); else { animation=0;animationKind='';complete(); }
    }
    animation=requestAnimationFrame(tick);
  }
  function tail(view) {
    const anchor = pageAnchor(view,view.pages-1);
    const range=view.doc.createRange();
    // A picture may precede the first text on this column. Carry it with the text.
    const image=[...view.flow.querySelectorAll('img,svg')].find(el=>nodeRect(el).some(r=>r.right+view.viewport.scrollLeft-(view.slide||0)>(view.pages-1)*w+18));
    if(image && (!anchor.node || image.compareDocumentPosition(anchor.node)&Node.DOCUMENT_POSITION_FOLLOWING))range.setStartBefore(image);
    else if(anchor.node)range.setStart(anchor.node,anchor.unit);
    else return view.desc.html;
    range.setEnd(view.flow,view.flow.childNodes.length);
    const container=document.createElement('div');container.append(range.cloneContents());
    const first=container.querySelector('[data-reader-text-offset]');
    if(first && anchor.node && first.textContent.length < anchor.node.length)first.dataset.readerTextOffset=String(anchor.offset);
    if (container.firstElementChild) container.firstElementChild.setAttribute('data-continuation','');
    return container.innerHTML;
  }
  async function adjacent(direction) {
    const owner=active;
    if(owner.targets.has(direction))return owner.targets.get(direction);
    const target=await makeAdjacent(direction);
    owner.targets.set(direction,target);
    return owner===active?target:null;
  }
  async function makeAdjacent(direction) {
    const owner=active, old=owner.desc;
    if (direction<0) {
      const previous=descriptors.get(old.previous);
      if (previous) return {desc:previous,screen:old.previousScreen};
      if (old.start>0) return {desc:await descriptor(old.chapter,Math.max(0,old.start-1)),offset:Math.max(0,old.start-1)};
    }
    if (direction>0 && !old.final && paged) {
      const parts=await sections(old.chapter),end=Math.min(parts.length,old.end+2);
      const anchor=pageAnchor(owner,owner.pages-1);
      return {desc:remember({id:++serial,chapter:old.chapter,html:tail(owner)+parts.slice(old.end,end).map(part=>part.html).join(''),
        first:old.end,end,start:anchor.offset,previous:old.id,previousScreen:Math.max(0,owner.pages-2),final:end===parts.length}),screen:0};
    }
    let chapter=old.chapter+direction;
    while (chapter>=0 && chapter<config.chapters.length && !config.chapters[chapter].linear) chapter+=direction;
    if (chapter<0 || chapter>=config.chapters.length) return null;
    return {desc:await descriptor(chapter,direction>0?0:2147483647),offset:direction>0?0:2147483647};
  }
  function prepare() {
    preparation++; nextJob=null;previousJob=null;
    // Reuse one spare frame, prioritized in the user's direction. No full-document bitmap captures.
    const epoch=preparation;
    const idle=()=>window.requestIdleCallback(()=>{
      if(epoch!==preparation||!pageMode||touch||pending)return;
      // A document load started mid-settle competes with the frames the reader is watching, so wait
      // for the animation instead of dropping the preparation.
      if(animation){window.setTimeout(idle,120);return;}
      growWindow(); trimWindow();
      warm(lastDirection);
    },{timeout:300});
    idle();
  }
  async function warm(direction) {
    if (!active) return null;
    const exists=direction>0?nextJob:previousJob;
    if (exists) return exists;
    let target=null;
    try { target=await adjacent(direction); } catch (_) { return null; }
    if (!target) return null;
    // The spare frame may already hold exactly this target; reloading it re-parses the same
    // document, which is what made repeated boundary drags expensive.
    if(spare&&spare!==active&&spare.frame!==active.frame&&spareDirection===direction&&spareTarget===target.desc.id&&
      (!Number.isFinite(target.screen)||spare.screen===target.screen))return spare;
    const epoch=preparation, owner=active;
    spare=null;spareTarget=null;spareDirection=0;
    const frame=frames.find(frame=>frame!==active.frame);
    const promise=(async()=>{
      frame.style.transform=`translateX(${direction*w}px)`;
      const view=await load(target.desc,frame,target.offset||0);
      if (epoch!==preparation || owner!==active) return null;
      if (Number.isFinite(target.screen)) {view.screen=target.screen;view.viewport.scrollLeft=view.screen*w;}
      spare=view;spareTarget=target.desc.id;spareDirection=direction;return view;
    })().catch(error=>{if(error.message!=='superseded') notice.textContent='正在准备相邻内容';return null;});
    if (direction>0) {nextJob=promise;previousJob=null;} else {previousJob=promise;nextJob=null;}
    return promise;
  }
  function isBoundary(direction) {
    return direction>0 ? active.screen>=active.pages-1-(active.desc.final?0:1) : active.screen===0;
  }
  async function cross(direction, animated) {
    if (pending) return true;
    const traceCookie=++serial;
    window.ReaderBridge?.traceCross?.(true,traceCookie);
    let next,token;
    try {
    let target=null;
    try { target=await adjacent(direction); } catch (error) { publish(error.message||'相邻内容暂不可用，请重试'); return true; }
    if (!target) {paint(0); publish();window.ReaderBridge?.boundary(direction);return false;}
    token=++generation; pending=token; notice.style.display='block';
    next=await warm(direction);notice.style.display='none';
    } finally { window.ReaderBridge?.traceCross?.(false,traceCookie); }
    if (pending!==token)return true;
    if (!next) {pending=null;paint(0);publish('相邻内容暂不可用，请重试');return true;}
    const previous=active;
    if (direction>0 && next.desc.chapter!==previous.desc.chapter) {
      next.desc.previous=previous.desc.id;next.desc.previousScreen=previous.pages-1;
    }
    paint(drag,next,direction);
    animateTo(-direction*w,value=>paint(value,next,direction),()=>{
      previous.frame.style.transform=`translateX(${-direction*w}px)`;
      previous.frame.setAttribute('aria-hidden','true');
      active=next;active.frame.setAttribute('aria-hidden','false');active.frame.style.transform='translateX(0)';dragNeighbour=null;
      // The parked frame becomes the spare, so it no longer stands for the target it was loaded for.
      slide(previous,0);slide(next,0);spare=previous;spareTarget=null;
      drag=0;pending=null;prepare();highlight();publish();drain();
    },animated);return true;
  }
  function turn(direction, animated=true) {
    if (!active) return true;
    if (pending) {if(queue.length<2)queue.push(direction);return true;}
    if (animation && !touch) {if(queue.length<2) queue.push(direction);return true;}
    lastDirection=direction;
    if (!paged && !config.fixed) {
      const root=active.doc.scrollingElement;
      if(direction>0&&root.scrollTop<root.scrollHeight-h-4||direction<0&&root.scrollTop>1){root.scrollBy({top:direction*(h-48),behavior:animated?'smooth':'instant'});return true;}
      if(direction>0&&!active.desc.final){extendScroll();return true;}
    }
    if (!paged) {cross(direction,animated);return true;}
    if (isBoundary(direction)) {
      // Inside a chapter the window grows in place; only a real chapter edge loads a new document.
      if (direction>0 && !active.desc.final && growWindow()) { /* fall through to the slide */ }
      else {cross(direction,animated);return true;}
    }
    const target=active.screen+direction;
    const to=-direction*w;
    animateTo(to,value=>paint(value),()=>{slide(active,0);active.screen=target;drag=0;active.viewport.scrollLeft=target*w;
      // Fill ahead while the reader is already at rest, so the next turn finds content in place.
      if(paged&&!config.fixed&&active.screen>=active.pages-2)growWindow();
      highlight();publish();drain();},animated);
    return true;
  }
  function drain() {if(queue.length) turn(queue.shift(),config.animate);}
  function selected(view=active) {const selection=view?.frame.contentWindow.getSelection();return selection&&!selection.isCollapsed;}
  function bind(view) {
    const doc=view.doc;
    const interactive=target=>target.closest?.('a,input,textarea,select,button,.reader-scroll-block');
    const x=point=>point.clientX+view.frame.getBoundingClientRect().left;
    doc.addEventListener('click',event=>{
      if(view!==active || performance.now()<suppressed || selected(view))return;
      const link=event.target.closest?.('a');
      if(link){event.preventDefault();window.ReaderBridge?.link(link.getAttribute('href'));return;}
      if(!interactive(event.target)){event.preventDefault();window.ReaderBridge?.controls();}
    });
    doc.addEventListener('touchstart',event=>{
      if((view!==active&&view!==dragNeighbour) || (pending&&!animation) || selected(view) || event.touches.length!==1)return;
      if(pending){generation++;pending=null;}
      stop();queue=[];const point=event.touches[0];
      const root=doc.scrollingElement;
      touch={owner:view,x:x(point),y:point.clientY,base:drag,last:x(point),time:event.timeStamp,velocity:0,link:!!interactive(event.target),moved:false,
        atTop:root.scrollTop<=1,atBottom:root.scrollTop>=root.scrollHeight-h-4};
      if(pageMode) warm(drag>0?-1:1);
    },{passive:true});
    doc.addEventListener('touchmove',event=>{
      if(!touch || touch.owner!==view)return;
      if(event.touches.length!==1 || selected(view)){touch=null;return;}
      const point=event.touches[0],dx=x(point)-touch.x,dy=point.clientY-touch.y;
      const dt=event.timeStamp-touch.time;if(dt>0)touch.velocity=(x(point)-touch.last)/dt;
      touch.last=x(point);touch.time=event.timeStamp;
      if(Math.abs(dx)+Math.abs(dy)>10)touch.moved=true;
      if(!pageMode || touch.link || Math.abs(dx)<10 || Math.abs(dx)<Math.abs(dy)*1.5)return;
      event.preventDefault();touch.dragging=true;
      const direction=dx<0?1:-1;const value=Math.max(-w,Math.min(w,touch.base+dx));
      if(isBoundary(direction)) {
        warm(direction);
        const ready=spare&&spareDirection===direction;
        paint(value*(ready?1:.18),ready?spare:null,direction);
      } else paint(value);
    },{passive:!pageMode});
    function end(event,cancelled=false) {
      if(!touch || touch.owner!==view)return;const previous=touch;touch=null;
      if(previous.moved)suppressed=performance.now()+400;
      if(!pageMode){
        const dy=(event.changedTouches[0]?.clientY??previous.y)-previous.y;
        trimScroll();publish();
        if(!cancelled&&!previous.link&&Math.abs(dy)>64){
          if(dy<0&&previous.atBottom&&active.desc.final)cross(1,false);
          else if(dy>0&&previous.atTop&&active.desc.first===0)cross(-1,false);
        }
        return;
      }
      const dx=event.changedTouches[0]?x(event.changedTouches[0])-previous.x:0;
      const flick=event.timeStamp-previous.time<100&&Math.abs(previous.velocity)>.45&&Math.abs(dx)>12;
      if(!cancelled&&previous.dragging&&(Math.abs(dx)>=Math.max(28,w*.08)||flick))turn(dx<0?1:-1,config.animate);
      else {const neighbour=dragNeighbour,direction=dragDirection;animateTo(0,value=>paint(value,neighbour,direction),()=>{paint(0);publish();},config.animate);}
    }
    doc.addEventListener('touchend',event=>end(event),{passive:!pageMode});
    doc.addEventListener('touchcancel',event=>end(event,true),{passive:true});
    doc.addEventListener('scroll',()=>{
      if(view!==active || paged)return;
      const root=doc.scrollingElement;
      if(root.scrollHeight-root.scrollTop-h<2*h&&!active.desc.final)extendScroll();
      if(root.scrollTop<2*h&&active.desc.first>0)prependScroll();
      clearTimeout(scrollTimer);scrollTimer=setTimeout(()=>{trimScroll();publish();},180);
    },{passive:true});
  }
  async function extendScroll() {
    if(!active||scrollLoading||active.desc.final||paged||config.fixed)return;
    scrollLoading=true;
    try {
      const view=active,parts=await sections(view.desc.chapter),index=view.desc.end;
      if(view!==active||index>=parts.length)return;
      const holder=view.doc.createElement('section');holder.dataset.readerPiece=String(index);holder.innerHTML=parts[index].html;
      view.flow.append(holder);view.desc.end++;view.desc.final=view.desc.end>=parts.length;
      view.spans=[...view.flow.querySelectorAll('[data-reader-text-offset]')];
      trimScroll();highlight();
    } catch (_) { /* A chapter that cannot be fetched must not break scrolling. */ }
    finally { scrollLoading=false; }
  }
  function trimScroll() {
    // Release both ends only at rest, preserving the visible passage and visual Y.
    if(active&&!touch&&!animation&&!pageMode){
      const view=active;
      const root=view.doc.scrollingElement;
      const retained=view.spans.find(el=>nodeRect(el).some(r=>r.bottom>24));
      const before=retained?.getBoundingClientRect().top;
      let removed=0;
      for(const node of [...view.flow.children]) {
        const box=node.getBoundingClientRect();
        if(!node.hasAttribute('data-reader-piece')||box.bottom>=-2*h)break;
        removed+=box.height;node.remove();
        view.desc.first++;
      }
      if(removed){if(retained)root.scrollTop+=retained.getBoundingClientRect().top-before;view.spans=[...view.flow.querySelectorAll('[data-reader-text-offset]')];view.desc.start=Number(view.spans[0]?.dataset.readerTextOffset||0);}
      for(const node of [...view.flow.children].reverse()) {
        if(!node.hasAttribute('data-reader-piece')||node.getBoundingClientRect().top<=4*h)break;
        node.remove();view.desc.end--;view.desc.final=false;
      }
      view.spans=[...view.flow.querySelectorAll('[data-reader-text-offset]')];
      view.desc.html=view.flow.innerHTML;view.targets.clear();
    }
  }
  async function prependScroll() {
    if(!active||scrollLoading||active.desc.first<=0||paged||config.fixed)return;
    scrollLoading=true;
    try {
      const view=active,index=view.desc.first-1,parts=await sections(view.desc.chapter),root=view.doc.scrollingElement;
      if(view!==active||index<0||index>=parts.length)return;
      const retained=view.flow.firstElementChild,before=retained?.getBoundingClientRect().top;
      const holder=view.doc.createElement('section');holder.dataset.readerPiece=String(index);holder.innerHTML=parts[index].html;
      view.flow.prepend(holder);view.desc.first=index;view.desc.start=parts[index].start;
      if(retained)root.scrollTop+=retained.getBoundingClientRect().top-before;
      view.spans=[...view.flow.querySelectorAll('[data-reader-text-offset]')];view.desc.html=view.flow.innerHTML;view.targets.clear();highlight();
    } catch (_) { /* A chapter that cannot be fetched must not break scrolling. */ }
    finally { scrollLoading=false; }
  }
  function highlight() {
    if(!active)return;
    const win=active.frame.contentWindow,spans=active.spans;
    for(const span of spans)span.style.backgroundColor='';
    if(!win.CSS?.highlights||!win.Highlight){for(const span of spans)span.style.backgroundColor=query&&span.textContent.toLowerCase().includes(query.toLowerCase())?'#3478f644':'';return;}
    win.CSS.highlights.delete('reader-search');if(!query)return;
    const text=spans.map(span=>span.textContent).join('').toLowerCase(),needle=query.toLowerCase(),ranges=[];
    const starts=[];let offset=0;for(const span of spans){starts.push(offset);offset+=span.textContent.length;}
    for(let at=text.indexOf(needle);at>=0&&ranges.length<200;at=text.indexOf(needle,at+needle.length)){
      const start=starts.findLastIndex(value=>value<=at),end=starts.findLastIndex(value=>value<at+needle.length);
      if(start<0||end<0||spans[start].firstChild?.nodeType!==Node.TEXT_NODE||spans[end].firstChild?.nodeType!==Node.TEXT_NODE)continue;
      const range=active.doc.createRange();range.setStart(spans[start].firstChild,at-starts[start]);range.setEnd(spans[end].firstChild,at+needle.length-starts[end]);ranges.push(range);
    }
    win.CSS.highlights.set('reader-search',new win.Highlight(...ranges));
  }
  async function goTo(chapter,offset=0,fragment='',fraction=null) {
    const token=++generation;stop();touch=null;queue=[];pending=token;preparation++;
    const frame=active?frames.find(frame=>frame!==active.frame):frames[0];
    frame.style.transform=`translateX(${w}px)`;
    try {
      const view=await load(await descriptor(chapter,offset,fragment),frame,offset,fragment);
      if(token!==generation)return;
      seek(view,offset,fragment,fraction);
      if(active){active.frame.style.transform=`translateX(${-w}px)`;active.frame.setAttribute('aria-hidden','true');slide(active,0);}
      active=view;active.frame.setAttribute('aria-hidden','false');active.frame.style.transform='translateX(0)';dragNeighbour=null;drag=0;pending=null;
      spare=null;spareTarget=null;spareDirection=0;prepare();highlight();publish();
    } catch(error) {if(token===generation){pending=null;publish('文档暂不可用，请重试');window.ReaderBridge?.failure(error.message);}}
  }
  function jump(offset,fragment='',fraction=null) {return goTo(active?.desc.chapter||0,offset,fragment,fraction);}
  function resize(width,height) {
    if(width<=48||height<=48)return;
    const retained=location();w=width;h=height;stage.style.width=w+'px';stage.style.height=h+'px';
    for(const frame of frames){frame.style.width=w+'px';frame.style.height=h+'px';}
    if(active){cancel();geometry(active);active.targets.clear();seek(active,retained.offset,retained.fragment,retained.scrollFraction);prepare();publish();}
  }
  function cancel() {generation++;pending=null;touch=null;queue=[];stop();drag=0;dragNeighbour=null;notice.style.display='none';if(active){active.frame.style.transform='translateX(0)';slide(active,0);active.viewport.scrollLeft=active.screen*w;for(const frame of frames)if(frame!==active.frame)frame.style.transform=`translateX(${w}px)`;}preparation++;nextJob=null;previousJob=null;spare=null;spareTarget=null;spareDirection=0;}
  const idle=window.requestIdleCallback||((callback)=>setTimeout(()=>callback({timeRemaining:()=>8}),30));
  // Bind the optional scheduler explicitly so older Android WebView versions remain supported.
  window.requestIdleCallback ||= idle;
  resize(w,h);goTo(config.chapter,config.offset,config.fragment,config.fraction);
  return {turn,jump,goTo,location,resize,cancel,isAnimating:()=>!!animation||!!pending,
    find:value=>{query=value;highlight();},debug:()=>({windows:descriptors.size,chapters:parsed.size,nodes:active?.flow.querySelectorAll('*').length||0})};
})();
