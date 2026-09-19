export default function StatCard({ label, value, loading = false }) {
  return (
    <div className="stat-card">
      <div className="stat-card__value">{loading ? "—" : value}</div>
      <div className="stat-card__label">{label}</div>
    </div>
  );
}
