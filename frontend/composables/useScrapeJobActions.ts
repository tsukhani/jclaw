import type { ScrapeJob } from '~/types/api'
import { scrapeSite } from '~/utils/scrape-job'

/**
 * Pause, resume, stop and delete for a background scrape job (JCLAW-1273), shared by the Scrapes list
 * and a job's detail view. A refused action is kept in {@code actionError} for an ApiErrorAlert;
 * stopping and deleting are confirmed inside the page first.
 *
 * @param onChanged called with the job as the server returns it after an action, or null once it is deleted
 */
export function useScrapeJobActions(onChanged: (job: ScrapeJob | null, id: number) => void | Promise<void>) {
  const { saveError: actionError, attempt } = useSaveAttempt()
  const { confirm } = useConfirm()
  const busy = ref<number | null>(null)

  /** POST one of the job's actions; the server answers with the job as it now is. */
  async function post(job: ScrapeJob, action: 'pause' | 'resume' | 'cancel'): Promise<boolean> {
    busy.value = job.id
    let updated: ScrapeJob | null = null
    const ok = await attempt(async () => {
      updated = await $fetch<ScrapeJob>(`/api/scrape-jobs/${job.id}/${action}`, { method: 'POST' })
    })
    busy.value = null
    if (ok) await onChanged(updated, job.id)
    return ok
  }

  const pause = (job: ScrapeJob) => post(job, 'pause')

  const resume = (job: ScrapeJob) => post(job, 'resume')

  async function stop(job: ScrapeJob): Promise<boolean> {
    const confirmed = await confirm({
      title: 'Stop scrape',
      message: `Stop the scrape of ${scrapeSite(job.url)}? It keeps the ${job.pagesRead} pages it has read, but it cannot be resumed.`,
      confirmText: 'Stop',
      variant: 'danger',
    })
    return confirmed && post(job, 'cancel')
  }

  async function remove(job: ScrapeJob): Promise<boolean> {
    const confirmed = await confirm({
      title: 'Delete scrape',
      message: `Delete the scrape of ${scrapeSite(job.url)}, its ${job.pagesRead} pages and its workspace folder ${job.folder}? This cannot be undone.`,
      confirmText: 'Delete',
      variant: 'danger',
    })
    if (!confirmed) return false
    busy.value = job.id
    const ok = await attempt(() => $fetch(`/api/scrape-jobs/${job.id}`, { method: 'DELETE' }))
    busy.value = null
    if (ok) await onChanged(null, job.id)
    return ok
  }

  return { pause, resume, stop, remove, actionError, busy }
}
