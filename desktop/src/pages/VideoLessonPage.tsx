import { useEffect, useState } from 'react';
import { invoke } from '@tauri-apps/api/core';
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

  // Store the initial hub info to use even when disconnected
  const [initialHub] = useState(hub);

  // Determine video source: local file or remote stream  
  const videoSrc = material?.local_file_path
    ? convertFileSrc(material.local_file_path, 'asset') // Use Tauri asset protocol
    : material && initialHub && token
    ? `http://${initialHub.ip}:${initialHub.http_port}${material.download_url}?token=${token}`
    : '';
  
  // Debug log
  useEffect(() => {
    if (material) {
      console.log('Material local_file_path:', material.local_file_path);
      console.log('Video source:', videoSrc);
      console.log('Is local file:', !!material.local_file_path);
    }
  }, [material, videoSrc]);

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
    setError(null);

    try {
      console.log('Starting video download:', material.id);
      
      // Call secure Rust download command
      const absolutePath = await invoke<string>('download_video_for_offline', {
        hub,
        userId,
        materialId: material.id,
        token, // Pass the token from props
      });

      console.log('Download complete, absolute path:', absolutePath);
      
      // Reload material from database to get the updated local_file_path
      const updatedMaterial = await invoke<Material | null>('get_material_by_id', {
        hub: initialHub,  // Use persisted hub info
        userId,
        materialId: material.id,
      });

      console.log('Reloaded material:', updatedMaterial);
      
      if (updatedMaterial) {
        setMaterial(updatedMaterial);
        console.log('New video source will be:', updatedMaterial.local_file_path ? 
          `file://${updatedMaterial.local_file_path}` : 'stream');
      }
      
      setDownloadProgress(100);
      
      // Reset UI after brief success display
      setTimeout(() => {
        setDownloading(false);
        setDownloadProgress(0);
      }, 2000);

    } catch (err) {
      console.error('Download failed:', err);
      setError(typeof err === 'string' ? err : t.video_download_error);
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
        
        {/* Save for Home button - only show if not downloaded and online */}
        {!material.local_file_path && hub && !offline && (
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
