import { NavLink, Navigate, Route, Routes } from "react-router-dom";
import { ToastProvider } from "./components/Toast";
import UploadPage from "./pages/UploadPage";
import DocumentListPage from "./pages/DocumentListPage";
import DocumentDetailPage from "./pages/DocumentDetailPage";

export default function App() {
  return (
    <ToastProvider>
      <div className="app-shell">
        <header className="app-nav">
          <span className="app-nav__brand">SuretySeven</span>
          <nav>
            <NavLink to="/documents" className={({ isActive }) => (isActive ? "active" : "")}>
              Documents
            </NavLink>
            <NavLink to="/upload" className={({ isActive }) => (isActive ? "active" : "")}>
              Upload
            </NavLink>
          </nav>
        </header>

        <main className="app-main">
          <Routes>
            <Route path="/" element={<Navigate to="/documents" replace />} />
            <Route path="/upload" element={<UploadPage />} />
            <Route path="/documents" element={<DocumentListPage />} />
            <Route path="/documents/:id" element={<DocumentDetailPage />} />
            <Route path="*" element={<Navigate to="/documents" replace />} />
          </Routes>
        </main>
      </div>
    </ToastProvider>
  );
}
