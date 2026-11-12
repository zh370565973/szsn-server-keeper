import { onMounted, onUnmounted, ref, type Ref } from 'vue'

/** 让表格体填满容器，页头和分页留在容器内。 */
export function useTableBodyHeight(box: Ref<HTMLElement | undefined>, pager = true) {
  const height = ref(240)
  let observer: ResizeObserver | undefined

  function update() {
    const el = box.value
    if (!el) {
      return
    }
    const chrome = pager ? 100 : 40
    height.value = Math.max(96, Math.floor(el.clientHeight - chrome))
  }

  onMounted(() => {
    update()
    if (box.value) {
      observer = new ResizeObserver(update)
      observer.observe(box.value)
    }
    window.addEventListener('resize', update)
  })

  onUnmounted(() => {
    observer?.disconnect()
    window.removeEventListener('resize', update)
  })

  return height
}
