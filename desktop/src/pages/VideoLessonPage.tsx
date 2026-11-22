import { useEffect, useState } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { listen, UnlistenFn } from '@tauri-apps/api/event';
import { convertFileSrc } from '@tauri-apps/api/core';
import {acquireMedia} from '../services/mediaCache';
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
  backLabel?: string;
}

export default function VideoLessonPage({
  materialId,
  hub,
  userId,
  token,
  offline,
  onBack,
  backLabel=t.back,
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
  const [savedMaterial, setSavedMaterial] = useState<Material | null>(null);
  const local = material?.local_file_path ? material : (savedMaterial?.id === materialId && savedMaterial?.local_file_path ? savedMaterial : null);
  const useLocal = !!(local?.local_file_path && (offline || material?.local_file_path));
  const [stream, setStream] = useState({url:'',failed:false});
  useEffect(() => {
    let active = true;
    setStream({url:'',failed:false});
    if (useLocal || !material || !initialHub || !token || offline) return;
    const lease = acquireMedia({hub:initialHub,user:{id:userId}},materialId,material.updated_at,material.mime_type || undefined);
    void lease.source.then(source => {if(active)setStream({url:source.url,failed:false});})
      .catch(() => {if(active)setStream({url:'',failed:true});});
    return () => {active=false;lease.release();};
  }, [initialHub,material,materialId,userId,token,offline,useLocal]);
  const videoSrc = useLocal && local?.local_file_path
    ? local.local_video_url ?? convertFileSrc(local.local_file_path, 'asset')
    : stream.url;
  
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
      
      const saved = await invoke<Material | null>('get_material_by_id', {
        hub, userId, materialId: material.id,
      });
      setSavedMaterial(saved);

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
            aria-label={backLabel}
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
            aria-label={backLabel}
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
            {backLabel}
          </button>
        </section>
      </div>
    );
  }

  // Offline state with no local file
  if (offline && !local) {
    return (
      <div className="video-lesson-page">
        <div className="video-header">
          <button
            type="button"
            className="icon-button back-button"
            onClick={onBack}
            aria-label={backLabel}
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
              {backLabel}
            </button>
          </div>
        </section>
      </div>
    );
  }

  // Main video player view
  return (
    <div className="video-lesson-page material-detail-page">
      <button type="button" className="control text-button material-back" onClick={onBack}><i className="ph ph-arrow-left" aria-hidden="true" />{backLabel}</button>
      <div className="material-detail-layout">
        <article className="material-detail-content"><header className="material-detail-heading"><span className="class-type-icon"><i className="ph ph-play-circle" aria-hidden="true" /></span><div><h1>{material.title}</h1><p>{t.class_video}</p></div></header>
          <div className="video-container">{videoSrc?<VideoPlayer src={videoSrc} title={material.title} />:<p role={stream.failed?'alert':'status'}>{stream.failed?t.video_error:t.loading}</p>}</div>
        </article>
        <aside className="material-detail-actions" aria-label={t.class_home_study}><h2>{t.class_home_study}</h2><p className="material-save-help">{t.class_save_help}</p>
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
        </aside>
      </div>
    </div>
  );
}
