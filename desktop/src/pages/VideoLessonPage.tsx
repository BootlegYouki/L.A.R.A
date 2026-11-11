import { useEffect, useState } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { t } from '../i18n';
import VideoPlayer from '../components/VideoPlayer';
// import { videoDownloadManager } from '../services/video_downloader'; // Phase 3: Full download implementation
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

  // Determine video source: local file or remote stream
  const videoSrc = material?.local_file_path
    ? `file://${material.local_file_path}`
    : material && hub && token
    ? `http://${hub.ip}:${hub.http_port}${material.download_url}?token=${token}`
    : '';

  useEffect(() => {
    let mounted = true;

    async function loadMaterial() {
      if (!hub) {
        setError(t.video_hub_required);
        setLoading(false);
        return;
      }

      try {
        setLoading(true);
        setError(null);

        const result = await invoke<Material | null>('get_material_by_id', {
          hub,
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
  }, [materialId, hub, userId]);

  // Download handler
  const handleDownload = async () => {
    if (!material || !hub || !token) return;
    
    setDownloading(true);
    setDownloadProgress(0);

    try {
      console.log('Starting download for:', material.id);
      
      // TODO: Full implementation with progress tracking
      // For now, show console log (Phase 3)
      console.log('Download would save to app data directory');
      console.log('Material:', {
        id: material.id,
        title: material.title,
        size: material.file_size_bytes,
      });
      console.log('Hub:', `${hub.ip}:${hub.http_port}`);
      console.log('Using video_downloader service (stubbed)');
      
      // Simulate progress for demonstration
      for (let i = 0; i <= 100; i += 10) {
        setDownloadProgress(i);
        await new Promise(resolve => setTimeout(resolve, 100));
      }
      
      // TODO: Actually call videoDownloadManager.downloadVideo()
      // await videoDownloadManager.downloadVideo(material, hub, token, (progress) => {
      //   setDownloadProgress(progress.percentage);
      // });
      
      console.log('Download complete (simulated)');
      
    } catch (err) {
      console.error('Download failed:', err);
      setError(t.video_download_error);
    } finally {
      setDownloading(false);
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

      {offline && (
        <p role="status" className="hub-status-banner banner-amber">
          <i className="ph ph-wifi-slash" aria-hidden="true" />
          {t.video_offline_not_saved}
        </p>
      )}

      <div className="video-container">
        <VideoPlayer
          src={videoSrc}
          title={material.title}
          isLocalFile={!!material.local_file_path}
        />
        
        {/* Save for Home button - only show if not downloaded and online */}
        {!material.local_file_path && hub && token && !offline && (
          <div className="video-actions" style={{ marginTop: '16px', display: 'flex', gap: '12px', justifyContent: 'center' }}>
            <button
              type="button"
              className="control tonal-button"
              onClick={handleDownload}
              disabled={downloading}
              style={{ minWidth: '200px' }}
            >
              {downloading ? (
                <>
                  <i className="ph ph-circle-notch" style={{ animation: 'spin 1s linear infinite' }} aria-hidden="true" />
                  {t.video_downloading} {downloadProgress}%
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
        
        {/* Downloaded indicator */}
        {material.local_file_path && (
          <div className="hub-status-banner banner-info" style={{ marginTop: '16px', textAlign: 'center' }}>
            <i className="ph ph-check-circle" aria-hidden="true" />
            {t.video_download_complete}
          </div>
        )}
      </div>
    </div>
  );
}
