import StatusBadge from "./StatusBadge";

function formatTimestamp(value) {
  if (!value) return "";
  return new Date(value).toLocaleString();
}

export default function HistoryTimeline({ entries }) {
  if (!entries || entries.length === 0) {
    return <p className="muted">No history yet.</p>;
  }

  return (
    <ol className="timeline">
      {entries.map((entry, i) => (
        <li key={`${entry.status}-${entry.timestamp}-${i}`} className="timeline__item">
          <span className="timeline__dot" />
          <div className="timeline__content">
            <div className="timeline__row">
              <StatusBadge status={entry.status} />
              <span className="timeline__timestamp">{formatTimestamp(entry.timestamp)}</span>
            </div>
            {entry.reason && <div className="timeline__reason">{entry.reason}</div>}
          </div>
        </li>
      ))}
    </ol>
  );
}
