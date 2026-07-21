/**
 * <Canvas> + <Option> — Design canvas for presenting 2+ static variations side-by-side.
 *
 * Usage (inside a Babel script):
 *   <Canvas title="Button treatments">
 *     <Option label="Solid">...</Option>
 *     <Option label="Outline">...</Option>
 *     <Option label="Ghost">...</Option>
 *   </Canvas>
 *
 * Exports Canvas and Option to window so other Babel files can use them.
 */

const canvasStyles = {
  root: {
    minHeight: '100vh',
    background: '#fafaf9',
    padding: '48px 40px 80px',
    fontFamily: "ui-sans-serif, system-ui, -apple-system, 'Segoe UI', sans-serif",
    color: '#1a1a1a',
  },
  header: {
    marginBottom: 40,
    maxWidth: 1200,
    margin: '0 auto 40px',
  },
  title: {
    fontSize: 28,
    fontWeight: 600,
    letterSpacing: '-0.01em',
    margin: 0,
    marginBottom: 8,
  },
  subtitle: {
    fontSize: 14,
    color: '#666',
    margin: 0,
  },
  grid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))',
    gap: 24,
    maxWidth: 1400,
    margin: '0 auto',
  },
  cell: {
    display: 'flex',
    flexDirection: 'column',
    gap: 12,
  },
  cellLabel: {
    fontSize: 11,
    fontWeight: 600,
    letterSpacing: '0.08em',
    textTransform: 'uppercase',
    color: '#888',
  },
  cellFrame: {
    background: 'white',
    border: '1px solid #eee',
    borderRadius: 10,
    padding: 32,
    minHeight: 240,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
  },
};

function Canvas({ title, subtitle, children }) {
  return (
    <div style={canvasStyles.root}>
      {(title || subtitle) && (
        <header style={canvasStyles.header}>
          {title && <h1 style={canvasStyles.title}>{title}</h1>}
          {subtitle && <p style={canvasStyles.subtitle}>{subtitle}</p>}
        </header>
      )}
      <div style={canvasStyles.grid}>{children}</div>
    </div>
  );
}

function Option({ label, background, children }) {
  const frameStyle = background
    ? { ...canvasStyles.cellFrame, background }
    : canvasStyles.cellFrame;
  return (
    <div style={canvasStyles.cell}>
      {label && <div style={canvasStyles.cellLabel}>{label}</div>}
      <div style={frameStyle}>{children}</div>
    </div>
  );
}

Object.assign(window, { Canvas, Option });
