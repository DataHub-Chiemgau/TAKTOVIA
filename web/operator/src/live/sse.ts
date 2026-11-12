/** Ein vollständiger Block eines Server-Sent-Events-Stroms (WP-M3-35). Kommentarzeilen (Herzschlag) werden als `heartbeat` gemeldet, nie als Ereignis. */
export interface SseFrame {
  event: string;
  id?: string;
  data?: string;
  heartbeat: boolean;
}

/**
 * Inkrementeller Parser: nimmt beliebig zerteilte Textstücke entgegen und liefert vollständige Blöcke (durch Leerzeile abgeschlossen). Unvollständige Reste bleiben für den nächsten
 * Aufruf stehen; ein Block bricht nie in der Mitte ab, auch wenn die Verbindung zwischen zwei Stücken endet (der Rest wird dann verworfen).
 */
export function createSseParser(): (chunk: string) => SseFrame[] {
  let buffer = '';
  return (chunk) => {
    buffer += chunk.replace(/\r\n/g, '\n');
    const out: SseFrame[] = [];
    let at = buffer.indexOf('\n\n');
    while (at >= 0) {
      const block = buffer.slice(0, at);
      buffer = buffer.slice(at + 2);
      at = buffer.indexOf('\n\n');
      if (!block.trim()) continue;
      const frame: SseFrame = { event: 'message', heartbeat: false };
      const data: string[] = [];
      let comment = false;
      for (const line of block.split('\n')) {
        if (line.startsWith(':')) comment = true;
        else if (line.startsWith('event:')) frame.event = line.slice(6).trim();
        else if (line.startsWith('id:')) frame.id = line.slice(3).trim();
        else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
      }
      if (data.length) frame.data = data.join('\n');
      if (comment && !data.length && frame.event === 'message') frame.heartbeat = true;
      out.push(frame);
    }
    return out;
  };
}
