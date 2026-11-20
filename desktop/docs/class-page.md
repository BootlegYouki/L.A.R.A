# Class Stream and Classwork

> Current verification uses the [real-Hub workflow](real-hub-testing.md). Historical simulator observations and screenshots below are archived evidence only; they do not establish real-Hub behavior. Old simulator commands, seed credentials and limitations are not current test instructions.

Class cards now open Stream and Classwork. Teacher People stays available as a third tab. Video rows open the existing video lesson page. The developer video shortcut is removed from Home.

The read_class_content native command verifies the selected account and class membership before reading account-scoped announcements, materials and ordered text chunks. Learners require ACTIVE enrollment. Stream sorts newest first. Save for Home marks an available cached handout revision in account-local sync state. The text is already mirrored by delta sync. Saved video badges check the local file. All text remains in English resources.

The announcement contract has no author field. Stream currently displays the title, date and content. Author display needs the lead's decision.

Windows verification is recorded below.

Archived Windows mock-Hub walkthrough passed for teacher Stream, Classwork, handout text and Save for Home. With the Hub stopped, reloading the app reopened the saved handout from Classwork. Frontend build and lint, 97 frontend tests and the native compile check passed. Screenshots are in desktop/docs/screenshots/55-*.png. The real-Hub learner and full-restart checks are recorded below. Linux verification remains pending.

## Real Hub Windows verification

The native Windows app connected to a real staging Hub built from the current server source. Synthetic learner sign-in, announcement display, handout text, Save for Home and actual video navigation/download passed. After stopping the Hub and fully restarting Desktop, the cached class and tabs opened, both lessons showed saved badges, the handout text opened and the saved video played past two seconds.

Native tests passed 25 with three existing opt-in tests skipped. Their first sandboxed run failed to open temporary account databases. The rerun with a writable project TEMP passed. Frontend tests passed 97.

[Stream](screenshots/55-real-stream.png), [handout](screenshots/55-real-handout.png), [video](screenshots/55-real-video.png), [offline Classwork](screenshots/55-real-offline-classwork.png), [offline handout](screenshots/55-real-offline-handout.png), [offline video](screenshots/55-real-offline-video.png).

Linux walkthrough, empty/error screenshots and physical LAN/WAN interruption remain pending. Announcements have no author in the current contract, so no author is inferred.
