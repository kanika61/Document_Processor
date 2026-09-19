export default function Spinner({ size = 18, inline = false }) {
  const style = {
    width: size,
    height: size,
    borderWidth: Math.max(2, Math.round(size / 8)),
  };

  return (
    <span
      className={inline ? "spinner spinner--inline" : "spinner"}
      style={style}
      role="status"
      aria-label="Loading"
    />
  );
}
