import type { TrackPoint } from "./types";
import { haversineM } from "./stats";

const SVG_NS = "http://www.w3.org/2000/svg";

const LINE_COLOR = "#2d5a3d";
const AREA_OPACITY = "0.18";
const LABEL_COLOR = "#7a8577";
const GRID_COLOR = "#e3e1d9";
const PANEL_COLOR = "#ffffff";

const FALLBACK_WIDTH = 800;
const FALLBACK_HEIGHT = 150;
const PAD_TOP = 12;
const PAD_RIGHT = 14;
const PAD_BOTTOM = 20;
const PAD_LEFT = 46;

const MAX_DRAW_POINTS = 1000;
const GRID_INTERVALS = 3;
const MAX_GRID_LINES = 5;
const NICE_STEPS = [1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000, 2000, 5000];

const LABEL_FONT_SIZE = 10;
const HOVER_FONT_SIZE = 11;
const HOVER_CHAR_WIDTH = 6.2;

/** Ein Punkt mit Höhe, samt Index in der originalen Punkteliste. */
interface Sample {
  index: number;
  km: number;
  ele: number;
  /** Relative X-Position 0…1 */
  pos: number;
}

interface Instance {
  destroy(): void;
}

const instances = new WeakMap<HTMLElement, Instance>();

function svgEl<K extends keyof SVGElementTagNameMap>(
  name: K,
  attrs: Record<string, string>
): SVGElementTagNameMap[K] {
  const el = document.createElementNS(SVG_NS, name);
  for (const [key, value] of Object.entries(attrs)) {
    el.setAttribute(key, value);
  }
  return el;
}

function cumulativeKm(points: TrackPoint[]): number[] {
  const cum = new Array<number>(points.length);
  let metres = 0;
  cum[0] = 0;

  for (let i = 1; i < points.length; i++) {
    metres += haversineM(points[i - 1], points[i]);
    cum[i] = metres / 1000;
  }

  return cum;
}

function collectSamples(points: TrackPoint[]): Sample[] {
  if (points.length === 0) {
    return [];
  }

  const cum = cumulativeKm(points);
  const raw: Sample[] = [];

  for (let i = 0; i < points.length; i++) {
    const ele = points[i].ele;
    if (ele !== undefined && Number.isFinite(ele)) {
      raw.push({ index: i, km: cum[i], ele, pos: 0 });
    }
  }

  if (raw.length < 2) {
    return raw;
  }

  const first = raw[0].km;
  const span = raw[raw.length - 1].km - first;

  for (let i = 0; i < raw.length; i++) {
    raw[i].pos = span > 0 ? (raw[i].km - first) / span : i / (raw.length - 1);
  }

  return raw;
}

function downsample(samples: Sample[]): Sample[] {
  if (samples.length <= MAX_DRAW_POINTS) {
    return samples;
  }

  const out: Sample[] = [];
  const stride = (samples.length - 1) / (MAX_DRAW_POINTS - 1);

  for (let i = 0; i < MAX_DRAW_POINTS; i++) {
    out.push(samples[Math.round(i * stride)]);
  }

  return out;
}

function niceStep(range: number, intervals: number): number {
  const raw = range / intervals;
  for (const step of NICE_STEPS) {
    if (step >= raw) {
      return step;
    }
  }
  return NICE_STEPS[NICE_STEPS.length - 1];
}

function eleRange(samples: Sample[]): { min: number; max: number; step: number } {
  let min = Infinity;
  let max = -Infinity;

  for (const sample of samples) {
    if (sample.ele < min) {
      min = sample.ele;
    }
    if (sample.ele > max) {
      max = sample.ele;
    }
  }

  const spread = Math.max(max - min, 1);
  const padding = spread * 0.1;
  let step = niceStep(spread + 2 * padding, GRID_INTERVALS);
  let low = Math.floor((min - padding) / step) * step;
  let high = Math.ceil((max + padding) / step) * step;

  while ((high - low) / step + 1 > MAX_GRID_LINES) {
    const next = NICE_STEPS[NICE_STEPS.indexOf(step) + 1];
    if (next === undefined) {
      break;
    }
    step = next;
    low = Math.floor((min - padding) / step) * step;
    high = Math.ceil((max + padding) / step) * step;
  }

  if (high <= low) {
    high = low + step;
  }

  return { min: low, max: high, step };
}

/** Index des Samples, dessen `pos` am nächsten an `pos` liegt (binäre Suche). */
function nearestSample(samples: Sample[], pos: number): number {
  let low = 0;
  let high = samples.length - 1;

  while (low < high) {
    const mid = (low + high) >> 1;
    if (samples[mid].pos < pos) {
      low = mid + 1;
    } else {
      high = mid;
    }
  }

  if (low > 0 && Math.abs(samples[low - 1].pos - pos) <= Math.abs(samples[low].pos - pos)) {
    return low - 1;
  }

  return low;
}

function textWidth(el: SVGTextElement, text: string): number {
  try {
    const measured = el.getComputedTextLength();
    if (measured > 0) {
      return measured;
    }
  } catch {
    /* Nicht gerendert – Schätzung genügt. */
  }
  return text.length * HOVER_CHAR_WIDTH;
}

/**
 * Zeichnet das Höhenprofil in den Container. Gibt `false` zurück, wenn
 * weniger als zwei Punkte eine Höhe besitzen (Panel dann ausblenden).
 */
export function renderProfile(
  container: HTMLElement,
  points: TrackPoint[],
  onHover?: (index: number | null) => void
): boolean {
  clearProfile(container);

  const samples = collectSamples(points);
  if (samples.length < 2) {
    return false;
  }

  const drawn = downsample(samples);
  const { min: eleMin, max: eleMax, step: eleStep } = eleRange(samples);
  const startKm = samples[0].km;
  const kmSpan = samples[samples.length - 1].km - startKm;

  const svg = svgEl("svg", {
    width: "100%",
    height: "100%",
    preserveAspectRatio: "none",
    "aria-hidden": "true",
  });
  svg.style.touchAction = "none";
  container.append(svg);

  let width = 0;
  let height = 0;
  let plotLeft = 0;
  let plotWidth = 0;
  let guide: SVGGElement | null = null;
  let guideLine: SVGLineElement | null = null;
  let guideDot: SVGCircleElement | null = null;
  let guideBox: SVGRectElement | null = null;
  let guideText: SVGTextElement | null = null;
  let hovered: number | null = null;

  function xOf(pos: number): number {
    return plotLeft + pos * plotWidth;
  }

  function yOf(ele: number): number {
    const plotTop = PAD_TOP;
    const plotHeight = Math.max(height - PAD_TOP - PAD_BOTTOM, 1);
    return plotTop + (1 - (ele - eleMin) / (eleMax - eleMin)) * plotHeight;
  }

  function draw(): void {
    svg.replaceChildren();
    svg.setAttribute("viewBox", `0 0 ${width} ${height}`);

    plotLeft = PAD_LEFT;
    plotWidth = Math.max(width - PAD_LEFT - PAD_RIGHT, 1);
    const baseY = height - PAD_BOTTOM;

    for (let value = eleMin; value <= eleMax + 0.001; value += eleStep) {
      const y = yOf(value);
      svg.append(
        svgEl("line", {
          x1: String(plotLeft),
          y1: y.toFixed(2),
          x2: String(plotLeft + plotWidth),
          y2: y.toFixed(2),
          stroke: GRID_COLOR,
          "stroke-width": "1",
          "shape-rendering": "crispEdges",
        })
      );

      const label = svgEl("text", {
        x: String(plotLeft - 6),
        y: y.toFixed(2),
        "text-anchor": "end",
        "dominant-baseline": "middle",
        "font-size": String(LABEL_FONT_SIZE),
        fill: LABEL_COLOR,
      });
      label.textContent = `${Math.round(value)} m`;
      svg.append(label);
    }

    const coords = drawn.map((sample) => `${xOf(sample.pos).toFixed(2)},${yOf(sample.ele).toFixed(2)}`);
    const line = `M${coords.join("L")}`;
    const firstX = xOf(drawn[0].pos).toFixed(2);
    const lastX = xOf(drawn[drawn.length - 1].pos).toFixed(2);

    svg.append(
      svgEl("path", {
        d: `${line}L${lastX},${baseY.toFixed(2)}L${firstX},${baseY.toFixed(2)}Z`,
        fill: LINE_COLOR,
        "fill-opacity": AREA_OPACITY,
        stroke: "none",
      })
    );

    svg.append(
      svgEl("path", {
        d: line,
        fill: "none",
        stroke: LINE_COLOR,
        "stroke-width": "2",
        "stroke-linejoin": "round",
        "stroke-linecap": "round",
        "vector-effect": "non-scaling-stroke",
      })
    );

    const marks: { pos: number; anchor: string }[] = [
      { pos: 0, anchor: "start" },
      { pos: 0.5, anchor: "middle" },
      { pos: 1, anchor: "end" },
    ];

    for (const mark of marks) {
      const label = svgEl("text", {
        x: xOf(mark.pos).toFixed(2),
        y: String(height - 6),
        "text-anchor": mark.anchor,
        "font-size": String(LABEL_FONT_SIZE),
        fill: LABEL_COLOR,
      });
      label.textContent = `${(startKm + mark.pos * kmSpan).toFixed(1)} km`;
      svg.append(label);
    }

    guide = svgEl("g", { display: "none" });
    guideLine = svgEl("line", {
      y1: String(PAD_TOP),
      y2: baseY.toFixed(2),
      stroke: LINE_COLOR,
      "stroke-width": "1",
      "stroke-dasharray": "3 3",
      "stroke-opacity": "0.6",
    });
    guideDot = svgEl("circle", {
      r: "3.5",
      fill: LINE_COLOR,
      stroke: PANEL_COLOR,
      "stroke-width": "1.5",
    });
    guideBox = svgEl("rect", {
      rx: "4",
      ry: "4",
      height: "16",
      fill: PANEL_COLOR,
      "fill-opacity": "0.9",
      stroke: GRID_COLOR,
      "stroke-width": "1",
    });
    guideText = svgEl("text", {
      "font-size": String(HOVER_FONT_SIZE),
      "dominant-baseline": "middle",
      fill: LINE_COLOR,
    });

    guide.append(guideLine, guideDot, guideBox, guideText);
    svg.append(guide);
  }

  function hideGuide(): void {
    guide?.setAttribute("display", "none");
  }

  function showGuide(sample: Sample): void {
    if (!guide || !guideLine || !guideDot || !guideBox || !guideText) {
      return;
    }

    const x = xOf(sample.pos);
    const y = yOf(sample.ele);

    guideLine.setAttribute("x1", x.toFixed(2));
    guideLine.setAttribute("x2", x.toFixed(2));
    guideDot.setAttribute("cx", x.toFixed(2));
    guideDot.setAttribute("cy", y.toFixed(2));

    const text = `${sample.km.toFixed(1)} km · ${Math.round(sample.ele)} m`;
    guideText.textContent = text;

    const boxY = PAD_TOP;
    const measured = textWidth(guideText, text);
    const flip = x + 8 + measured + 8 > width - PAD_RIGHT;
    const textX = flip ? x - 8 : x + 8;

    guideText.setAttribute("x", textX.toFixed(2));
    guideText.setAttribute("y", String(boxY + 8));
    guideText.setAttribute("text-anchor", flip ? "end" : "start");

    guideBox.setAttribute("x", (flip ? textX - measured - 4 : textX - 4).toFixed(2));
    guideBox.setAttribute("y", String(boxY));
    guideBox.setAttribute("width", (measured + 8).toFixed(2));

    guide.removeAttribute("display");
  }

  function report(index: number | null): void {
    if (index === hovered) {
      return;
    }
    hovered = index;
    onHover?.(index);
  }

  function handlePointer(event: PointerEvent): void {
    const rect = svg.getBoundingClientRect();
    if (rect.width <= 0) {
      return;
    }

    const x = ((event.clientX - rect.left) / rect.width) * width;
    const pos = Math.min(1, Math.max(0, (x - plotLeft) / plotWidth));
    const sample = samples[nearestSample(samples, pos)];

    showGuide(sample);
    report(sample.index);
  }

  function handleLeave(): void {
    hideGuide();
    report(null);
  }

  function resize(): void {
    const nextWidth = Math.round(container.clientWidth) || FALLBACK_WIDTH;
    const nextHeight = Math.round(container.clientHeight) || FALLBACK_HEIGHT;

    if (nextWidth === width && nextHeight === height) {
      return;
    }

    width = nextWidth;
    height = nextHeight;
    hovered = null;
    draw();
  }

  resize();

  svg.addEventListener("pointermove", handlePointer);
  svg.addEventListener("pointerdown", handlePointer);
  svg.addEventListener("pointerleave", handleLeave);
  svg.addEventListener("pointercancel", handleLeave);

  const observer = new ResizeObserver(resize);
  observer.observe(container);

  instances.set(container, {
    destroy(): void {
      observer.disconnect();
      svg.removeEventListener("pointermove", handlePointer);
      svg.removeEventListener("pointerdown", handlePointer);
      svg.removeEventListener("pointerleave", handleLeave);
      svg.removeEventListener("pointercancel", handleLeave);
      container.replaceChildren();
    },
  });

  return true;
}

/** Entfernt Profil und Listener aus dem Container. */
export function clearProfile(container: HTMLElement): void {
  const instance = instances.get(container);

  if (instance) {
    instances.delete(container);
    instance.destroy();
    return;
  }

  container.replaceChildren();
}
