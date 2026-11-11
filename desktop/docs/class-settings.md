# Desktop class settings

From a teaching class, the owner opens Class settings to change the name or section, or to confirm an archive. People lists the owner and co-teachers. Owners can add an active teacher by ID and remove a co-teacher. Co-teachers can read the teacher list. The ID entered in the add form is sent to the Hub and stays out of the local database.

Changes require the connected Hub and use the existing bearer-authenticated classroom routes. After a successful response, Desktop refreshes its classroom snapshot and requests a sync. Public teacher names come from that account’s SQLite mirror. If the mirror has yet to receive a name, People shows the corresponding role. The Hub enforces ownership.

Archived classes move out of the active grid and drawer list into Archived classes on Home. Authorized teachers and active learners can reopen the cached class after disconnecting. The class remains read-only, with the join code and class management actions hidden. Pending learners do not gain archive access.

## How to test

1. Sign in as a teacher who owns a test class. Open the class and use the three-dot Class settings button to edit its name and section. Go back to Home and check the updated class.
2. In People, add a second active teacher such as `T-0002`. Switch accounts and confirm the class appears and the Teachers list is visible. Only the owner can open Class settings or manage co-teachers. Switch back and remove the second teacher.
3. Archive the class from Class settings. Open Archived classes on Home and check that the class is read-only. Stop the test Hub and fully reopen Desktop. Check that the archived class still opens and that a separate active class disables changes while offline.
4. Enter the Hub address manually. In Settings, check that the address appears once and that a matching discovered name appears when available.

## Verification

The Windows production build exercised the owner and co-teacher flows against the mock and the real staging Hub at `544557e`, with synthetic teachers including `T-0002`. For each Hub, the archived class reopened after a full Desktop restart with the Hub stopped, and the active class blocked writes. The Settings connection panel showed the manual address once.

`npm run build`, `npm run lint`, the Windows Tauri build, 25 active native tests, 68 shared tests and `verify_invariants.py` passed. The frontend suite passed 95 of 96 tests. The unchanged staging database test expects the new `assignment_submissions.received_at` contract column, which Desktop migrations do not yet create. The local Hub tests used loopback. Physical WAN disconnection is still pending.

## Windows screenshots for PR #283

Captured from the Windows production build at 64ebbbe against a mock Hub with synthetic classes and accounts. The archived class reopened read-only after a full restart with the mock Hub stopped. The active class disabled writes while disconnected.

- [Class settings](screenshots/class-settings-123/rename.png) and [archive confirmation](screenshots/class-settings-123/archive-confirmation.png)
- [Owner People](screenshots/class-settings-123/owner-people.png), [add co-teacher](screenshots/class-settings-123/add-co-teacher.png), [co-teacher People](screenshots/class-settings-123/co-teacher-people.png), and [remove co-teacher](screenshots/class-settings-123/remove-co-teacher.png)
- [Empty archive](screenshots/class-settings-123/home-empty-archive.png), [populated archive](screenshots/class-settings-123/home-archived.png), and [read-only archive](screenshots/class-settings-123/archive-read-only.png)
- [Offline archive](screenshots/class-settings-123/archive-offline.png) and [offline settings](screenshots/class-settings-123/settings-offline.png)
- [Hub Settings](screenshots/class-settings-123/hub-settings.png)

Desktop CI checks lint and build. The local schema parity test is tracked in #275. The Windows screenshot run used loopback. Physical WAN disconnection is pending.
