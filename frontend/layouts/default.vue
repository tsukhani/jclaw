<template>
  <div class="min-h-screen bg-gray-50">
    <header class="bg-white border-b border-gray-200">
      <div class="max-w-5xl mx-auto px-6 py-4 flex items-center justify-between">
        <h1 class="text-lg font-semibold text-gray-900">JClaw</h1>
        <nav class="flex gap-6 text-sm text-gray-500">
          <NuxtLink to="/" class="hover:text-gray-900">Home</NuxtLink>
        </nav>
      </div>
    </header>
    <main class="max-w-5xl mx-auto px-6 py-8">
      <slot />
    </main>
  </div>
</template>
