import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { fetchDocument, fetchHistory, retryDocument } from "../api";
import { isInFlight, isRetryableReason } from "../constants";
import StatusBadge from "../components/StatusBadge";
import HistoryTimeline from "../components/HistoryTimeline";
import Spinner from "../components/Spinner";
import { useToast } from "../components/Toast";

const POLL_INTERVAL_MS = 3000;

function formatDate(value) {
  return value ? new Date(value).toLocaleString() : "—";
}

function FailureReason({ reason }) {
  if (reason.startsWith("VALIDATION_FAILED:")) {
    const detail = reason.slice("VALIDATION_FAILED:".length).trim();
    const items = detail
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean);
    return (
      <ul className="violation-list">
        {items.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
    );
  }
  return <p>{reason}</p>;
}

export default function DocumentDetailPage() {
  const { id } = useParams();
  const toast = useToast();

  const [doc, setDoc] = useState(null);
  const [history, setHistory] = useState([]);
  const [initialLoading, setInitialLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);
  const [loadError, setLoadError] = useState("");
  const [retrying, setRetrying] = useState(false);

  const load = useCallback(async () => {
    const [docData, historyData] = await Promise.all([fetchDocument(id), fetchHistory(id)]);
    setDoc(docData);
    setHistory(historyData);
    setNotFound(false);
    setLoadError("");
  }, [id]);

  // Initial load — the only time we show the full-page spinner.
  useEffect(() => {
    let cancelled = false;
    setInitialLoading(true);
    setNotFound(false);
    setLoadError("");

    load()
      .catch((err) => {
        if (cancelled) return;
        if (err.status === 404) setNotFound(true);
        else setLoadError(err.message);
      })
      .finally(() => {
        if (!cancelled) setInitialLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [id, load]);

  // Background polling while in flight — silent, no spinner, no error toasts.
  useEffect(() => {
    if (!doc || !isInFlight(doc.status)) return undefined;
    const timer = setInterval(() => {
      load().catch(() => {});
    }, POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [doc, load]);

  async function handleRetry() {
    setRetrying(true);
    try {
      await retryDocument(id);
      toast.success("Retry started");
      await load(); // reflect PROCESSING immediately instead of waiting for the next poll tick
    } catch (err) {
      if (err.status === 404) {
        toast.error("This document no longer exists.");
      } else {
        toast.error(err.body?.message || err.message);
      }
    } finally {
      setRetrying(false);
    }
  }

  if (initialLoading) {
    return (
      <div className="page center-pad">
        <Spinner />
      </div>
    );
  }

  if (notFound) {
    return (
      <div className="page">
        <p>No document found with id {id}.</p>
        <Link className="btn btn--ghost" to="/documents">
          Back to documents
        </Link>
      </div>
    );
  }

  if (loadError || !doc) {
    return (
      <div className="page">
        <p className="field__error">{loadError || "Something went wrong loading this document."}</p>
        <Link className="btn btn--ghost" to="/documents">
          Back to documents
        </Link>
      </div>
    );
  }

  const showRetry = doc.status === "FAILED" && isRetryableReason(doc.failureReason);

  return (
    <div className="page">
      <div className="detail-header">
        <h1>{doc.documentId}</h1>
        <StatusBadge status={doc.status} />
      </div>

      <div className="detail-grid">
        <div>
          <span className="detail-grid__label">Filename</span>
          <span>{doc.filename}</span>
        </div>
        <div>
          <span className="detail-grid__label">Document type</span>
          <span>{doc.documentType}</span>
        </div>
        <div>
          <span className="detail-grid__label">File size</span>
          <span>{doc.fileSizeBytes != null ? `${doc.fileSizeBytes} bytes` : "—"}</span>
        </div>
        <div>
          <span className="detail-grid__label">Created</span>
          <span>{formatDate(doc.createdAt)}</span>
        </div>
        <div>
          <span className="detail-grid__label">Updated</span>
          <span>{formatDate(doc.updatedAt)}</span>
        </div>
      </div>

      {doc.failureReason && (
        <section className="section">
          <h2>Failure reason</h2>
          <FailureReason reason={doc.failureReason} />
          {showRetry && (
            <button className="btn btn--primary" onClick={handleRetry} disabled={retrying}>
              {retrying ? <Spinner size={14} inline /> : "Retry"}
            </button>
          )}
        </section>
      )}

      {doc.extractedResult && (
        <section className="section">
          <h2>Extracted fields</h2>
          <table className="table table--compact">
            <tbody>
              {Object.entries(doc.extractedResult).map(([key, value]) => (
                <tr key={key}>
                  <th>{key}</th>
                  <td>{String(value)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}

      <section className="section">
        <h2>History</h2>
        <HistoryTimeline entries={history} />
      </section>
    </div>
  );
}
