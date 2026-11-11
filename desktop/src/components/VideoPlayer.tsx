import { useEffect, useRef, useState } from 'react';

interface VideoPlayerProps {
  src: string;
  title: string;
  isLocalFile?: boolean;
  onError?: (error: Error) => void;
}

export default function VideoPlayer({ src, title, isLocalFile = false, onError }: VideoPlayerProps) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const [isPlaying, setIsPlaying] = useState(false);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [volume, setVolume] = useState(1);
  const [isMuted, setIsMuted] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [bufferedRanges, setBufferedRanges] = useState<{ start: number; end: number }[]>([]);
  const [isSeeking, setIsSeeking] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  // Play/Pause toggle
  const togglePlay = () => {
    if (!videoRef.current) return;
    if (isPlaying) {
      videoRef.current.pause();
    } else {
      videoRef.current.play().catch(err => {
        console.error('Playback error:', err);
        onError?.(err);
      });
    }
  };

  // Seek to position (click or drag)
  const seekToPosition = (clientX: number, element: HTMLDivElement) => {
    if (!videoRef.current || !duration) return;
    const rect = element.getBoundingClientRect();
    const pos = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
    videoRef.current.currentTime = pos * duration;
  };

  // Mouse down - start seeking
  const handleMouseDown = (e: React.MouseEvent<HTMLDivElement>) => {
    e.preventDefault(); // Prevent text selection/drag ghosts
    setIsSeeking(true);
    seekToPosition(e.clientX, e.currentTarget);
  };

  // Mouse move while seeking
  useEffect(() => {
    if (!isSeeking) return;

    const handleMouseMove = (e: MouseEvent) => {
      const scrubBar = document.querySelector('.scrub-bar') as HTMLDivElement;
      if (scrubBar) {
        seekToPosition(e.clientX, scrubBar);
      }
    };

    const handleMouseUp = () => {
      setIsSeeking(false);
    };

    window.addEventListener('mousemove', handleMouseMove);
    window.addEventListener('mouseup', handleMouseUp);

    return () => {
      window.removeEventListener('mousemove', handleMouseMove);
      window.removeEventListener('mouseup', handleMouseUp);
    };
  }, [isSeeking, duration]);

  // Volume control
  const handleVolumeChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (!videoRef.current) return;
    const newVolume = parseFloat(e.target.value);
    videoRef.current.volume = newVolume;
    setVolume(newVolume);
    setIsMuted(newVolume === 0);
  };

  // Mute toggle
  const toggleMute = () => {
    if (!videoRef.current) return;
    videoRef.current.muted = !isMuted;
    setIsMuted(!isMuted);
  };

  // Fullscreen toggle
  const toggleFullscreen = () => {
    if (!containerRef.current) return;
    if (!isFullscreen) {
      if (containerRef.current.requestFullscreen) {
        containerRef.current.requestFullscreen();
      }
    } else {
      if (document.exitFullscreen) {
        document.exitFullscreen();
      }
    }
  };

  // Update buffered ranges
  const updateBuffered = () => {
    if (!videoRef.current) return;
    const buffered = videoRef.current.buffered;
    const ranges: { start: number; end: number }[] = [];
    for (let i = 0; i < buffered.length; i++) {
      ranges.push({
        start: buffered.start(i),
        end: buffered.end(i),
      });
    }
    setBufferedRanges(ranges);
  };

  // Video event handlers
  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;

    const handlePlay = () => setIsPlaying(true);
    const handlePause = () => setIsPlaying(false);
    const handleTimeUpdate = () => setCurrentTime(video.currentTime);
    const handleDurationChange = () => setDuration(video.duration);
    const handleVolumeUpdate = () => {
      setVolume(video.volume);
      setIsMuted(video.muted);
    };
    const handleError = () => {
      const error = new Error(video.error?.message || 'Video playback error');
      onError?.(error);
    };

    video.addEventListener('play', handlePlay);
    video.addEventListener('pause', handlePause);
    video.addEventListener('timeupdate', handleTimeUpdate);
    video.addEventListener('durationchange', handleDurationChange);
    video.addEventListener('volumechange', handleVolumeUpdate);
    video.addEventListener('progress', updateBuffered);
    video.addEventListener('error', handleError);

    return () => {
      video.removeEventListener('play', handlePlay);
      video.removeEventListener('pause', handlePause);
      video.removeEventListener('timeupdate', handleTimeUpdate);
      video.removeEventListener('durationchange', handleDurationChange);
      video.removeEventListener('volumechange', handleVolumeUpdate);
      video.removeEventListener('progress', updateBuffered);
      video.removeEventListener('error', handleError);
    };
  }, [onError]);

  // Fullscreen change handler
  useEffect(() => {
    const handleFullscreenChange = () => {
      setIsFullscreen(!!document.fullscreenElement);
    };

    document.addEventListener('fullscreenchange', handleFullscreenChange);
    return () => {
      document.removeEventListener('fullscreenchange', handleFullscreenChange);
    };
  }, []);

  // Format time helper
  const formatTime = (seconds: number) => {
    if (!isFinite(seconds)) return '0:00';
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  };

  return (
    <div ref={containerRef} className="video-player-container">
      <div className="video-wrapper">
        <video
          ref={videoRef}
          src={src}
          className="video-element"
          preload="metadata"
          aria-label={title}
        />
        
        {/* Custom Controls */}
        <div className="video-controls">
          {/* Progress bar */}
          <div 
            className="progress-container scrub-bar" 
            onMouseDown={handleMouseDown}
            style={{ cursor: isSeeking ? 'grabbing' : 'pointer' }}
          >
            {/* Buffered ranges */}
            <div className="progress-track">
              {bufferedRanges.map((range, i) => (
                <div
                  key={i}
                  className="buffered-range"
                  style={{
                    left: `${(range.start / duration) * 100}%`,
                    width: `${((range.end - range.start) / duration) * 100}%`,
                  }}
                />
              ))}
            </div>
            {/* Current progress */}
            <div
              className="progress-bar"
              style={{ width: `${(currentTime / duration) * 100}%` }}
            />
          </div>

          {/* Control buttons */}
          <div className="controls-row">
            <div className="controls-left">
              {/* Play/Pause */}
              <button
                type="button"
                onClick={togglePlay}
                className="control-button"
                aria-label={isPlaying ? 'Pause' : 'Play'}
              >
                <i className={`ph ${isPlaying ? 'ph-pause' : 'ph-play'}`} />
              </button>

              {/* Time display */}
              <span className="time-display">
                {formatTime(currentTime)} / {formatTime(duration)}
              </span>
            </div>

            <div className="controls-right">
              {/* Volume */}
              <button
                type="button"
                onClick={toggleMute}
                className="control-button"
                aria-label={isMuted ? 'Unmute' : 'Mute'}
              >
                <i className={`ph ${isMuted ? 'ph-speaker-slash' : 'ph-speaker-high'}`} />
              </button>
              <input
                type="range"
                min="0"
                max="1"
                step="0.1"
                value={isMuted ? 0 : volume}
                onChange={handleVolumeChange}
                className="volume-slider"
                aria-label="Volume"
              />

              {/* Fullscreen */}
              <button
                type="button"
                onClick={toggleFullscreen}
                className="control-button"
                aria-label={isFullscreen ? 'Exit fullscreen' : 'Fullscreen'}
              >
                <i className={`ph ${isFullscreen ? 'ph-arrows-in' : 'ph-arrows-out'}`} />
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Source indicator */}
      {isLocalFile && (
        <div className="video-source-badge">
          <i className="ph ph-download-simple" />
          <span>Saved on this device</span>
        </div>
      )}
    </div>
  );
}
