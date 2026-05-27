/**
 * SequenceDiagramRenderer
 *
 * Consumes a schema.json and renders a UML-style sequence diagram into a
 * target DOM element as an inline SVG.
 *
 * Two-pass approach:
 *   1. Layout pass  — walks messages/fragments, tracks Y positions and
 *                     open activation bars, collects render items.
 *   2. Render pass  — draws SVG elements from collected items, fragments
 *                     drawn in reverse order (outermost first) so inner
 *                     fragments appear in front.
 */
class SequenceDiagramRenderer {

  // ─── Construction ───────────────────────────────────────────────────────────

  constructor(schema) {
    this.schema = schema;

    // Layout constants (px)
    this.COL_SPACING   = 190;  // center-to-center distance between participants
    this.PADDING_X     = 55;   // left edge to first participant center
    this.PADDING_TOP   = 24;
    this.PARTICIPANT_W = 152;
    this.PARTICIPANT_H = 40;
    this.ROW_H         = 54;   // vertical space per message
    this.SELF_EXTRA    = 26;   // extra height for self-loop messages
    this.ACT_W         = 10;   // activation bar width
    this.ACT_STACK_OFF = 5;    // x offset per nested activation depth
    this.FRAG_PAD_X    = 10;   // fragment box x inset from svg edges
    this.FRAG_HDR_H    = 22;   // height of the type-tab header row
    this.FRAG_GAP      = 10;   // vertical padding before/after fragment content

    // Participant column centers: { id → x }
    this.colX = {};
    schema.participants.forEach((p, i) => {
      this.colX[p.id] = this.PADDING_X + i * this.COL_SPACING;
    });

    // Layout state (reset before each layout pass)
    this._y         = 0;
    this._seqNum    = 0;
    this._openActs  = {};   // id → [{ startY, depth }]
    this._actSegs   = [];   // completed: { id, startY, endY, depth }
    this._items     = [];   // render items: { kind:'message'|'fragment', ... }

    schema.participants.forEach(p => (this._openActs[p.id] = []));
  }

  // ─── Public API ─────────────────────────────────────────────────────────────

  /**
   * Run both passes and append the SVG to `container`.
   * @param {HTMLElement} container
   */
  render(container) {
    const totalH = this._layout();
    const totalW = this.PADDING_X * 2 + (this.schema.participants.length - 1) * this.COL_SPACING;

    const svg = this._mkSVG(totalW, totalH);

    // Layer order: fragments → lifelines → activation bars → messages → participants
    const gFrags        = this._g();
    const gLifelines    = this._g();
    const gActs         = this._g();
    const gMsgs         = this._g();
    const gParticipants = this._g();
    [gFrags, gLifelines, gActs, gMsgs, gParticipants].forEach(g => svg.appendChild(g));

    const lifelineTop = this.PADDING_TOP + this.PARTICIPANT_H;

    // ── Lifelines ──────────────────────────────────────────────────────────────
    this.schema.participants.forEach(p => {
      gLifelines.appendChild(this._el('line', {
        class: 'lifeline',
        x1: this.colX[p.id], y1: lifelineTop,
        x2: this.colX[p.id], y2: totalH - 12,
      }));
    });

    // ── Participant header boxes ───────────────────────────────────────────────
    this.schema.participants.forEach(p => {
      const cx = this.colX[p.id];
      const ty = this.PADDING_TOP;
      const g  = this._g();
      g.appendChild(this._el('rect', {
        class: 'participant-box',
        x: cx - this.PARTICIPANT_W / 2,
        y: ty,
        width:  this.PARTICIPANT_W,
        height: this.PARTICIPANT_H,
        rx: 3,
      }));
      const t = this._el('text', {
        class: 'participant-text',
        x: cx,
        y: ty + this.PARTICIPANT_H / 2,
        'text-anchor': 'middle',
        'dominant-baseline': 'central',
      });
      t.textContent = p.label;
      g.appendChild(t);
      gParticipants.appendChild(g);
    });

    // ── Activation bars ────────────────────────────────────────────────────────
    this._actSegs.forEach(seg => {
      const baseX = this.colX[seg.id] - this.ACT_W / 2;
      const x     = baseX + (seg.depth - 1) * this.ACT_STACK_OFF;
      const h     = Math.max(seg.endY - seg.startY, 6);
      gActs.appendChild(this._el('rect', {
        class: 'activation-bar',
        x, y: seg.startY,
        width: this.ACT_W, height: h,
        rx: 2,
      }));
    });

    // ── Fragments (outermost first so inner appears on top) ────────────────────
    const fragItems = this._items.filter(i => i.kind === 'fragment').reverse();
    fragItems.forEach(item => this._drawFragment(gFrags, item, totalW));

    // ── Messages ───────────────────────────────────────────────────────────────
    this._items.filter(i => i.kind === 'message').forEach(item => {
      this._drawMessage(gMsgs, item.msg, item.y, item.seq);
    });

    container.appendChild(svg);
  }

  // ─── Layout pass ─────────────────────────────────────────────────────────────

  _layout() {
    this._y      = this.PADDING_TOP + this.PARTICIPANT_H + 30;
    this._seqNum = 0;
    this._walkMessages(this.schema.messages);

    // Close any activations still open at the bottom of the diagram
    for (const [id, stack] of Object.entries(this._openActs)) {
      while (stack.length) {
        const { startY, depth } = stack.pop();
        this._actSegs.push({ id, startY, endY: this._y, depth });
      }
    }

    return this._y + 28;
  }

  _walkMessages(messages) {
    for (const item of messages) {
      if (item.fragment) {
        this._walkFragment(item.fragment);
      } else {
        this._walkMessage(item);
      }
    }
  }

  _walkMessage(msg) {
    // Activate the named participant — bar starts at this arrow's Y
    if (msg.activate) {
      const id    = msg.activate;
      const depth = this._openActs[id].length + 1;
      this._openActs[id].push({ startY: this._y, depth });
    }

    const isSelf = msg.from === msg.to;
    this._seqNum += 1;
    this._items.push({ kind: 'message', msg, y: this._y, seq: this._seqNum });
    this._y += this.ROW_H + (isSelf ? this.SELF_EXTRA : 0);

    // Deactivate — bar ends partway into the gap below the return arrow
    if (msg.deactivate) {
      const id    = msg.deactivate;
      const stack = this._openActs[id];
      if (stack.length) {
        const { startY, depth } = stack.pop();
        this._actSegs.push({ id, startY, endY: this._y - this.ROW_H * 0.45, depth });
      }
    }
  }

  _walkFragment(frag) {
    const startY        = this._y;
    const branchMarkers = [];  // { y, condition, isFirst }

    this._y += this.FRAG_HDR_H + this.FRAG_GAP;

    if (frag.type === 'alt') {
      // First branch — record marker at the top of the content area
      branchMarkers.push({
        y: startY + this.FRAG_HDR_H,
        condition: frag.branches[0]?.condition,
        isFirst: true,
      });

      frag.branches.forEach((branch, i) => {
        if (i > 0) {
          // Subsequent branches — divider line then content
          branchMarkers.push({ y: this._y, condition: branch.condition, isFirst: false });
          this._y += this.FRAG_GAP + 4;
        }
        this._walkMessages(branch.messages);
        this._y += this.FRAG_GAP;
      });
    } else {
      // loop / opt / par / ref — single message list
      this._walkMessages(frag.messages);
      this._y += this.FRAG_GAP;
    }

    // Item is pushed AFTER content so outer fragments appear later in the list
    // (they will be reversed before drawing so outer is drawn first/behind)
    this._items.push({ kind: 'fragment', frag, startY, endY: this._y, branchMarkers });
  }

  // ─── Draw: fragment ──────────────────────────────────────────────────────────

  _drawFragment(parent, item, totalW) {
    const { frag, startY, endY, branchMarkers } = item;

    const x = this.FRAG_PAD_X;
    const w = totalW - this.FRAG_PAD_X * 2;
    const h = endY - startY;
    const g = this._g();

    // Background rectangle
    g.appendChild(this._el('rect', {
      class: 'fragment-box',
      x, y: startY, width: w, height: h, rx: 2,
    }));

    // Pentagon type-tab
    const typeStr = frag.type.toUpperCase();
    const tabW    = typeStr.length * 7 + 18;
    const tabH    = this.FRAG_HDR_H;
    g.appendChild(this._el('path', {
      class: 'fragment-tab',
      d: `M${x},${startY} h${tabW} l8,${tabH / 2} l-8,${tabH / 2} H${x} Z`,
    }));

    const typeText = this._el('text', {
      class: 'fragment-type-text',
      x: x + tabW / 2, y: startY + tabH / 2,
      'text-anchor': 'middle', 'dominant-baseline': 'central',
    });
    typeText.textContent = typeStr;
    g.appendChild(typeText);

    // Optional fragment label (e.g. "[for each bank account]")
    if (frag.label) {
      const lt = this._el('text', {
        class: 'fragment-label-text',
        x: x + tabW + 12, y: startY + tabH / 2,
        'dominant-baseline': 'central',
      });
      lt.textContent = `[${frag.label}]`;
      g.appendChild(lt);
    }

    // Branch markers (dividers + condition labels)
    branchMarkers.forEach(marker => {
      if (!marker.isFirst) {
        // Dashed horizontal divider
        g.appendChild(this._el('line', {
          class: 'fragment-divider',
          x1: x, y1: marker.y, x2: x + w, y2: marker.y,
        }));
      }
      if (marker.condition) {
        const condY = marker.isFirst
          ? startY + tabH + 12      // just below the header tab
          : marker.y + 13;          // just below the divider line
        const ct = this._el('text', {
          class: 'fragment-condition-text',
          x: x + 8, y: condY,
        });
        ct.textContent = `[${marker.condition}]`;
        g.appendChild(ct);
      }
    });

    parent.appendChild(g);
  }

  // ─── Draw: message ───────────────────────────────────────────────────────────

  _drawMessage(parent, msg, y, seq) {
    const isSelf = msg.from === msg.to;
    const g      = this._g();

    // Sequence number in left margin
    const seqX = this.FRAG_PAD_X + 4;
    const seqT = this._el('text', {
      class: 'seq-num',
      x: seqX, y,
      'dominant-baseline': 'central',
    });
    seqT.textContent = String(seq);
    g.appendChild(seqT);

    if (isSelf) {
      // ── Self-loop (goes right, loops back) ──────────────────────────────────
      const cx      = this.colX[msg.from];
      const loopW   = 50;
      const loopH   = this.SELF_EXTRA + Math.round(this.ROW_H * 0.35);
      const startX  = cx + this.ACT_W / 2;
      const isDash  = msg.type === 'return';

      g.appendChild(this._el('path', {
        class: `arrow-line${isDash ? ' dashed' : ''}`,
        d: `M${startX},${y} H${startX + loopW} V${y + loopH} H${startX + 1}`,
        fill: 'none',
        'marker-end': 'url(#arrow-open)',
      }));

      if (msg.label) {
        const t = this._el('text', {
          class: 'message-label',
          x: startX + loopW + 6, y: y + loopH / 2,
          'dominant-baseline': 'central',
        });
        t.textContent = msg.label;
        g.appendChild(t);
      }

    } else {
      // ── Horizontal arrow between participants ────────────────────────────────
      const fx       = this.colX[msg.from];
      const tx       = this.colX[msg.to];
      const goRight  = tx > fx;
      const halfAct  = this.ACT_W / 2;
      const x1       = fx + (goRight ?  halfAct : -halfAct);
      const x2       = tx + (goRight ? -halfAct :  halfAct);

      const isDash   = msg.type === 'return';
      const arrowId  = msg.type === 'sync' ? 'arrow-filled' : 'arrow-open';

      g.appendChild(this._el('line', {
        class: `arrow-line${isDash ? ' dashed' : ''}`,
        x1, y1: y, x2, y2: y,
        'marker-end': `url(#${arrowId})`,
      }));

      if (msg.label) {
        const midX = (x1 + x2) / 2;
        const t    = this._el('text', {
          class: 'message-label',
          x: midX, y: y - 7,
          'text-anchor': 'middle',
        });
        t.textContent = msg.label;
        g.appendChild(t);
      }
    }

    parent.appendChild(g);
  }

  // ─── SVG helpers ─────────────────────────────────────────────────────────────

  _mkSVG(w, h) {
    const svg = this._el('svg', {
      class: 'sequence-diagram',
      width: w, height: h,
      viewBox: `0 0 ${w} ${h}`,
    });

    // Arrow markers — colors inherit via CSS var(--clr-arrow) painted on the line
    const defs = this._el('defs');
    defs.innerHTML = /* html */`
      <marker id="arrow-filled"
        viewBox="0 0 10 10" refX="9" refY="5"
        markerWidth="7" markerHeight="7" orient="auto">
        <path d="M0 0L10 5L0 10Z" fill="var(--clr-arrow)"/>
      </marker>
      <marker id="arrow-open"
        viewBox="0 0 12 10" refX="10" refY="5"
        markerWidth="8" markerHeight="8" orient="auto">
        <path d="M1 1L10 5L1 9"
          fill="none" stroke="var(--clr-arrow)"
          stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
      </marker>
    `;
    svg.appendChild(defs);
    return svg;
  }

  /** Create an SVG element with given attributes. */
  _el(tag, attrs = {}) {
    const e = document.createElementNS('http://www.w3.org/2000/svg', tag);
    for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
    return e;
  }

  /** Shorthand for a plain <g> element. */
  _g(attrs = {}) { return this._el('g', attrs); }
}

// ─── Bootstrap ───────────────────────────────────────────────────────────────

async function init() {
  const container = document.getElementById('diagram-container');

  try {
    // When opened via file://, fetch() is blocked by the browser.
    // schema.js sets window.DIAGRAM_SCHEMA as a plain global — use that first.
    // When served over HTTP (local dev server, etc.) fetch() also works fine.
    let schema;

    if (window.DIAGRAM_SCHEMA) {
      schema = window.DIAGRAM_SCHEMA;
    } else {
      const res = await fetch('schema.json');
      if (!res.ok) throw new Error(`HTTP ${res.status} — could not load schema.json`);
      schema = await res.json();
    }

    document.getElementById('diagram-title').textContent = schema.title ?? 'Sequence Diagram';

    const renderer = new SequenceDiagramRenderer(schema);
    renderer.render(container);

  } catch (err) {
    container.innerHTML = `<p class="error-msg">⚠ ${err.message}</p>`;
    console.error(err);
  }
}

document.addEventListener('DOMContentLoaded', init);