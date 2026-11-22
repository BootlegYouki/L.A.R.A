# Desktop classroom parity on Android

Teachers can open Grades from a classroom’s menu. The read-only gradebook combines the saved active roster, synced assignment grades, and identity-bearing receipts for closed quizzes from Room. For older receipts without quiz and learner IDs, entering Grades while connected allows one full sync rescan per Hub. The existing assignment grading screens continue to publish grades.

Classwork keeps its topic groups and offers a topic filter for learners and teachers. Editing a topic can also change its numeric order. A new material can be classified as a worksheet while keeping the existing document and video paths. Stream cards resolve announcement authors from the class account’s cached users.

New teacher materials can be staged on device while the known Hub is unreachable. The worker checks the original Hub identity before sending a staged file. Classwork shows queued and active work, supports cancellation, and asks for confirmation before retrying when the request may already have reached the Hub. Cancel and Dismiss operate on the local upload job and never delete an accepted Hub material. Settings includes pending material jobs in the existing sign-out confirmation.

Teacher assignment review shows unread learner private comments. The class drawer and Classwork show counts, and opening a learner’s thread records the messages read on that device. Replies use the existing private-comment endpoint.

Announcements and assignment materials show inline images and user-initiated video playback. Images use a bounded preview and sampled decoding. The existing attachment card and full reader/player remain available. Teachers viewing a connected class receive unread-count updates via foreground delta pulls with backoff.
