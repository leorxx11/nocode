<template>
  <footer class="global-footer">
    <span>NoCode</span><span>让想法在对话中成形 · by Leo</span>
  </footer>
</template>
<style scoped>
.global-footer {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 24px 32px;
  border-top: 1px solid var(--app-border);
  color: var(--app-muted);
  font-size: 12px;
}
@media (max-width: 720px) {
  .global-footer {
    padding: 20px 16px;
  }
}
</style>
