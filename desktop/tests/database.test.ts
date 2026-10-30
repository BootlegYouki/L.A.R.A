import { afterEach, describe, expect, it, vi } from 'vitest';
import { DatabaseSync } from 'node:sqlite';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

vi.mock('@tauri-apps/plugin-sql', () => ({ default: { load: vi.fn() } }));
import { savedClassesQuery } from '../src/services/db';

const migration = readFileSync(new URL('../src-tauri/migrations/001_client_offline.sql', import.meta.url), 'utf8');
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
    db.exec(migration);
    return db;
  }

  it('reproduces the canonical client schema and keeps queued work after reopening', () => {
    expect(migration.replace(/\r\n/g, '\n')).toBe(contract.replace(/\r\n/g, '\n'));
    directory = mkdtempSync(join(tmpdir(), 'lara-desktop-'));
    const path = join(directory, 'lara_client.db');
    const first = open(path);
    first.exec(`
      INSERT INTO classrooms (id, name, section, class_code, teacher_id, created_at, updated_at)
      VALUES ('class-1', 'Science', 'A', 'K7M4QX', 'teacher-1', 1, 1);
      INSERT INTO announcements VALUES ('post-1', 'class-1', 'Lesson', 'Read', 1, 1, 1);
      INSERT INTO announcement_comments VALUES ('comment-1', 'post-1', 'learner-1', 'Question', 1, 1, 'QUEUED_FOR_SYNC');
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
