# Mobile Stream and grading verification

Android 9 x86_64 emulator against the repository mock Hub. The x86_64 APK used a temporary Gradle init script. The standard APK build used the usual ARM targets.

| Item | Evidence |
|---|---|
| 1. Stream comments | [Discussion](discussion.png) shows the posted comment, the avatar, and the spacing above the composer. |
| 2. Grading tabs | [To review](to-review.png) lists the submitted work. After entering and saving zero points, [Reviewed](reviewed.png) shows one reviewed submission and zero to review. |
| 3. Announcement attachments and ownership | [Posted](posted.png) shows the emulator-created teacher announcement and its uploaded file. Mock delta sync contained the post and linked material. The mock suite verifies learner posting and attachment ownership, denial for a classmate, and teacher deletion with tombstones. |
| 4. Homework attachment | [Submission](submission.png) shows the teacher's document attachment chip. The deliberately minimal synthetic PDF could not render a preview. |
| 5. Join request | `EnrollmentApiTest` reproduced the production receipt failure with `MissingFieldException` before the decoder change. The contract receipt, production receipt, and invalid-code tests passed after it. |
| 6. Announcement discussion | [Discussion with keyboard](discussion-keyboard.png) shows the input and Send button above the keyboard. The posted comment displayed its author and timestamp after reopening. |
| 7. Announcement composer | [Empty composer](composer.png) had Post disabled. [Selected file](composer-file.png) shows the attachment and its remove control. The draft text and file survived an app force-stop and relaunch. The retry test verified that a partial queue failure neither posted twice nor requeued a successful file. |
| 8. Shared chip | The composer, Stream, and submission screenshots show the same attachment presentation. |

Commands and results:

```text
gradlew.bat lintDebug testDebugUnitTest assembleDebug --console=plain
BUILD SUCCESSFUL in 7m 7s
58 actionable tasks: 21 executed, 37 up-to-date

python -m unittest discover tests
Ran 84 tests in 78.486s
OK

python scripts/verify_invariants.py
✅ All L.A.R.A offline invariants PASSED.
```

Still to verify on devices: learner attachment actions and homework view, app restart with a pending join request, an actual Hub disconnection during a draft or upload, and production Hub behavior. The production Hub needs a separate implementation of the announcement contract from PR #388.
