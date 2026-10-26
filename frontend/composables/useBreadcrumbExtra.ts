/**
 * Shared ref pages can populate with a sub-crumb to append onto the layout's
 * route-derived breadcrumb trail. Names the entity a URL identifies, or a form
 * with no URL of its own (the agents page's create form). Set to the resource's
 * identity when the editor opens;
 * reset to {@code null} when it closes and on unmount so the crumb never leaks
 * across page navigations.
 */
export function useBreadcrumbExtra() {
  return useState<string | null>('breadcrumb-extra', () => null)
}
