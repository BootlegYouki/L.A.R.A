import { useEffect, useState } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { listen, UnlistenFn } from '@tauri-apps/api/event';
import { convertFileSrc } from '@tauri-apps/api/core';
import { t } from '../i18n';
import VideoPlayer from '../components/VideoPlayer';
import type { HubInfo } from '../services/discovery';

interface Material {
  id: string;
  classroom_id: string;
  title: string;
  file_type: string;
  mime_type: string;
  file_size_bytes: number;
  download_url: string;
  local_file_path: string | null;
  local_video_url?: string | null;
  topic_id: string | null;
  assignment_id: string | null;
  announcement_id: string | null;
  created_at: number;
  updated_at: number;
}

interface Props {
  materialId: string;
  hub: HubInfo | null;
  userId: string;
  token: string;
  offline: boolean;
  onBack: () => void;
}

export default function VideoLessonPage({
  materialId,
  hub,
  userId,
  token,
  offline,
  onBack,
}: Props) {
  const [material, setMaterial] = useState<Material | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);
  const [downloadProgress, setDownloadProgress] = useState(0);
  const [hasPartialDownload, setHasPartialDownload] = useState(false);
  const [downloadComplete, setDownloadComplete] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  // Store the initial hub info to use even when disconnected
  const [initialHub] = useState(hub);

  // Listen for download progress events
  useEffect(() => {
    let unlisten: UnlistenFn | undefined;

    const setupListener = async () => {
      unlisten = await listen<{
        materialId: string;
        downloaded: number;
        total: number;
        percentage: number;
      }>('download-progress', (event) => {
        // Only update progress for this material
        if (event.payload.materialId === materialId) {
          setDownloadProgress(event.payload.percentage);
        }
      });
    };

    void setupListener();

    return () => {
      if (unlisten) {
        unlisten();
      }
    };
  }, [materialId]);

  // Determine video source: local file or remote stream  
  const videoSrc = material?.local_file_path
    ? material.local_video_url ?? convertFileSrc(material.local_file_path, 'asset')
    : material && initialHub && token
    ? `http://${initialHub.ip}:${initialHub.http_port}${material.download_url}?token=${token}`
    : '';
  
  useEffect(() => {
    let mounted = true;

    async function loadMaterial() {
      // Use initialHub (captured when component mounted) instead of current hub
      // This allows us to query the local database even when Hub is disconnected
      if (!initialHub) {
        setError(t.video_hub_required);
        setLoading(false);
        return;
      }

      try {
        setLoading(true);
        setError(null);

        const result = await invoke<Material | null>('get_material_by_id', {
          hub: initialHub,  // Use persisted hub info, not current connection state
          userId,
          materialId,
        });

        if (!mounted) return;

        if (!result) {
          setError(t.video_not_found);
          setMaterial(null);
        } else if (result.file_type !== 'VIDEO') {
          setError(t.video_wrong_type);
          setMaterial(null);
        } else {
          setMaterial(result);
          setError(null);
          
          // Check if there's a partial download (.part file)
          if (!result.local_file_path && initialHub) {
            try {
              const hasPartial = await invoke<boolean>('check_partial_download', {
                hub: initialHub,
                userId,
                materialId,
              });
              if (mounted) {
                setHasPartialDownload(hasPartial);
              }
            } catch (err) {
              console.error('Failed to check partial download:', err);
              // Non-fatal error, just assume no partial
              if (mounted) setHasPartialDownload(false);
            }
          }
        }
      } catch (err) {
        if (!mounted) return;
        console.error('Failed to load material:', err);
        setError(t.video_load_error);
        setMaterial(null);
      } finally {
        if (mounted) setLoading(false);
      }
    }

    void loadMaterial();

    return () => {
      mounted = false;
    };
  }, [materialId, initialHub, userId]);

  // Download handler - secure implementation in Rust
  const handleDownload = async () => {
    if (!material || !hub || downloading) return;
    
    setDownloading(true);
    setDownloadProgress(0);
    setDownloadError(null); // Clear any previous download errors

    try {
      console.log('Starting video download:', material.id);
      
      // Call secure Rust download command
      await invoke<string>('download_video_for_offline', {
        hub,
        userId,
        materialId: material.id,
        token, // Pass the token from props
      });

      
      // Ensure progress shows 100% at completion
      setDownloadProgress(100);
      
      // DON'T reload material here - it would change videoSrc and interrupt playback
      // The database is already updated with local_file_path by the Rust command
      // Next time the user opens this video, it will load from disk automatically
      
      // Just update our local state
      setHasPartialDownload(false);
      setDownloadComplete(true); // Show success banner
      
      // Reset UI after brief success display
      setTimeout(() => {
        setDownloading(false);
        setDownloadProgress(0);
      }, 2000);

    } catch (err) {
      console.error('Download failed:', err);
      
      // Set download-specific error (not the page error)
      const errorMsg = typeof err === 'string' ? err : t.video_download_error;
      
      // Check if it's a connection error (Hub disconnected)
      if (errorMsg.includes('Failed to read chunk') || 
          errorMsg.includes('error decoding') ||
          errorMsg.includes('connection') ||
          errorMsg.includes('stream')) {
        setDownloadError(t.video_download_interrupted);
        const partial = await invoke<boolean>('check_partial_download', { materialId: material.id }).catch(() => false);
        setHasPartialDownload(partial);
      } else {
        setDownloadError(errorMsg);
      }
      
      setDownloading(false);
      setDownloadProgress(0);
    }
  };

  // Loading skeleton
  if (loading) {
    return (
      <div className="video-lesson-page">
        <div className="video-header">
          <button
            type="button"
            className="icon-button back-button"
            onClick={onBack}
            aria-label={t.back}
          >
            <i className="ph ph-arrow-left" aria-hidden="true" />
          </button>
          <div className="skeleton video-title-skeleton" />
        </div>
        <div className="video-container">
          <div className="skeleton video-skeleton" />
        </div>
      </div>
    );
  }

  // Error state
  if (error || !material) {
    return (
      <div className="video-lesson-page">
        <div className="video-header">
          <button
            type="button"
            className="icon-button back-button"
            onClick={onBack}
            aria-label={t.back}
          >
            <i className="ph ph-arrow-left" aria-hidden="true" />
          </button>
          <h1>{t.video_error_title}</h1>
        </div>
        <section className="database-error" role="alert">
          <h2>
            <i className="ph ph-warning-circle" aria-hidden="true" />
            {t.video_error_title}
          </h2>
          <p>{error || t.video_load_error}</p>
          <button
            type="button"
            className="control tonal-button"
            onClick={onBack}
          >
            <i className="ph ph-arrow-left" aria-hidden="true" />
            {t.back_to_stream}
          </button>
        </section>
      </div>
    );
  }

  // Offline state with no local file
  if (offline && !material.local_file_path) {
    return (
      <div className="video-lesson-page">
        <div className="video-header">
          <button
            type="button"
            className="icon-button back-button"
            onClick={onBack}
            aria-label={t.back}
          >
            <i className="ph ph-arrow-left" aria-hidden="true" />
          </button>
          <h1>{material.title}</h1>
        </div>
        <section className="empty-state">
          <span className="empty-art">
            <i className="ph ph-wifi-slash" aria-hidden="true" />
          </span>
          <div>
            <h2>{t.video_offline_title}</h2>
            <p>{t.video_offline_body}</p>
            <button
              type="button"
              className="control tonal-button"
              onClick={onBack}
            >
              <i className="ph ph-arrow-left" aria-hidden="true" />
              {t.back_to_stream}
            </button>
          </div>
        </section>
      </div>
    );
  }

  // Main video player view
  return (
    <div className="video-lesson-page">
      <div className="video-header">
        <button
          type="button"
          className="icon-button back-button"
          onClick={onBack}
          aria-label={t.back}
        >
          <i className="ph ph-arrow-left" aria-hidden="true" />
        </button>
        <h1>{material.title}</h1>
      </div>

      {offline && !material.local_file_path && (
        <p role="status" className="hub-status-banner banner-amber">
          <i className="ph ph-wifi-slash" aria-hidden="true" />
          {t.video_offline_not_saved}
        </p>
      )}

      <div className="video-container">
        <VideoPlayer
          src={videoSrc}
          title={material.title}
        />
        
        {/* Download error banner - show when download fails */}
        {downloadError && (
          <div className="hub-status-banner banner-amber video-download-banner">
            <i className="ph ph-warning" aria-hidden="true" />
            {downloadError}
          </div>
        )}
        
        {/* Save for Home button - only show if not downloaded and online and download not complete */}
        {!material.local_file_path && !downloadComplete && hub && !offline && (
          <div className="video-actions">
            <button
              type="button"
              className="control tonal-button"
              onClick={handleDownload}
              disabled={downloading}
            >
              {downloading ? (
                <>
                  <i className="ph ph-circle-notch spin-animation" aria-hidden="true" />
                  {downloadProgress > 0 && downloadProgress < 100
                    ? `${t.video_downloading} ${downloadProgress}%`
                    : t.video_downloading}
                </>
              ) : hasPartialDownload ? (
                <>
                  <i className="ph ph-download-simple" aria-hidden="true" />
                  {t.video_resume_download}
                </>
              ) : (
                <>
                  <i className="ph ph-download-simple" aria-hidden="true" />
                  {t.video_save_for_home}
                </>
              )}
            </button>
          </div>
        )}
        
        {/* Downloaded indicator - show if already saved OR just completed download */}
        {(material.local_file_path || downloadComplete) && (
          <div className="hub-status-banner banner-info video-download-banner">
            <i className="ph ph-check-circle" aria-hidden="true" />
            {t.video_download_complete}
          </div>
        )}
      </div>
    </div>
  );
}
