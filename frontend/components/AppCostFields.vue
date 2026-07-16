<script setup lang="ts">
// Cost/pricing fields for the Apps create/update forms. The `price` in app.json
// is a DISPLAY LABEL only (no payments/access control), so this assembles a
// human string — "Free", "$9/mo", "$99/yr", "$29" — and emits it via
// v-model:priceLabel. Emits `null` when the choice is incomplete (amount blank)
// or, in update mode, when "Keep current pricing" is selected — the parent then
// omits the price from its brief so nothing changes.
const props = defineProps<{
  /** Update mode: adds a "Keep current pricing" option (the default) that emits null. */
  allowUnchanged?: boolean
}>()

const emit = defineEmits<{ 'update:priceLabel': [string | null] }>()

// Curated common currencies (ISO 4217) with display symbols — enough for a
// pricing label without a data dependency. Alpha "symbols" (CHF, RM, …) render
// with a trailing space; glyph symbols ($, €, ₹) hug the amount.
const CURRENCIES: ReadonlyArray<{ code: string, symbol: string }> = [
  { code: 'USD', symbol: '$' }, { code: 'EUR', symbol: '€' }, { code: 'GBP', symbol: '£' },
  { code: 'JPY', symbol: '¥' }, { code: 'CNY', symbol: '¥' }, { code: 'INR', symbol: '₹' },
  { code: 'CAD', symbol: 'CA$' }, { code: 'AUD', symbol: 'A$' }, { code: 'NZD', symbol: 'NZ$' },
  { code: 'CHF', symbol: 'CHF' }, { code: 'SGD', symbol: 'S$' }, { code: 'HKD', symbol: 'HK$' },
  { code: 'MYR', symbol: 'RM' }, { code: 'IDR', symbol: 'Rp' }, { code: 'THB', symbol: '฿' },
  { code: 'PHP', symbol: '₱' }, { code: 'KRW', symbol: '₩' }, { code: 'AED', symbol: 'AED' },
  { code: 'SAR', symbol: 'SAR' }, { code: 'ZAR', symbol: 'R' }, { code: 'BRL', symbol: 'R$' },
  { code: 'MXN', symbol: 'MX$' }, { code: 'SEK', symbol: 'kr' }, { code: 'NOK', symbol: 'kr' },
  { code: 'DKK', symbol: 'kr' }, { code: 'PLN', symbol: 'zł' }, { code: 'TRY', symbol: '₺' },
  { code: 'ILS', symbol: '₪' }, { code: 'RUB', symbol: '₽' },
]

type CostType = 'keep' | 'free' | 'subscription' | 'fixed'

const costType = ref<CostType>(props.allowUnchanged ? 'keep' : 'free')
const currency = ref('USD')
// v-model on <input type="number"> yields a number (Vue auto-casts), or '' when
// cleared — normalize through String() before formatting.
const amount = ref<string | number>('')
const period = ref<'mo' | 'yr'>('mo')

// Unique ids so two instances (create + update forms) don't collide on for/id.
const uid = useId()
const id = (k: string) => `${uid}-${k}`

const symbolFor = (code: string) => CURRENCIES.find(c => c.code === code)?.symbol ?? code

const priced = computed(() => {
  const amt = String(amount.value ?? '').trim()
  if (!amt) return null
  const sym = symbolFor(currency.value)
  return /[A-Za-z]/.test(sym) ? `${sym} ${amt}` : `${sym}${amt}`
})

const priceLabel = computed<string | null>(() => {
  switch (costType.value) {
    case 'keep': return null
    case 'free': return 'Free'
    case 'subscription': return priced.value ? `${priced.value}/${period.value}` : null
    case 'fixed': return priced.value
    default: return null
  }
})

watch(priceLabel, v => emit('update:priceLabel', v), { immediate: true })
</script>

<template>
  <div class="space-y-2">
    <label
      :for="id('type')"
      class="block"
    >
      <span class="block text-xs text-fg-muted mb-1">Cost</span>
      <select
        :id="id('type')"
        v-model="costType"
        data-testid="cost-type"
        class="w-full px-2 py-1.5 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
      >
        <option
          v-if="allowUnchanged"
          value="keep"
        >
          — Keep current pricing —
        </option>
        <option value="free">
          Free
        </option>
        <option value="subscription">
          Subscription
        </option>
        <option value="fixed">
          Fixed price
        </option>
      </select>
    </label>

    <div
      v-if="costType === 'subscription' || costType === 'fixed'"
      class="flex items-end gap-2"
    >
      <label
        :for="id('currency')"
        class="block"
      >
        <span class="block text-xs text-fg-muted mb-1">Currency</span>
        <select
          :id="id('currency')"
          v-model="currency"
          data-testid="cost-currency"
          class="px-2 py-1.5 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
        >
          <option
            v-for="c in CURRENCIES"
            :key="c.code"
            :value="c.code"
          >
            {{ c.code }} ({{ c.symbol }})
          </option>
        </select>
      </label>
      <label
        :for="id('amount')"
        class="block flex-1"
      >
        <span class="block text-xs text-fg-muted mb-1">Amount</span>
        <input
          :id="id('amount')"
          v-model="amount"
          type="number"
          min="0"
          step="0.01"
          placeholder="9.00"
          data-testid="cost-amount"
          class="w-full px-2 py-1.5 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
        >
      </label>
      <label
        v-if="costType === 'subscription'"
        :for="id('period')"
        class="block"
      >
        <span class="block text-xs text-fg-muted mb-1">Billed</span>
        <select
          :id="id('period')"
          v-model="period"
          data-testid="cost-period"
          class="px-2 py-1.5 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
        >
          <option value="mo">
            Monthly
          </option>
          <option value="yr">
            Annually
          </option>
        </select>
      </label>
    </div>

    <p
      v-if="priceLabel"
      class="text-[11px] text-fg-muted"
    >
      Shown on the card as <span class="font-mono text-fg-primary">{{ priceLabel }}</span>.
    </p>
  </div>
</template>
