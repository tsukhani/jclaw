import type { SearchableOption } from '~/components/SearchableSelect.vue'

interface NamedModel {
  id: string
  name?: string
}

/**
 * A model as a picker option, labelled by its display name. The id rides along as the detail when the
 * name differs, because the id is what is stored and shown elsewhere, and the picker matches it too.
 */
export function modelOption(value: string, label: string, m: NamedModel): SearchableOption {
  return { value, label, detail: m.name && m.name !== m.id ? m.id : undefined }
}

/** Every model of each provider, valued `<provider>::<id>` and labelled `<provider> / <name>`. */
export function providerModelOptions(providers: Iterable<string>, modelsOf: (provider: string) => NamedModel[]): SearchableOption[] {
  const options: SearchableOption[] = []
  for (const provider of providers) {
    for (const m of modelsOf(provider)) {
      options.push(modelOption(`${provider}::${m.id}`, `${provider} / ${m.name || m.id}`, m))
    }
  }
  return options
}
