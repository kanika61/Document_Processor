import { createContext, useCallback, useContext, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";

const ToastContext = createContext(null);

let idCounter = 0;

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);
  const timers = useRef(new Map());

  const dismiss = useCallback((id) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
    const timer = timers.current.get(id);
    if (timer) {
      clearTimeout(timer);
      timers.current.delete(id);
    }
  }, []);

  const push = useCallback(
    (variant, message, options = {}) => {
      const id = ++idCounter;
      setToasts((prev) => [...prev, { id, variant, message, action: options.action }]);
      const timer = setTimeout(() => dismiss(id), options.duration ?? 4000);
      timers.current.set(id, timer);
      return id;
    },
    [dismiss],
  );

  const api = useMemo(
    () => ({
      success: (message, options) => push("success", message, options),
      info: (message, options) => push("info", message, options),
      error: (message, options) => push("error", message, options),
      dismiss,
    }),
    [push, dismiss],
  );

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className="toast-stack" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className={`toast toast--${t.variant}`}>
            <span className="toast__message">{t.message}</span>
            {t.action && (
              <Link className="toast__action" to={t.action.to} onClick={() => dismiss(t.id)}>
                {t.action.label}
              </Link>
            )}
            <button className="toast__close" onClick={() => dismiss(t.id)} aria-label="Dismiss">
              ×
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast() {
  const ctx = useContext(ToastContext);
  if (!ctx) {
    throw new Error("useToast must be used within a ToastProvider");
  }
  return ctx;
}
