import Database from '@tauri-apps/plugin-sql';

export interface SavedClassroom {
  id: string;
  name: string;
  section: string;
}

let connection: Promise<Database> | undefined;

export function openDatabase(): Promise<Database> {
  connection ??= Database.load('sqlite:lara_client.db').then(async (db) => {
    await db.execute('PRAGMA foreign_keys = ON');
    return db;
  }).catch((error: unknown) => {
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
  return db.select<SavedClassroom[]>(savedClassesQuery);
}
