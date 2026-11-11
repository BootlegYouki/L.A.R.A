# Testing Video Player (Sprint 3.1 Issue #30)

## Quick Answer to Your Questions

### Q1: Where is "Navigate to video material"?
**Answer:** The Stream page doesn't exist yet. I've added a **temporary test button** on the HomePage that will appear after you login. This button opens the VideoLessonPage with a test video.

### Q2: Do I need to upload a video?
**Answer:** **No!** The mock hub already includes a test video:
- **Material ID:** `a2000000-0000-4000-8000-000000000002`
- **Title:** "Video: Food Chain"
- **File:** `scripts/fixtures/lesson-video.mp4` (a real 30-second H.264 MP4 video)
- **Size:** ~123 KB

---

## Step-by-Step Testing Guide

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

### Step 2: Rebuild Desktop App (with test button)
```powershell
# Terminal 2: Build desktop with the new test button
cd desktop
npm run build
```

Expected: ✓ built in ~1-2s

### Step 3: Run Desktop App
```powershell
# Still in desktop/
npm run tauri dev
```

The desktop app window should open.

### Step 4: Login
**Use these mock hub accounts:**
- **Student:** LRN `123456789012`, PIN `1234`
- **Teacher:** ID `T-0001`, PIN `1234`

Either account works for testing the video player.

### Step 5: ⏰ IMPORTANT - Wait for Initial Sync

**After login, WAIT 5-10 seconds** for the app to sync materials from the Hub.

You'll see sync activity in the mock hub terminal:
```
[HTTP] 192.168.x.x - "POST /api/sync/pull HTTP/1.1" 200
```

The app automatically syncs:
- Classrooms
- Enrollments  
- Announcements
- **Materials (including the video!)** ← This is what we need
- Assignments

### Step 6: Find the Test Button
After sync completes, on the **Home page**, you'll see a **yellow/amber test section** at the top:

```
🧪 Test Video Player
This button will be removed when Stream page is built. 
It opens VideoLessonPage with a test video from the mock hub.

[📹 Open Test Video]  ← Click this button
```

### Step 7: Test Video Playback

When you click the button, you should see:

1. **Loading skeleton** (briefly)
2. **VideoLessonPage** with:
   - Back arrow button (← Back)
   - Video title: "Video: Food Chain"
   - HTML5 video player with custom controls
   - Play/pause button
   - Scrub bar with buffered ranges (semi-transparent green)
   - Volume slider
   - Fullscreen button

**What to test:**
- ✅ Click Play → video starts
- ✅ Click Pause → video stops
- ✅ Drag scrub bar → seeks to position
- ✅ Click different positions on scrub bar → jumps there
- ✅ Adjust volume → sound changes
- ✅ Click fullscreen → goes fullscreen (ESC to exit)
- ✅ Watch buffered ranges appear as you play (light green bars)

### Step 7: Test Offline State (Simulated)

The "Save for Home" download button is visible but **not fully functional yet** (it logs to console). The download implementation is a TODO for the next iteration.

**To test offline message:**
1. Stop the mock hub (Ctrl+C in Terminal 1)
2. Refresh the video page or click the test button again
3. You should see: "This video is not downloaded. Connect to the classroom Hub to watch this video..."

---

## What the Mock Hub Provides

The mock hub (`scripts/mock_hub.py`) **automatically seeds** this test data:

### Video Material
```python
{
  "id": "a2000000-0000-4000-8000-000000000002",
  "classroom_id": "c1a2b3c4-0001-4000-8000-000000000001",
  "title": "Video: Food Chain",
  "file_type": "VIDEO",
  "file_size_bytes": 123456,  # actual size of lesson-video.mp4
  "download_url": "/api/materials/a2000000-0000-4000-8000-000000000002/download",
  "uploaded_by": "T-0001",  # Teacher Maria Santos
}
```

### Video File
- **Location:** `scripts/fixtures/lesson-video.mp4`
- **Format:** Real H.264 MP4 video, 30 seconds
- **Purpose:** Clients can actually decode, seek, and play it (not just count bytes)

### Streaming Endpoints
Mock hub implements:
- `GET /api/materials/{id}/stream?token=XXX` - HTTP 206 Range streaming
- `GET /api/materials/{id}/download?token=XXX` - Full download

---

## Troubleshooting

### Video doesn't load
1. **Check console logs** in desktop app (DevTools: Ctrl+Shift+I)
2. **Check mock hub terminal** - should show HTTP requests
3. **Verify material ID** in console: should be `a2000000-0000-4000-8000-000000000002`

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
5. **Now try the test button** - video should load

**Why this happens:**
- VideoLessonPage calls `get_material_by_id` which reads from **local SQLite**
- The video material starts on the mock hub
- L.A.R.A is **offline-first** - it syncs Hub data into local SQLite
- If you click the test button before sync completes, the material won't exist locally yet

**Quick test:** If you see your class card "Science 4", sync is working. Wait a few more seconds.

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

---

## Removing the Test Button

When the **Stream page is built** (future sprint), remove these lines from `desktop/src/pages/HomePage.tsx`:

```typescript
// DELETE THIS ENTIRE SECTION:
{_onOpenVideo && (
  <section style={{ padding: '16px', ... }}>
    <h2>🧪 Test Video Player</h2>
    ...
    <button ... onClick={handleTestVideo}>
      Open Test Video
    </button>
  </section>
)}

// AND DELETE THIS FUNCTION:
function handleTestVideo() {
  if (_onOpenVideo) {
    _onOpenVideo('a2000000-0000-4000-8000-000000000002');
  }
}
```

Then the video player will be accessed normally through material cards on the Stream page.

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
Download initiated for video: a2000000-0000-4000-8000-000000000002
Stream URL: /api/materials/.../download
Hub: { ip: "192.168.X.X", http_port: 8080 }
File size: 123456 bytes
```

---

## Success Criteria

✅ Test button appears on HomePage after login  
✅ Clicking button opens VideoLessonPage  
✅ Video title "Video: Food Chain" displayed  
✅ Video player loads and shows first frame  
✅ Play button works  
✅ Scrub bar works (seek to different positions)  
✅ Volume control works  
✅ Fullscreen button works  
✅ Buffered ranges appear on scrub bar  
✅ Back button returns to HomePage  

---

## Screenshots for PR

Capture these screenshots for the PR:

1. **Test button on HomePage** (shows 🧪 section)
2. **Video player playing** (with buffered ranges visible)
3. **Video player controls** (close-up of scrub bar)
4. **Mock hub terminal** (showing HTTP 206 requests)
5. **Offline state** (Hub stopped, showing offline message)

---

Happy testing! 🎬
