const BASE_URL = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

async function handle(res) {
  let body = null;
  try {
    body = await res.json();
  } catch {
    body = null;
  }
  if (!res.ok) {
    const error = new Error(body?.message || `Request failed with status ${res.status}`);
    error.status = res.status;
    error.body = body;
    throw error;
  }
  return body;
}

export async function uploadDocument({ file, documentType, sizeBytes }) {
  const form = new FormData();
  form.append("file", file);
  form.append("documentType", documentType);
  if (sizeBytes != null) {
    form.append("metadata", JSON.stringify({ sizeBytes }));
  }
  const res = await fetch(`${BASE_URL}/documents`, {
    method: "POST",
    body: form,
  });
  return handle(res);
}

export async function retryDocument(id) {
  const res = await fetch(`${BASE_URL}/documents/${encodeURIComponent(id)}/retry`, {
    method: "POST",
  });
  return handle(res);
}

export async function fetchDocument(id) {
  const res = await fetch(`${BASE_URL}/documents/${encodeURIComponent(id)}`);
  return handle(res);
}

export async function fetchHistory(id) {
  const res = await fetch(`${BASE_URL}/documents/${encodeURIComponent(id)}/history`);
  return handle(res);
}

export async function fetchDocuments({ status, documentType, uploadDate, page = 0, size = 20 } = {}) {
  const params = new URLSearchParams();
  if (status) params.set("status", status);
  if (documentType) params.set("documentType", documentType);
  if (uploadDate) params.set("uploadDate", uploadDate);
  params.set("page", String(page));
  params.set("size", String(size));

  const res = await fetch(`${BASE_URL}/documents?${params.toString()}`);
  return handle(res);
}
