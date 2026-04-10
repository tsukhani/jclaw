<template>
  <NuxtLayout>
    <NuxtPage />
  </NuxtLayout>
  <ConfirmDialog />
</template>

<style>
/* Light mode: invert the dark UI with hue correction */
html.light-mode {
  filter: invert(1) hue-rotate(180deg);
}
/* Restore images/videos so they don't get inverted */
html.light-mode img,
html.light-mode video,
html.light-mode svg.no-invert {
  filter: invert(1) hue-rotate(180deg);
}
</style>
