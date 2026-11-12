# Video Lesson Player

## Purpose

HTML5 video player with offline caching capabilities for L.A.R.A desktop app. Allows learners to stream video lessons from the Hub or play downloaded videos completely offline.

## Key Files

### Components
- **`src/components/VideoPlayer.tsx`** - HTML5 `<video>` with custom Material 3 controls (play/pause, scrub bar with buffered ranges, volume, fullscreen, download manager)

### Services
- **`src/services/video_downloader.ts`** - Download manager service that streams videos from Hub to local disk with progress tracking

### Backend (Rust)
- **`src-tauri/src/video.rs`** - Two Tauri commands:
  - `download_video_for_offline` - Securely downloads video from Hub to app data directory with token authentication
  - `get_material_by_id` - Fetches material metadata from local SQLite with path conversion

### Pages
- **`src/pages/VideoLessonPage.tsx`** - Integration layer that loads material metadata, handles offline states, and renders VideoPlayer

### Routing
- **`src/App.tsx`** - Video routing via `videoMaterialId` state and `videoToken` prop

## Architecture & Data Flow

```
┌─────────────────┐
│  HomePage       │  Future: Material cards with "Watch" button
│  (Stream/Class) │  Currently: Test access via materialId
└────────┬────────┘
         │
         ▼
┌─────────────────────────────────────────────────────┐
│  App.tsx                                            │
│  1. Sets videoMaterialId state                      │
│  2. Passes token from activeAccount                 │
│  3. Renders VideoLessonPage with token              │
└────────┬────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────┐
│  VideoLessonPage                                    │
│  1. Persists hub info on mount (initialHub)         │
│  2. Calls get_material_by_id(hub, userId, id)      │
│  3. Checks: material exists? is video? has access?  │
│  4. Shows loading/error/offline states              │
│  **Offline Fix:** Uses initialHub for queries even  │
│  when current hub becomes null (Hub disconnected)   │
└────────┬────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────┐
│  VideoPlayer Component                              │
│                                                     │
│  Offline Mode:                                      │
│    - local_file_path exists? → Play from disk      │
│      using Tauri asset protocol                    │
│    - No download? → Show "Save for Home" button    │
│                                                     │
│  Online Mode:                                       │
│    - Stream from Hub: /api/materials/{id}/download │
│    - Token passed as ?token= query param           │
│    - HTTP 206 (Range) requests for scrubbing       │
│                                                     │
│  Download Flow:                                     │
│    1. Click "Save for Home"                        │
│    2. Rust downloads file from Hub with token      │
│    3. Saves to: {appDataDir}/videos/{id}.{ext}     │
│    4. Updates materials.local_file_path in DB      │
│    5. Player switches to local file automatically  │
│    6. Shows "Video saved on this device" banner    │
└─────────────────────────────────────────────────────┘
```

## Database Schema

```sql
-- Already exists in migrations/001_client_offline.sql
CREATE TABLE materials (
  id TEXT PRIMARY KEY,
  classroom_id TEXT NOT NULL,
  title TEXT NOT NULL,
  file_type TEXT NOT NULL,           -- 'video', 'pdf', 'document'
  mime_type TEXT NOT NULL,           -- 'video/mp4', 'video/webm'
  file_size_bytes INTEGER NOT NULL,
  download_url TEXT NOT NULL,        -- /api/materials/{id}/download
  local_file_path TEXT,              -- NULL = not downloaded, path = cached
  topic_id TEXT,
  assignment_id TEXT,
  announcement_id TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE
);
```

## Hub API Contract

### Stream Endpoint (HTTP 206 for scrubbing)
```
GET /api/materials/{id}/download?token={bearer_token}
Authorization: None (token in query param because HTML5 <video> cannot set headers)
Response: 200 OK or 206 Partial Content
  - Supports Range header for seeking
  - Rate limited: 2.0 MB/s per stream
  - Note: Contract specifies /stream endpoint but mock hub uses /download
```

### Download Endpoint (resume support)
```
GET /api/materials/{id}/download?token={bearer_token}
Authorization: None (token in query param)
Response: 200 OK
  - Full file download
  - Supports Range header for resume
  - Rate limited: 2.0 MB/s
```

## Offline Behavior

### With Local File
- Plays entirely from disk using Tauri asset protocol (`convertFileSrc()`)
- No Hub connection required
- Scrubbing works instantly (no buffering)
- Shows info banner: "Video saved on this device"

### Without Local File (Hub unreachable)
- Shows empty state with wifi-slash icon
- Message: "This video is not downloaded"
- Body: "Connect to the classroom Hub to watch this video, or save it for home first."
- Back button returns to Stream/Home

### Offline Playback Bug Fix
**Problem:** After downloading a video, stopping the Hub and refreshing the page would show "This video is not saved on this device" error despite the file being downloaded.

**Root Cause:** The `loadMaterial()` useEffect checked `if (!hub)` and cleared material state when Hub disconnected, even though the material was in the local database.

**Solution:** Persist hub info on component mount using `const [initialHub] = useState(hub)` and use it for all database queries. The `hub` parameter in `get_material_by_id` is just a database context identifier, not a live connection requirement.

### Offline-First Pattern
- All video metadata stored in local SQLite
- Player checks `local_file_path` first before attempting stream
- Download progress persists across app restarts
- Failed downloads can be resumed

## Testing Notes

### Manual Test Plan

**1. Online Streaming**
```powershell
# Start mock hub
cd C:\Users\Jan Kiervy\Desktop\capstone\L.A.R.A
python scripts/mock_hub.py

# Navigate to video in app
# Test: Play, pause, scrub, volume, fullscreen
# Verify: Smooth playback, buffered ranges visible
```

**2. Download Flow**
```
# Click "Save for Home" button (below video player)
# Rust securely downloads file to app data directory
# File saved to {appDataDir}/videos/{materialId}.{ext}
# Database automatically updated with local_file_path
# Verify: Download completes successfully
# Verify: "Video saved on this device" banner appears
```

**3. Offline Playback (Critical Test)**
```
# After download completes
# Stop mock hub (Ctrl+C)
# **Refresh page or restart app** (this was the bug trigger)
# Expected: Video plays from local file via asset protocol
# Expected: Info banner shows "Video saved on this device"
# Expected: No network requests in console
# Expected: No "not saved" error message
```

**4. Edge Cases**
- Open video page with invalid materialId → Shows error
- Open non-video material → Shows "This material is not a video"
- Hub unreachable, no download → Shows offline empty state
- Download interrupted → Resume works on reconnect

### Build Commands

```powershell
# TypeScript build
cd desktop
npm run build

# Should output: ✓ built in ~X.XXs

# Rust check
cd src-tauri
cargo check

# Should output: Finished `dev` profile
```

### Acceptance Criteria Verification

- ✅ **Video scrubs smoothly** - HTML5 `<video>` with Range requests + drag-to-seek support
- ✅ **Downloaded videos play offline** - Secure Rust implementation with Range resume
- ✅ **English strings only** - All text in `i18n/en.json` (AGENTS.md line 61: apps are English only)
- ✅ **Design tokens only** - Uses CSS variables from `index.css`
- ✅ **40px buttons** - Desktop spec followed (56dp for quiz options only)
- ✅ **npm run build passes** - TypeScript strict mode, zero errors
- ✅ **cargo check passes** - All Rust commands compile cleanly
- ✅ **"Save for Home" button** - Fully functional with secure backend implementation

## Current Implementation Status

### ✅ Complete (Sprint 3.1, Issue #30)
- VideoPlayer component with custom controls (play/pause, scrub with drag-to-seek, volume, fullscreen)
- VideoLessonPage integration with offline states
- Token authentication passed from frontend to Rust
- Materials sync with proper UUID validation  
- HTTP 206 streaming from Hub with buffered ranges visualization
- **Secure download implementation in Rust**
  - Token-authenticated downloads to app data directory
  - Paths validated and restricted to app data directory
  - Database automatically updated with local_file_path
  - Handles re-downloads by deleting existing file first
- **Offline playback from local files**
  - Videos play via Tauri asset protocol when downloaded
  - **Bug fix:** Persists hub context to query local database when Hub disconnects
  - Info banner indicates "Video saved on this device"
- **UI cleanup**
  - Single clean banner for download status
  - No duplicate "saved" indicators
  - Clear offline states and empty states

### 🔧 Integration Notes
- Stream page not yet implemented - test access via material ID
- Video player ready for Stream integration when materials page is built
- All tech lead feedback items addressed

## Security

- **Download paths restricted to app data directory**
  - Frontend cannot specify arbitrary file paths
  - All downloads handled by Rust backend with token authentication
  - Paths validated before file operations
  - Videos stored: `$APPDATA/org.lara.desktop/videos/`
- **Re-download handling**
  - Existing files deleted before new download to avoid HTTP 416 Range errors
  - Fresh download ensures file integrity
- **Token authentication enforced**
  - Hub validates token on every request
  - Token passed via query parameter for HTML5 `<video>` compatibility
- **Tauri asset protocol**
  - Local files served through secure asset protocol
  - Scope restricted to app data directory: `{"allow": ["$APPDATA/**"]}`
  - CSP configured: `media-src asset: http://asset.localhost`
  - Prevents direct file:// access security issues

## Path Handling

1. **Database Storage (Relative Paths)**
   - Format: `videos/filename.mp4` (forward slashes, cross-platform)
   - Example: `videos/a2000000-0000-4000-8000-000000000002.mp4`
   - Portable across different user accounts and systems

2. **Runtime Conversion (Absolute Paths)**
   - `get_material_by_id` converts relative → absolute
   - Splits by forward slashes, rejoins with OS-specific separator
   - Example: `C:\Users\...\AppData\Roaming\org.lara.desktop\videos\a2000000-....mp4`
   - Verifies file exists before returning path

3. **Playback URL (Asset Protocol)**
   - Frontend calls: `convertFileSrc(absolutePath, 'asset')`
   - Result: `http://asset.localhost/C%3A%5CUsers%5C...`
   - Browser-compatible, secure, no direct file system access

4. **Account Context & Token Fetching**
   - All Rust commands require: `hub`, `userId`, account selection
   - Token passed from frontend (already available in App.tsx)
   - Follows same pattern as `sync.rs` commands
   - Uses `account::database()` for SQLite connection

5. **Mock Hub Material IDs (Contract Compliance)**
   - Uses `a1000000-...` and `a2000000-...` format (valid UUID hex digits)
   - Contract (`openapi.yaml` line 1553) specifies `id: format: uuid`
   - Real Hub will use `Uuid::new_v4()` for production materials

## Future Enhancements

- [ ] Batch download all videos for a class
- [ ] Disk space warnings before large downloads
- [ ] Download queue with priority
- [ ] Automatic cleanup of old/unused downloads
- [ ] Subtitle support (.vtt files)
- [ ] Playback speed controls (0.5x, 1x, 1.5x, 2x)
- [ ] Picture-in-picture mode
- [ ] Keyboard shortcuts (Space = play/pause, F = fullscreen, etc.)


---

## Testing Guide

### Prerequisites
```powershell
# Make sure you're in the repo root
cd C:\Users\Jan Kiervy\Desktop\capstone\L.A.R.A
```

### Step 1: Start the Mock Hub
```powershell
# Terminal 1: Start mock hub (leave this running)
python scripts/mock_hub.py
```

You should see:
```
[UDP] Discovery beacon broadcasting on port 8888
[HTTP] Mock Hub listening on port 8080
[WebSocket] Mock WebSocket broker listening on port 8081
```

### Step 2: Build Desktop App
```powershell
# Terminal 2: Build desktop
cd desktop
npm run build
```

Expected: ✓ built in ~1-2s

### Step 3: Run Desktop App
```powershell
# Still in desktop/
npm run tauri dev
```

### Step 4: Login
**Use these mock hub accounts:**
- **Student:** LRN `123456789012`, PIN `1234`
- **Teacher:** ID `T-0001`, PIN `1234`

### Step 5: ⏰ Wait for Initial Sync

**After login, WAIT 5-10 seconds** for the app to sync materials from the Hub.

You'll see sync activity in the mock hub terminal:
```
[HTTP] 192.168.x.x - "POST /api/sync/pull HTTP/1.1" 200
```

### Step 6: Navigate to Video Material

Navigate through the Stream or Classwork page to find a video material and open it.

**Test video in mock hub:**
- Material ID: `a2000000-0000-4000-8000-000000000002`
- Title: "Video: Food Chain"
- File: `scripts/fixtures/lesson-video.mp4` (30-second H.264 MP4)

### Step 7: Test Video Playback

**What to test:**
- ✅ Click Play → video starts
- ✅ Click Pause → video stops
- ✅ Drag scrub bar → seeks to position
- ✅ Click different positions on scrub bar → jumps there
- ✅ Adjust volume → sound changes
- ✅ Click fullscreen → goes fullscreen (ESC to exit)
- ✅ Watch buffered ranges appear (light green bars)

### Step 8: Test Download Feature

1. Click "Save for Home" button below video
2. Wait for download to complete
3. Verify download completes successfully
4. Video should now show "Downloaded" indicator

### Step 9: Test Offline Playback

1. Stop the mock hub (Ctrl+C in Terminal 1)
2. Refresh the video page or restart app
3. Video should play from local file
4. Verify amber banner shows "Playing from this device"
5. No network requests should appear in console

---

## Troubleshooting

### Video doesn't load / "Video not found"

**This is the most common issue!** The video material needs to sync from the Hub first.

**Solution:**
1. **Make sure mock hub is running** (`python scripts/mock_hub.py`)
2. **Wait 5-10 seconds after login** for initial sync
3. Watch the **mock hub terminal** - you should see:
   ```
   [HTTP] POST /api/sync/pull - materials syncing
   ```
4. **Check the app - class card should show** "Science 4 - Aguinaldo"
5. **Now try opening the video**

### Mock hub not responding
```powershell
# Kill any existing Python process
taskkill /F /IM python.exe

# Restart mock hub
python scripts/mock_hub.py
```

### Desktop app won't start
```powershell
# Rebuild
cd desktop
npm run build
npm run tauri dev
```

### Download fails
- Check Hub is running
- Check network connection
- Check console for error messages
- Try again (resume support handles partial downloads)

---

## Expected Console Logs

When testing, you should see in the console:

```
Loading material: a2000000-0000-4000-8000-000000000002
Video source: http://192.168.X.X:8080/api/materials/.../stream?token=...
Video loaded successfully
```

If download button is clicked:
```
Starting video download: a2000000-0000-4000-8000-000000000002
Download complete: videos/a2000000-....mp4
```

---

## Success Criteria

✅ Video player loads and shows first frame  
✅ Play button works  
✅ Scrub bar works (seek to different positions)  
✅ Drag-to-seek works smoothly  
✅ Volume control works  
✅ Fullscreen button works  
✅ Buffered ranges appear on scrub bar  
✅ "Save for Home" downloads video successfully  
✅ Downloaded video plays with Hub offline  
✅ **Downloaded video plays after page refresh with Hub offline** (bug fix)  
✅ Info banner shows "Video saved on this device"  
✅ Back button returns to previous page  
