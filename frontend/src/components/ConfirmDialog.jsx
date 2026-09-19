export default function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel = "Confirm",
  cancelLabel = "Cancel",
  onConfirm,
  onCancel,
  busy = false,
}) {
  if (!open) return null;

  return (
    <div className="dialog-overlay" onClick={onCancel}>
      <div className="dialog-box" role="dialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
        {title && <h3 className="dialog-box__title">{title}</h3>}
        <p className="dialog-box__message">{message}</p>
        <div className="dialog-box__actions">
          <button className="btn btn--ghost" onClick={onCancel} disabled={busy}>
            {cancelLabel}
          </button>
          <button className="btn btn--primary" onClick={onConfirm} disabled={busy}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
