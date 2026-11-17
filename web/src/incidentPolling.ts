const delayAfter = [5000, 10000, 20000, 30000];

export function incidentPolling<T>(
  read: (signal: AbortSignal) => Promise<T>,
  received: (data: T) => void,
  failed: (error: unknown) => boolean,
) {
  let visible = false;
  let failures = 0;
  let pending: AbortController | null = null;
  let timer: ReturnType<typeof setTimeout> | undefined;

  function refresh() {
    clearTimeout(timer);
    if (!visible || pending) return;
    const request = new AbortController();
    pending = request;
    void (async () => {
      let continuePolling = true;
      try {
        const data = await read(AbortSignal.any([request.signal, AbortSignal.timeout(8000)]));
        if (pending !== request) return;
        failures = 0;
        received(data);
      } catch (error) {
        if (pending !== request) return;
        failures = Math.min(failures + 1, delayAfter.length - 1);
        continuePolling = failed(error);
      } finally {
        if (pending === request) {
          pending = null;
          if (visible && continuePolling) timer = setTimeout(refresh, delayAfter[failures]);
        }
      }
    })();
  }

  function setVisible(next: boolean) {
    if (next === visible) return;
    visible = next;
    if (visible) refresh();
    else {
      clearTimeout(timer);
      pending?.abort();
      pending = null;
    }
  }

  function invalidate() {
    pending?.abort();
    pending = null;
    refresh();
  }

  return { refresh, setVisible, invalidate };
}
