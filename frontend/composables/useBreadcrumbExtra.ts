/**
 * Shared ref pages can populate with a sub-crumb to append onto the layout's
 * route-derived breadcrumb trail. Primarily for in-page editors that don't
 * change the URL (e.g. agents.vue opens an "Edit Agent" form on the same
 * {@code /agents} route). Set to the resource's identity when the editor opens;
 * reset to {@code null} when it closes and on unmount so the crumb never leaks
 * across page navigations.
 */
export function useBreadcrumbExtra() {
  return useState<string | null>('breadcrumb-extra', () => null)
}
