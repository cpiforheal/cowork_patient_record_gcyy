const shouldTracePerformance = import.meta.env.DEV;

export const traceAsync = async <T>(label: string, task: () => Promise<T>): Promise<T> => {
  if (!shouldTracePerformance || typeof performance === "undefined") return task();

  const startedAt = performance.now();

  try {
    return await task();
  } finally {
    const duration = performance.now() - startedAt;

    console.debug(`[perf] ${label}: ${duration.toFixed(1)}ms`);
  }
};
