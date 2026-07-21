/**
 * <AndroidFrame> — Android device bezel with status bar and nav bar.
 *
 * Usage:
 *   <AndroidFrame>
 *     <YourAppScreen />
 *   </AndroidFrame>
 */

const androidFrameStyles = {
  outer: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: '100vh',
    background: '#f5f5f5',
    padding: 40,
    fontFamily: 'Roboto, "Google Sans", system-ui, sans-serif',
  },
  bezel: {
    position: 'relative',
    background: '#0a0a0a',
    borderRadius: 44,
    padding: 10,
    boxShadow: '0 30px 60px -15px rgba(0,0,0,0.3), 0 0 0 1px rgba(0,0,0,0.15)',
  },
  screen: {
    position: 'relative',
    borderRadius: 36,
    overflow: 'hidden',
    background: 'white',
  },
  statusBar: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    height: 28,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: '0 18px',
    fontSize: 13,
    fontWeight: 500,
    zIndex: 10,
    pointerEvents: 'none',
  },
  navBar: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    height: 40,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 60,
    zIndex: 20,
  },
  navDot: {
    width: 8, height: 8, borderRadius: 999, background: 'currentColor',
  },
  navSquare: {
    width: 12, height: 12, border: '2px solid currentColor', borderRadius: 2,
  },
  navTriangle: {
    width: 0, height: 0,
    borderLeft: '8px solid transparent',
    borderRight: '8px solid transparent',
    borderBottom: '12px solid currentColor',
  },
  content: {
    position: 'absolute',
    inset: '28px 0 40px 0',
    overflow: 'auto',
  },
};

function AndroidFrame({
  children,
  time = '10:30',
  dark = false,
  width = 412,
  height = 892,
  background,
}) {
  const fg = dark ? 'white' : 'black';
  const screenStyle = {
    ...androidFrameStyles.screen,
    width,
    height,
    background: background || 'white',
  };
  const statusStyle = {
    ...androidFrameStyles.statusBar,
    color: fg,
  };
  const navStyle = {
    ...androidFrameStyles.navBar,
    color: fg,
  };
  return (
    <div style={androidFrameStyles.outer}>
      <div style={androidFrameStyles.bezel}>
        <div style={screenStyle}>
          <div style={statusStyle}>
            <span>{time}</span>
            <span style={{ display: 'flex', gap: 6, alignItems: 'center', fontSize: 11 }}>
              <span>●●●</span>
              <span>Wi-Fi</span>
              <span>100%</span>
            </span>
          </div>
          <div style={androidFrameStyles.content}>{children}</div>
          <div style={navStyle}>
            <div style={androidFrameStyles.navTriangle} />
            <div style={androidFrameStyles.navDot} />
            <div style={androidFrameStyles.navSquare} />
          </div>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { AndroidFrame });
