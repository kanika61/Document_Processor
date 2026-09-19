export const DOCUMENT_TYPES = [
  { value: "FINANCIAL_STATEMENT", label: "Financial Statement" },
];

export function documentTypeLabel(value) {
  return DOCUMENT_TYPES.find((t) => t.value === value)?.label ?? value;
}

export const STATUSES = ["UPLOADED", "PROCESSING", "PROCESSED", "FAILED"];

export const STATUS_LABELS = {
  UPLOADED: "Uploaded",
  PROCESSING: "Processing",
  PROCESSED: "Processed",
  FAILED: "Failed",
};

export const DEFAULT_PAGE_SIZE = 20;

/** Mirrors the backend rule: TIMEOUT/ERROR are transient (retryable); everything else isn't. */
export function isRetryableReason(failureReason) {
  if (!failureReason) return false;
  return failureReason.startsWith("TIMEOUT") || failureReason.startsWith("ERROR");
}

/** Statuses that mean processing is still in flight — worth polling. */
export function isInFlight(status) {
  return status === "UPLOADED" || status === "PROCESSING";
}
