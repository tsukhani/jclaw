/**
 * <MacOSWindow> — macOS window chrome with traffic lights.
 *
 * Usage:
 *   <MacOSWindow title="My App" width={1200} height={800}>
 *     <YourContent />
 *   </MacOSWindow>
 */

const macOSWindowStyles = {
  outer: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: '100vh',
    background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
    padding: 40,
    fontFamily: '-apple-system, BlinkMacSystemFont, "SF Pro Text", sans-serif',
  },
  window: {
    background: 'white',
    borderRadius: 12,
    overflow: 'hidden',
    boxShadow: '0 30px 80px -20px rgba(0,0,0,0.3), 0 0 0 1px rgba(0,0,0,0.08)',
  },
  titleBar: {
    height: 36,
    background: 'linear-gradient(to bottom, #ebebeb, #d5d5d5)',
    borderBottom: '1px solid rgba(0,0,0,0.12)',
    display: 'flex',
    alignItems: 'center',
    padding: '0 12px',
    gap: 8,
    position: 'relative',
  },
  trafficLight: {
    width: 12,
    height: 12,
    borderRadius: 999,
  },
  title: {
    position: 'absolute',
    left: '50%',
    top: '50%',
    transform: 'translate(-50%, -50%)',
    fontSize: 13,
    fontWeight: 500,
    color: '#444',
    pointerEvents: 'none',
  },
  content: {
    position: 'relative',
    overflow: 'auto',
  },
};

function MacOSWindow({ title, children, width = 1200, height = 800, background }) {
  return (
    <div style={macOSWindowStyles.outer}>
      <div style={{ ...macOSWindowStyles.window, width }}>
        <div style={macOSWindowStyles.titleBar}>
          <div style={{ ...macOSWindowStyles.trafficLight, background: '#ff5f57', border: '0.5px solid #e0443e' }} />
          <div style={{ ...macOSWindowStyles.trafficLight, background: '#febc2e', border: '0.5px solid #dea123' }} />
          <div style={{ ...macOSWindowStyles.trafficLight, background: '#28c840', border: '0.5px solid #1aab29' }} />
          {title && <div style={macOSWindowStyles.title}>{title}</div>}
        </div>
        <div style={{ ...macOSWindowStyles.content, height, background: background || 'white' }}>
          {children}
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { MacOSWindow });
