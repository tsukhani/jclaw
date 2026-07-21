/**
 * Animations starter — Stage + Sprite + scrubber for timeline-based motion design.
 *
 * Usage:
 *   <Stage width={1920} height={1080} duration={10}>
 *     <Sprite start={0} end={3}>
 *       <Title />
 *     </Sprite>
 *     <Sprite start={2} end={8}>
 *       <ProductShot />
 *     </Sprite>
 *   </Stage>
 *
 * Inside a Sprite, use:
 *   const { progress } = useSprite();  // 0→1 over sprite lifetime
 *   const t = useTime();                // global time in seconds
 *
 * Utilities:
 *   interpolate(t, [fromT, toT], [fromV, toV], easing?)
 *   Easing.inOut / Easing.out / Easing.in / Easing.cubic
 */

const { useState, useEffect, useRef, createContext, useContext, useMemo } = React;

const Easing = {
  linear: t => t,
  in: t => t * t,
  out: t => 1 - (1 - t) * (1 - t),
  inOut: t => (t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2),
  cubic: t => t * t * t,
  cubicOut: t => 1 - Math.pow(1 - t, 3),
};

function interpolate(t, input, output, easing = Easing.linear) {
  const [t0, t1] = input;
  const [v0, v1] = output;
  if (t <= t0) return v0;
  if (t >= t1) return v1;
  const p = easing((t - t0) / (t1 - t0));
  return v0 + (v1 - v0) * p;
}

const StageContext = createContext({ time: 0, duration: 10, width: 1920, height: 1080 });
const SpriteContext = createContext({ start: 0, end: 0, time: 0, progress: 0 });

function useTime() { return useContext(StageContext).time; }
function useSprite() { return useContext(SpriteContext); }

const stageOuterStyle = {
  position: 'fixed',
  inset: 0,
  background: '#000',
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
};
const stageCanvasWrapStyle = {
  flex: 1,
  position: 'relative',
  overflow: 'hidden',
};
const stageCanvasStyle = {
  position: 'absolute',
  top: '50%',
  left: '50%',
  transformOrigin: '0 0',
  background: '#fff',
};
const stageControlsStyle = {
  background: '#111',
  padding: '12px 20px',
  display: 'flex',
  alignItems: 'center',
  gap: 16,
  color: 'white',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 13,
};
const stageScrubberStyle = {
  flex: 1,
  appearance: 'none',
  height: 4,
  background: '#333',
  borderRadius: 999,
  outline: 'none',
};
const stagePlayButtonStyle = {
  background: 'white',
  color: 'black',
  border: 'none',
  borderRadius: 999,
  width: 32,
  height: 32,
  cursor: 'pointer',
  fontSize: 14,
};

function Stage({ width = 1920, height = 1080, duration = 10, children }) {
  const [time, setTime] = useState(0);
  const [playing, setPlaying] = useState(true);
  const [scale, setScale] = useState(1);
  const rafRef = useRef(null);
  const lastRef = useRef(performance.now());
  const wrapRef = useRef(null);

  // Playback loop
  useEffect(() => {
    function tick(now) {
      const dt = (now - lastRef.current) / 1000;
      lastRef.current = now;
      if (playing) {
        setTime(prev => {
          const next = prev + dt;
          return next >= duration ? 0 : next;
        });
      }
      rafRef.current = requestAnimationFrame(tick);
    }
    lastRef.current = performance.now();
    rafRef.current = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(rafRef.current);
  }, [playing, duration]);

  // Responsive scale
  useEffect(() => {
    function fit() {
      if (!wrapRef.current) return;
      const { clientWidth, clientHeight } = wrapRef.current;
      setScale(Math.min(clientWidth / width, clientHeight / height));
    }
    fit();
    window.addEventListener('resize', fit);
    return () => window.removeEventListener('resize', fit);
  }, [width, height]);

  const contextValue = useMemo(
    () => ({ time, duration, width, height }),
    [time, duration, width, height]
  );

  return (
    <StageContext.Provider value={contextValue}>
      <div style={stageOuterStyle}>
        <div style={stageCanvasWrapStyle} ref={wrapRef}>
          <div style={{
            ...stageCanvasStyle,
            width,
            height,
            transform: `translate(${-width / 2}px, ${-height / 2}px) scale(${scale})`,
          }}>
            {children}
          </div>
        </div>
        <div style={stageControlsStyle}>
          <button style={stagePlayButtonStyle} onClick={() => setPlaying(p => !p)}>
            {playing ? '❚❚' : '▶'}
          </button>
          <span>{time.toFixed(2)}s / {duration.toFixed(1)}s</span>
          <input
            type="range"
            min={0}
            max={duration}
            step={0.01}
            value={time}
            onChange={e => { setTime(parseFloat(e.target.value)); setPlaying(false); }}
            style={stageScrubberStyle}
          />
        </div>
      </div>
    </StageContext.Provider>
  );
}

function Sprite({ start = 0, end, children, fadeIn = 0.2, fadeOut = 0.2 }) {
  const { time } = useContext(StageContext);
  const effectiveEnd = end ?? Infinity;
  const active = time >= start && time <= effectiveEnd;
  if (!active) return null;

  const progress = (time - start) / (effectiveEnd - start);
  const opacity = interpolate(time, [start, start + fadeIn], [0, 1]) *
                  interpolate(time, [effectiveEnd - fadeOut, effectiveEnd], [1, 0]);

  const spriteValue = { start, end: effectiveEnd, time: time - start, progress };

  return (
    <SpriteContext.Provider value={spriteValue}>
      <div style={{ position: 'absolute', inset: 0, opacity }}>
        {children}
      </div>
    </SpriteContext.Provider>
  );
}

Object.assign(window, { Stage, Sprite, useTime, useSprite, Easing, interpolate });
