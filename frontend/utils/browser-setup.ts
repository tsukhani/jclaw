// How Settings > Browser describes the browser tool's driver and Chromium. Dependency-free, so the
// e2e spec imports the same labels the panel renders and cannot drift from them.

export type DriverSource = 'preinstalled' | 'bundled' | 'downloaded' | 'missing' | 'unsupported'

export const DRIVER_LABELS: Record<DriverSource, string> = {
  bundled: 'Included with this install',
  preinstalled: 'Provided by the environment',
  downloaded: 'Downloaded',
  missing: 'Not downloaded yet',
  unsupported: 'Not available on this platform',
}

export function chromiumLabel(installed: boolean): string {
  return installed ? 'Installed' : 'Not downloaded yet'
}

/** Whether Download now has anything to fetch. An unsupported platform has no driver to fetch. */
export function browserSetupNeeded(s: { driverSource: DriverSource, chromiumInstalled: boolean }): boolean {
  if (s.driverSource === 'unsupported') return false
  return s.driverSource === 'missing' || !s.chromiumInstalled
}
