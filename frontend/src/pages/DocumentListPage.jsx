import { useCallback, useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { fetchDocuments } from "../api";
import { STATUSES, DOCUMENT_TYPES, DEFAULT_PAGE_SIZE, STATUS_LABELS } from "../constants";
import StatusBadge from "../components/StatusBadge";
import StatCard from "../components/StatCard";
import Spinner from "../components/Spinner";

const EMPTY_PAGE = { content: [], totalElements: 0, totalPages: 0, number: 0, first: true, last: true };

function formatDate(value) {
  if (!value) return "";
  return new Date(value).toLocaleString();
}

function formatBytes(bytes) {
  if (bytes == null) return "—";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export default function DocumentListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const status = searchParams.get("status") || "";
  const documentType = searchParams.get("documentType") || "";
  const uploadDate = searchParams.get("uploadDate") || "";
  const page = Number(searchParams.get("page") || 0);

  const hasFilters = Boolean(status || documentType || uploadDate);

  const [pageData, setPageData] = useState(EMPTY_PAGE);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const [stats, setStats] = useState({ total: null, inProgress: null, processed: null, failed: null });
  const [statsLoading, setStatsLoading] = useState(true);

  const loadList = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const data = await fetchDocuments({ status, documentType, uploadDate, page, size: DEFAULT_PAGE_SIZE });
      setPageData(data);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, [status, documentType, uploadDate, page]);

  const loadStats = useCallback(async () => {
    setStatsLoading(true);
    try {
      const [total, uploaded, processing, processed, failed] = await Promise.all([
        fetchDocuments({ page: 0, size: 1 }),
        fetchDocuments({ status: "UPLOADED", page: 0, size: 1 }),
        fetchDocuments({ status: "PROCESSING", page: 0, size: 1 }),
        fetchDocuments({ status: "PROCESSED", page: 0, size: 1 }),
        fetchDocuments({ status: "FAILED", page: 0, size: 1 }),
      ]);
      setStats({
        total: total.totalElements,
        inProgress: uploaded.totalElements + processing.totalElements,
        processed: processed.totalElements,
        failed: failed.totalElements,
      });
    } catch {
      // Stats are a bonus surface — fail silently, the list itself still works.
    } finally {
      setStatsLoading(false);
    }
  }, []);

  useEffect(() => {
    loadList();
    loadStats();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, documentType, uploadDate, page]);

  function updateFilter(key, value) {
    const next = new URLSearchParams(searchParams);
    if (value) next.set(key, value);
    else next.delete(key);
    next.set("page", "0");
    setSearchParams(next);
  }

  function goToPage(newPage) {
    const next = new URLSearchParams(searchParams);
    next.set("page", String(newPage));
    setSearchParams(next);
  }

  return (
    <div className="page">
      <h1>Documents</h1>

      <div className="stat-row">
        <StatCard label="Total" value={stats.total} loading={statsLoading} />
        <StatCard label="In progress" value={stats.inProgress} loading={statsLoading} />
        <StatCard label="Processed" value={stats.processed} loading={statsLoading} />
        <StatCard label="Failed" value={stats.failed} loading={statsLoading} />
      </div>

      <div className="filters">
        <label className="field field--inline">
          <span className="field__label">Status</span>
          <select value={status} onChange={(e) => updateFilter("status", e.target.value)}>
            <option value="">All</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {STATUS_LABELS[s]}
              </option>
            ))}
          </select>
        </label>

        <label className="field field--inline">
          <span className="field__label">Type</span>
          <select value={documentType} onChange={(e) => updateFilter("documentType", e.target.value)}>
            <option value="">All</option>
            {DOCUMENT_TYPES.map((t) => (
              <option key={t.value} value={t.value}>
                {t.label}
              </option>
            ))}
          </select>
        </label>

        <label className="field field--inline">
          <span className="field__label">Uploaded on</span>
          <input type="date" value={uploadDate} onChange={(e) => updateFilter("uploadDate", e.target.value)} />
        </label>

        {hasFilters && (
          <button className="btn btn--ghost" onClick={() => setSearchParams({})}>
            Clear filters
          </button>
        )}
      </div>

      {loading && (
        <div className="center-pad">
          <Spinner />
        </div>
      )}

      {!loading && error && <p className="field__error">{error}</p>}

      {!loading && !error && pageData.totalElements === 0 && (
        <div className="empty-state">
          {hasFilters ? (
            <p>No documents match these filters.</p>
          ) : (
            <>
              <p>No documents to display. Upload something to get started.</p>
              <Link className="btn btn--primary" to="/upload">
                Upload a document
              </Link>
            </>
          )}
        </div>
      )}

      {!loading && !error && pageData.totalElements > 0 && (
        <>
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>Document ID</th>
                  <th>Filename</th>
                  <th>Type</th>
                  <th>Status</th>
                  <th>Size</th>
                  <th>Uploaded</th>
                  <th>Failure reason</th>
                </tr>
              </thead>
              <tbody>
                {pageData.content.map((doc) => (
                  <tr key={doc.documentId}>
                    <td>
                      <Link to={`/documents/${doc.documentId}`}>{doc.documentId}</Link>
                    </td>
                    <td>{doc.filename}</td>
                    <td>{doc.documentType}</td>
                    <td>
                      <StatusBadge status={doc.status} />
                    </td>
                    <td>{formatBytes(doc.fileSizeBytes)}</td>
                    <td>{formatDate(doc.createdAt)}</td>
                    <td className="muted">{doc.failureReason || "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="pagination">
            <button
              className="btn btn--ghost"
              onClick={() => goToPage(page - 1)}
              disabled={pageData.first}
            >
              Previous
            </button>
            <span className="pagination__status">
              Page {pageData.number + 1} of {Math.max(pageData.totalPages, 1)}
            </span>
            <button
              className="btn btn--ghost"
              onClick={() => goToPage(page + 1)}
              disabled={pageData.last}
            >
              Next
            </button>
          </div>
        </>
      )}
    </div>
  );
}
