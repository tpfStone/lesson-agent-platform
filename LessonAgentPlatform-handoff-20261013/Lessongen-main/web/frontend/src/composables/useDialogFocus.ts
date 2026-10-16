import { nextTick, onUnmounted, watch, type Ref } from "vue";

const dialogs: HTMLElement[] = [];
const focusable =
  'button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), summary, [tabindex]:not([tabindex="-1"])';

/** Keep keyboard interaction in the topmost open dialog and restore its opener. */
export function useDialogFocus(
  open: Ref<boolean>,
  element: Ref<HTMLElement | null>,
  close: () => void,
) {
  let opener: HTMLElement | null = null;
  let active: HTMLElement | null = null;
  let generation = 0;
  function controls() {
    return Array.from(
      active?.querySelectorAll<HTMLElement>(focusable) || [],
    ).filter(
      (el) =>
        el.tabIndex >= 0 &&
        el.getClientRects().length > 0 &&
        !el.closest("[hidden], [inert]"),
    );
  }
  function onKey(event: KeyboardEvent) {
    if (!active || dialogs.at(-1) !== active) return;
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      close();
    } else if (event.key === "Tab") {
      const items = controls();
      const first = items[0],
        last = items.at(-1);
      if (!first) {
        event.preventDefault();
        active.focus();
      } else if (
        event.shiftKey &&
        (document.activeElement === first ||
          !active.contains(document.activeElement))
      ) {
        event.preventDefault();
        last?.focus();
      } else if (
        !event.shiftKey &&
        (document.activeElement === last ||
          !active.contains(document.activeElement))
      ) {
        event.preventDefault();
        first.focus();
      }
    }
  }
  function onFocus(event: FocusEvent) {
    if (
      active &&
      dialogs.at(-1) === active &&
      !active.contains(event.target as Node)
    )
      (controls()[0] || active).focus();
  }
  function release() {
    const index = active ? dialogs.indexOf(active) : -1;
    if (index >= 0) dialogs.splice(index, 1);
    active = null;
    document.removeEventListener("keydown", onKey, true);
    document.removeEventListener("focusin", onFocus);
    if (opener?.isConnected) opener.focus();
    opener = null;
  }
  watch(
    open,
    async (value) => {
      const request = ++generation;
      if (!value) {
        release();
        return;
      }
      opener =
        document.activeElement instanceof HTMLElement
          ? document.activeElement
          : null;
      await nextTick();
      if (request !== generation || !open.value || !element.value) return;
      active = element.value;
      dialogs.push(active);
      document.addEventListener("keydown", onKey, true);
      document.addEventListener("focusin", onFocus);
      (controls()[0] || active).focus();
    },
    { immediate: true },
  );
  onUnmounted(() => {
    generation++;
    release();
  });
}
