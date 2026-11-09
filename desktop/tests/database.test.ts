import { afterEach, describe, expect, it, vi } from 'vitest';
import { DatabaseSync } from 'node:sqlite';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

vi.mock('@tauri-apps/plugin-sql', () => ({ default: { load: vi.fn() } }));
import { savedClassesQuery } from '../src/services/db';

const migration = readFileSync(new URL('../src-tauri/migrations/001_client_offline.sql', import.meta.url), 'utf8');
const upgrade = readFileSync(new URL('../src-tauri/migrations/002_sync_rejections.sql', import.meta.url), 'utf8');
const receiptMigration = readFileSync(new URL('../src-tauri/migrations/003_submission_received_at.sql', import.meta.url), 'utf8');
const contract = readFileSync(new URL('../../contracts/schema/client_offline.sql', import.meta.url), 'utf8');

describe('client database', () => {
  let db: DatabaseSync | undefined;
  let directory: string | undefined;
  afterEach(() => {
    db?.close();
    if (directory) {
      expect(directory.startsWith(join(tmpdir(), 'lara-desktop-'))).toBe(true);
      rmSync(directory, { recursive: true, force: true });
    }
    db = undefined;
    directory = undefined;
  });

  function open(path = ':memory:') {
    db = new DatabaseSync(path);
    db.exec('PRAGMA foreign_keys = ON');
    if (db.prepare('PRAGMA user_version').get()?.user_version === 0) {
      db.exec(migration);
      db.exec(upgrade);
      db.exec(receiptMigration);
      db.exec('PRAGMA user_version = 3');
    }
    return db;
  }

  it('reproduces the canonical client schema and keeps queued work after reopening', () => {
    const expected = new DatabaseSync(':memory:');
    expected.exec(contract);
    const actual = open();
    const tables = expected.prepare("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").all();
    // An additive SQLite migration appends a column regardless of its position in the contract.
    const columns = (database: DatabaseSync, table: string) => database.prepare(
      'SELECT name, type, "notnull", dflt_value, pk FROM pragma_table_info(?) ORDER BY name',
    ).all(table);
    for (const { name } of tables) {
      expect(columns(actual, String(name))).toEqual(columns(expected, String(name)));
      expect(actual.prepare(`PRAGMA foreign_key_list(${name})`).all()).toEqual(expected.prepare(`PRAGMA foreign_key_list(${name})`).all());
    }
    expected.close(); actual.close(); db = undefined;
    directory = mkdtempSync(join(tmpdir(), 'lara-desktop-'));
    const path = join(directory, 'lara_client.db');
    const first = open(path);
    first.exec(`
      INSERT INTO classrooms (id, name, section, class_code, teacher_id, created_at, updated_at)
      VALUES ('class-1', 'Science', 'A', 'K7M4QX', 'teacher-1', 1, 1);
      INSERT INTO announcements VALUES ('post-1', 'class-1', 'Lesson', 'Read', 1, 1, 1);
      INSERT INTO announcement_comments (id, announcement_id, author_id, content, created_at, updated_at, sync_status) VALUES ('comment-1', 'post-1', 'learner-1', 'Question', 1, 1, 'QUEUED_FOR_SYNC');
    `);
    first.close();
    db = undefined;
    const reopened = open(path);
    expect(reopened.prepare("SELECT sync_status FROM announcement_comments WHERE id = 'comment-1'").get())
      .toMatchObject({ sync_status: 'QUEUED_FOR_SYNC' });
    expect(reopened.prepare('PRAGMA foreign_keys').get()).toMatchObject({ foreign_keys: 1 });
    expect(() => reopened.exec("INSERT INTO enrollments VALUES ('bad', 'missing', 'learner-1', 'ACTIVE', 1, 1)")).toThrow();
  });

  it('does not show another account’s cached classes or pending/removed enrollments', () => {
    const local = open();
    for (const id of ['active', 'pending', 'removed', 'other']) {
      local.prepare('INSERT INTO classrooms VALUES (?, ?, ?, ?, ?, NULL, NULL, ?, ?)').run(id, id, 'A', id, 'teacher', 1, 1);
    }
    local.prepare("INSERT INTO enrollments VALUES ('e1', 'active', 'learner', 'ACTIVE', 1, 1)").run();
    local.prepare("INSERT INTO enrollments VALUES ('e2', 'pending', 'learner', 'PENDING', 1, 1)").run();
    local.prepare("INSERT INTO enrollments VALUES ('e3', 'removed', 'learner', 'REMOVED', 1, 1)").run();
    local.prepare("INSERT INTO enrollments VALUES ('e4', 'other', 'someone-else', 'ACTIVE', 1, 1)").run();
    expect(local.prepare(savedClassesQuery).all()).toEqual([]);
    local.exec("INSERT INTO sync_state VALUES ('current_user_id', 'learner', 1)");
    expect(local.prepare(savedClassesQuery).all().map((row) => row.id)).toEqual(['active']);
  });

  it('shows classes for their owning teacher and co-teacher', () => {
    const local = open();
    local.exec(`
      INSERT INTO classrooms VALUES ('owned', 'Science', 'A', 'ABC234', 'teacher', NULL, NULL, 1, 1);
      INSERT INTO classrooms VALUES ('shared', 'Math', 'A', 'DEF234', 'other', '["teacher"]', NULL, 1, 1);
      INSERT INTO classrooms VALUES ('unrelated', 'English', 'A', 'GHJ234', 'other', NULL, NULL, 1, 1);
      INSERT INTO sync_state VALUES ('current_user_id', 'teacher', 1);
    `);
    expect(local.prepare(savedClassesQuery).all().map((row) => row.id)).toEqual(['shared', 'owned']);
  });
});

it('upgrades queued rows without clearing them and stores contracted rejection reasons', () => {
  const local = new DatabaseSync(':memory:');
  try {
    local.exec('PRAGMA foreign_keys = ON');
    local.exec(migration);
    local.exec("INSERT INTO classrooms (id,name,section,class_code,teacher_id,created_at,updated_at) VALUES ('c','Class','A','ABC234','t',1,1)");
    local.exec("INSERT INTO announcements VALUES ('p','c','Title','Body',1,1,1)");
    local.exec("INSERT INTO announcement_comments VALUES ('q','p','u','Keep this',1,1,'QUEUED_FOR_SYNC')");
    local.exec("INSERT INTO assignments (id,classroom_id,title,due_date,created_at,updated_at) VALUES ('a','c','Work',2,1,1)");
    local.exec("INSERT INTO assignment_submissions (id,assignment_id,student_id,file_path,submitted_at,updated_at) VALUES ('s','a','u','photo.jpg',1,1)");
    local.exec("INSERT INTO quizzes (id,classroom_id,title,time_limit_minutes,created_at,updated_at) VALUES ('quiz','c','Quiz',10,1,1)");
    local.exec("INSERT INTO quiz_attempts (id,quiz_id,student_id,started_at,updated_at,answers_json) VALUES ('attempt','quiz','u',1,1,'[1]')");
    local.exec("INSERT INTO private_comments (id,assignment_id,student_id,author_id,content,created_at,updated_at,sync_status) VALUES ('private','a','u','u','Private queued',1,1,'QUEUED_FOR_SYNC')");
    local.exec('BEGIN');
    local.exec(upgrade);
    local.exec('COMMIT');
    local.exec(receiptMigration);
    expect(local.prepare("SELECT file_path,sync_status,received_at FROM assignment_submissions WHERE id='s'").get())
      .toMatchObject({file_path:'photo.jpg',sync_status:'QUEUED_FOR_SYNC',received_at:null});
    expect(local.prepare("SELECT answers_json,sync_status FROM quiz_attempts WHERE id='attempt'").get()).toMatchObject({answers_json:'[1]',sync_status:'QUEUED_FOR_SYNC'});
    expect(local.prepare("SELECT content,sync_status FROM private_comments WHERE id='private'").get()).toMatchObject({content:'Private queued',sync_status:'QUEUED_FOR_SYNC'});
    expect(local.prepare("SELECT content,sync_status,sync_reject_reason FROM announcement_comments WHERE id='q'").get())
      .toMatchObject({content:'Keep this',sync_status:'QUEUED_FOR_SYNC',sync_reject_reason:null});
    local.exec("UPDATE announcement_comments SET sync_status='REJECTED',sync_reject_reason='FORBIDDEN' WHERE id='q'");
    expect(local.prepare("SELECT sync_reject_reason FROM announcement_comments WHERE id='q'").get()).toMatchObject({sync_reject_reason:'FORBIDDEN'});
    for (const table of ['announcement_comments','assignment_submissions','quiz_attempts','private_comments']) {
      expect(local.prepare("SELECT sql FROM sqlite_master WHERE type='table' AND name=?").get(table)?.sql).toContain("'REJECTED'");
    }
    expect(local.prepare('PRAGMA foreign_key_check').all()).toEqual([]);
  } finally { local.close(); }
});
