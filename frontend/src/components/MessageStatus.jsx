export default function MessageStatus({ message }) {
  if (message.failed) {
    return <span className="status failed" title={message.error}>!</span>;
  }
  if (message.pending) {
    return <span className="status pending" title="Отправляется">🕓</span>;
  }
  switch (message.status) {
    case 'READ':
      return <span className="status read" title="Прочитано">✓✓</span>;
    case 'DELIVERED':
      return <span className="status delivered" title="Доставлено">✓✓</span>;
    default:
      return <span className="status sent" title="Отправлено">✓</span>;
  }
}
