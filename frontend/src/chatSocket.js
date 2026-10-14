const CLOSE_UNAUTHORIZED = 4401;
const MIN_RECONNECT_DELAY = 1000;
const MAX_RECONNECT_DELAY = 30000;
// Сервер шлёт ping раз в 20 секунд. Если от него ничего не приходило дольше этого времени,
// соединение считается мёртвым: при обрыве сети браузер может не замечать этого минутами.
const SERVER_SILENCE_TIMEOUT = 45000;
const HEARTBEAT_CHECK_INTERVAL = 5000;

/**
 * Обёртка над WebSocket с автоматическим переподключением и растущей задержкой между попытками.
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
    this.heartbeatTimer = null;
    this.lastMessageAt = 0;
  }

  connect() {
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const url = `${protocol}://${window.location.host}/ws?token=${encodeURIComponent(this.token)}`;
    this.ws = new WebSocket(url);

    this.ws.onopen = () => {
      this.reconnectDelay = MIN_RECONNECT_DELAY;
      this.lastMessageAt = Date.now();
      this.startHeartbeatCheck();
      this.onOpen?.();
    };

    this.ws.onmessage = (event) => {
      this.lastMessageAt = Date.now();
      try {
        const data = JSON.parse(event.data);
        if (data.type === 'ping') {
          this.send({ type: 'pong' });
          return;
        }
        this.onEvent?.(data);
      } catch (e) {
        console.error('Failed to handle WebSocket message', e);
      }
    };

    this.ws.onclose = (event) => {
      this.stopHeartbeatCheck();
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

  startHeartbeatCheck() {
    this.stopHeartbeatCheck();
    this.heartbeatTimer = setInterval(() => {
      if (Date.now() - this.lastMessageAt > SERVER_SILENCE_TIMEOUT) {
        this.dropDeadConnection();
      }
    }, HEARTBEAT_CHECK_INTERVAL);
  }

  stopHeartbeatCheck() {
    clearInterval(this.heartbeatTimer);
    this.heartbeatTimer = null;
  }

  // Сервер молчит: не ждём, пока браузер сам заметит обрыв, а сразу переподключаемся.
  // Обработчики старого соединения отключаем, чтобы его запоздалый onclose ничего не сломал.
  dropDeadConnection() {
    this.stopHeartbeatCheck();
    const ws = this.ws;
    ws.onopen = null;
    ws.onmessage = null;
    ws.onclose = null;
    ws.close();
    this.onClose?.();
    this.scheduleReconnect();
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
    this.stopHeartbeatCheck();
    clearTimeout(this.reconnectTimer);
    this.ws?.close();
  }
}
