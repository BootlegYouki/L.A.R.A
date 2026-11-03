import Database from '@tauri-apps/plugin-sql';

export interface SavedClassroom {
  id: string;
  name: string;
  section: string;
}

let connection: Promise<Database> | undefined;

async function withDatabaseTimeout<T>(operation: Promise<T>): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      operation,
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new Error('Local database did not respond')), 15_000);
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}

export function openDatabase(): Promise<Database> {
  connection ??= withDatabaseTimeout(Database.load('sqlite:lara_client.db').then(async (db) => {
    await db.execute('PRAGMA foreign_keys = ON');
    return db;
  })).catch((error: unknown) => {
    connection = undefined;
    throw error;
  });
  return connection;
}

// A device may hold old accounts' rows. Only read classes belonging to its current user.
export const savedClassesQuery = `
  SELECT c.id, c.name, c.section FROM classrooms c
  JOIN sync_state s ON s.key = 'current_user_id'
  WHERE c.teacher_id = s.value
     OR EXISTS (SELECT 1 FROM json_each(c.co_teacher_ids_json) WHERE value = s.value)
     OR EXISTS (SELECT 1 FROM enrollments e
                WHERE e.classroom_id = c.id AND e.student_id = s.value AND e.status = 'ACTIVE')
  ORDER BY c.name, c.section, c.id
`;

export async function readSavedClasses(): Promise<SavedClassroom[]> {
  const db = await openDatabase();
  return withDatabaseTimeout(db.select<SavedClassroom[]>(savedClassesQuery));
}

export interface HubConnection {
  hub_address: string;
  connected_at: string;
  last_seen_at: string;
}

export async function saveHubConnection(address: string): Promise<void> {
  const db = await openDatabase();
  const now = new Date().toISOString();
  await withDatabaseTimeout(
    db.execute(
      `INSERT INTO hub_connections (hub_address, connected_at, last_seen_at)
       VALUES (?, ?, ?)
       ON CONFLICT(hub_address) DO UPDATE SET last_seen_at = excluded.last_seen_at`,
      [address, now, now]
    )
  );
}

export async function getLastHubConnection(): Promise<string | null> {
  const db = await openDatabase();
  const rows = await withDatabaseTimeout(
    db.select<{ hub_address: string }[]>(
      'SELECT hub_address FROM hub_connections ORDER BY last_seen_at DESC LIMIT 1'
    )
  );
  return rows.length > 0 ? rows[0].hub_address : null;
}

export async function updateHubConnectionSeen(address: string): Promise<void> {
  const db = await openDatabase();
  await withDatabaseTimeout(
    db.execute(
      'UPDATE hub_connections SET last_seen_at = ? WHERE hub_address = ?',
      [new Date().toISOString(), address]
    )
  );
}
