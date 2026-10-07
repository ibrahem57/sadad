import { DurableObject } from 'cloudflare:workers';
import { handleAsNodeRequest } from 'cloudflare:node';
import core from './server.js';

const NODE_HTTP_PORT = 8081;
const nodeServer = core.createHttpServer();
nodeServer.listen(NODE_HTTP_PORT);

class DurableSqliteAdapter {
  constructor(storage) {
    this.storage = storage;
    this.sql = storage.sql;
  }

  exec(statement) {
    return this.sql.exec(statement);
  }

  prepare(statement) {
    const sql = this.sql;
    return {
      get(...values) {
        return sql.exec(statement, ...values).toArray()[0];
      },
      all(...values) {
        return sql.exec(statement, ...values).toArray();
      },
      run(...values) {
        const cursor = sql.exec(statement, ...values);
        cursor.toArray();
        const row = sql.exec('SELECT last_insert_rowid() AS id').toArray()[0];
        return { changes: cursor.rowsWritten, lastInsertRowid: Number(row?.id || 0) };
      },
    };
  }

  transactionSync(callback) {
    return this.storage.transactionSync(callback);
  }

  close() {}
}

export class SadadBackend extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.database = new DurableSqliteAdapter(ctx.storage);
    core.initializeDatabase(this.database, { durable: true });
    core.configureDatabase(this.database, env);
    core.seedInitialAdmin(this.database);
  }

  fetch(request) {
    core.configureDatabase(this.database, this.env);
    return handleAsNodeRequest(NODE_HTTP_PORT, request);
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname.startsWith('/api/')) {
      const id = env.SADAD_BACKEND.idFromName('sadad-primary');
      const headers = new Headers(request.headers);
      headers.delete('x-sadad-client-ip');
      const clientIp = request.headers.get('cf-connecting-ip');
      if (clientIp) headers.set('x-sadad-client-ip', clientIp);
      return env.SADAD_BACKEND.get(id).fetch(new Request(request, { headers }));
    }
    if (url.pathname === '/') {
      return env.ASSETS.fetch(new Request(new URL('/admin.html', request.url), request));
    }
    return env.ASSETS.fetch(request);
  },
};
