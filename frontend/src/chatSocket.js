const CLOSE_UNAUTHORIZED = 4401;
const MIN_RECONNECT_DELAY = 1000;
const MAX_RECONNECT_DELAY = 30000;

/**
 * WebSocket wrapper with automatic reconnect and exponential backoff.
 */
export class ChatSocket {
  constructor(token, { onEvent, onOpen, onClose, onUnauthorized }) {
    this.token = token;
    this.onEvent = onEvent;
    this.onOpen = onOpen;
    this.onClose = onClose;
    this.onUnauthorized = onUnauthorized;
    this.ws = null;
    this.closedByUser = false;
    this.reconnectDelay = MIN_RECONNECT_DELAY;
    this.reconnectTimer = null;
  }

  connect() {
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const url = `${protocol}://${window.location.host}/ws?token=${encodeURIComponent(this.token)}`;
    this.ws = new WebSocket(url);

    this.ws.onopen = () => {
      this.reconnectDelay = MIN_RECONNECT_DELAY;
      this.onOpen?.();
    };

    this.ws.onmessage = (event) => {
      try {
        this.onEvent?.(JSON.parse(event.data));
      } catch (e) {
        console.error('Failed to handle WebSocket message', e);
      }
    };

    this.ws.onclose = (event) => {
      this.onClose?.();
      if (this.closedByUser) {
        return;
      }
      if (event.code === CLOSE_UNAUTHORIZED) {
        this.onUnauthorized?.();
        return;
      }
      this.scheduleReconnect();
    };
  }

  scheduleReconnect() {
    clearTimeout(this.reconnectTimer);
    this.reconnectTimer = setTimeout(() => this.connect(), this.reconnectDelay);
    this.reconnectDelay = Math.min(this.reconnectDelay * 2, MAX_RECONNECT_DELAY);
  }

  send(payload) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(payload));
      return true;
    }
    return false;
  }

  close() {
    this.closedByUser = true;
    clearTimeout(this.reconnectTimer);
    this.ws?.close();
  }
}
