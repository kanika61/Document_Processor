import { useState } from "react";
import { uploadDocument } from "../api";
import { DOCUMENT_TYPES, documentTypeLabel } from "../constants";
import { useToast } from "../components/Toast";
import ConfirmDialog from "../components/ConfirmDialog";
import Spinner from "../components/Spinner";

export default function UploadPage() {
  const toast = useToast();
  const [file, setFile] = useState(null);
  const [documentType, setDocumentType] = useState(DOCUMENT_TYPES[0].value);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [fileError, setFileError] = useState("");

  function handleFileChange(e) {
    const selected = e.target.files?.[0] ?? null;
    if (selected && !selected.name.toLowerCase().endsWith(".pdf")) {
      setFileError("Only .pdf files are supported.");
      setFile(null);
      return;
    }
    setFileError("");
    setFile(selected);
  }

  function handleUploadClick(e) {
    e.preventDefault();
    if (!file) {
      setFileError("Choose a PDF file to upload.");
      return;
    }
    setConfirmOpen(true);
  }

  async function handleConfirm() {
    setSubmitting(true);
    try {
      const result = await uploadDocument({ file, documentType, sizeBytes: file.size });
      toast.success(`Upload received — ${result.documentId}`, {
        action: { label: "View", to: `/documents/${result.documentId}` },
      });
      setFile(null);
      const input = document.getElementById("upload-file-input");
      if (input) input.value = "";
    } catch (err) {
      if (err.status === 409) {
        toast.info(err.body?.message || err.message);
      } else {
        toast.error(err.body?.message || err.message);
      }
    } finally {
      setSubmitting(false);
      setConfirmOpen(false);
    }
  }

  return (
    <div className="page">
      <h1>Upload a document</h1>
      <form className="upload-form" onSubmit={handleUploadClick}>
        <label className="field">
          <span className="field__label">File (.pdf only)</span>
          <input id="upload-file-input" type="file" accept=".pdf,application/pdf" onChange={handleFileChange} />
        </label>
        {fileError && <p className="field__error">{fileError}</p>}

        <label className="field">
          <span className="field__label">Document type</span>
          <select value={documentType} onChange={(e) => setDocumentType(e.target.value)}>
            {DOCUMENT_TYPES.map((t) => (
              <option key={t.value} value={t.value}>
                {t.label}
              </option>
            ))}
          </select>
        </label>

        <button className="btn btn--primary" type="submit" disabled={submitting}>
          {submitting ? <Spinner size={14} inline /> : "Upload"}
        </button>
      </form>

      <ConfirmDialog
        open={confirmOpen}
        title="Confirm upload"
        message={file ? `Upload "${file.name}" as ${documentTypeLabel(documentType)}?` : ""}
        confirmLabel={submitting ? "Uploading…" : "Confirm"}
        onConfirm={handleConfirm}
        onCancel={() => setConfirmOpen(false)}
        busy={submitting}
      />
    </div>
  );
}
