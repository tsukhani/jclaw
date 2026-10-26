/**
 * <BrowserWindow> — Browser chrome with tab bar and URL bar.
 *
 * Usage:
 *   <BrowserWindow url="https://example.com" title="Example — a thing">
 *     <YourPage />
 *   </BrowserWindow>
 */

const browserWindowStyles = {
  outer: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: '100vh',
    background: '#f0f0f2',
    padding: 40,
    fontFamily: '-apple-system, BlinkMacSystemFont, "SF Pro Text", sans-serif',
  },
  window: {
    background: 'white',
    borderRadius: 12,
    overflow: 'hidden',
    boxShadow: '0 30px 80px -20px rgba(0,0,0,0.2), 0 0 0 1px rgba(0,0,0,0.06)',
  },
  chrome: {
    background: '#e4e4e7',
    borderBottom: '1px solid rgba(0,0,0,0.08)',
  },
  tabBar: {
    height: 40,
    display: 'flex',
    alignItems: 'flex-end',
    padding: '0 12px',
    gap: 4,
  },
  trafficLights: {
    display: 'flex',
    gap: 6,
    alignItems: 'center',
    paddingRight: 12,
    paddingBottom: 8,
  },
  trafficLight: {
    width: 11,
    height: 11,
    borderRadius: 999,
  },
  tab: {
    background: 'white',
    borderRadius: '8px 8px 0 0',
    padding: '8px 16px',
    fontSize: 12,
    color: '#222',
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    maxWidth: 220,
    whiteSpace: 'nowrap',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
  },
  urlBar: {
    height: 40,
    display: 'flex',
    alignItems: 'center',
    padding: '0 12px',
    gap: 8,
    background: 'white',
    borderBottom: '1px solid rgba(0,0,0,0.08)',
  },
  navButton: {
    width: 28,
    height: 28,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 6,
    color: '#555',
    fontSize: 14,
    cursor: 'pointer',
  },
  url: {
    flex: 1,
    background: '#f0f0f2',
    borderRadius: 6,
    padding: '6px 12px',
    fontSize: 13,
    color: '#333',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  },
  content: {
    overflow: 'auto',
  },
};

function BrowserWindow({ url = 'example.com', title = 'Untitled', children, width = 1280, height = 800, background }) {
  return (
    <div style={browserWindowStyles.outer}>
      <div style={{ ...browserWindowStyles.window, width }}>
        <div style={browserWindowStyles.chrome}>
          <div style={browserWindowStyles.tabBar}>
            <div style={browserWindowStyles.trafficLights}>
              <div style={{ ...browserWindowStyles.trafficLight, background: '#ff5f57' }} />
              <div style={{ ...browserWindowStyles.trafficLight, background: '#febc2e' }} />
              <div style={{ ...browserWindowStyles.trafficLight, background: '#28c840' }} />
            </div>
            <div style={browserWindowStyles.tab}>
              <span style={{ width: 12, height: 12, borderRadius: 2, background: '#ddd', flexShrink: 0 }} />
              <span>{title}</span>
            </div>
          </div>
          <div style={browserWindowStyles.urlBar}>
            <div style={browserWindowStyles.navButton}>←</div>
            <div style={browserWindowStyles.navButton}>→</div>
            <div style={browserWindowStyles.navButton}>↻</div>
            <div style={browserWindowStyles.url}>{url}</div>
          </div>
        </div>
        <div style={{ ...browserWindowStyles.content, height, background: background || 'white' }}>
          {children}
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { BrowserWindow });
