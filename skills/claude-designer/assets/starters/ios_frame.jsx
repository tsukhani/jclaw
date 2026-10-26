/**
 * <IOSFrame> — iOS device bezel with status bar and home indicator.
 *
 * Usage:
 *   <IOSFrame>
 *     <YourAppScreen />
 *   </IOSFrame>
 *
 * Optional props:
 *   time="9:41"        - status bar time (default 9:41)
 *   battery={80}       - battery percentage 0-100
 *   dark               - use white status bar icons (for dark app backgrounds)
 *   width={390}        - logical px width (iPhone 14 default)
 *   height={844}       - logical px height
 */

const iosFrameStyles = {
  outer: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: '100vh',
    background: '#f5f5f7',
    padding: 40,
    fontFamily: '-apple-system, BlinkMacSystemFont, "SF Pro Display", "SF Pro Text", sans-serif',
  },
  bezel: {
    position: 'relative',
    background: '#1c1c1e',
    borderRadius: 56,
    padding: 12,
    boxShadow: '0 30px 60px -15px rgba(0,0,0,0.25), 0 0 0 1px rgba(0,0,0,0.1)',
  },
  screen: {
    position: 'relative',
    borderRadius: 44,
    overflow: 'hidden',
    background: 'white',
  },
  statusBar: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    height: 54,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: '0 32px 0 32px',
    fontSize: 17,
    fontWeight: 600,
    letterSpacing: '-0.02em',
    zIndex: 10,
    pointerEvents: 'none',
  },
  dynamicIsland: {
    position: 'absolute',
    top: 11,
    left: '50%',
    transform: 'translateX(-50%)',
    width: 126,
    height: 37,
    background: '#000',
    borderRadius: 999,
    zIndex: 20,
  },
  homeIndicator: {
    position: 'absolute',
    bottom: 8,
    left: '50%',
    transform: 'translateX(-50%)',
    width: 134,
    height: 5,
    borderRadius: 3,
    background: 'rgba(0,0,0,0.85)',
    zIndex: 20,
  },
  content: {
    position: 'absolute',
    inset: 0,
    overflow: 'auto',
  },
};

function IOSFrame({
  children,
  time = '9:41',
  battery = 80,
  dark = false,
  width = 390,
  height = 844,
  background,
}) {
  const fg = dark ? 'white' : 'black';
  const screenStyle = {
    ...iosFrameStyles.screen,
    width,
    height,
    background: background || 'white',
  };
  const statusStyle = {
    ...iosFrameStyles.statusBar,
    color: fg,
  };
  const homeStyle = {
    ...iosFrameStyles.homeIndicator,
    background: dark ? 'rgba(255,255,255,0.85)' : 'rgba(0,0,0,0.85)',
  };
  return (
    <div style={iosFrameStyles.outer}>
      <div style={iosFrameStyles.bezel}>
        <div style={screenStyle}>
          <div style={iosFrameStyles.dynamicIsland} />
          <div style={statusStyle}>
            <span>{time}</span>
            <span style={{ display: 'flex', gap: 6, alignItems: 'center', fontSize: 14 }}>
              <span>●●●</span>
              <span>􀙇</span>
              <span>{battery}%</span>
            </span>
          </div>
          <div style={iosFrameStyles.content}>{children}</div>
          <div style={homeStyle} />
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { IOSFrame });
