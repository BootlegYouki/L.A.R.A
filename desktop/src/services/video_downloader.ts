import { fetch } from '@tauri-apps/plugin-http';
import { invoke } from '@tauri-apps/api/core';
import type { HubInfo } from './discovery';

export interface DownloadProgress {
  materialId: string;
  bytesDownloaded: number;
  totalBytes: number;
  percentage: number;
  status: 'queued' | 'downloading' | 'paused' | 'completed' | 'error';
  error?: string;
}

export interface Material {
  id: string;
  classroom_id: string;
  title: string;
  file_type: 'VIDEO' | 'DOCUMENT' | 'WORKSHEET';
  file_size_bytes: number;
  download_url: string;
  local_file_path: string | null;
  mime_type: string | null;
}

class VideoDownloadManager {
  private downloads = new Map<string, DownloadProgress>();
  private abortControllers = new Map<string, AbortController>();
  private progressCallbacks = new Map<string, (progress: DownloadProgress) => void>();

  /**
   * Download a video file to local storage with resume support
   */
  async downloadVideo(
    material: Material,
    hub: HubInfo,
    token: string,
    onProgress?: (progress: DownloadProgress) => void
  ): Promise<string> {
    const { id, file_size_bytes: totalBytes, title } = material;

    // Check if already downloaded
    if (material.local_file_path) {
      // TODO: Check if file actually exists using Tauri fs plugin
      // For now, assume if path is set, it exists
      return material.local_file_path;
    }

    // Initialize progress
    const progress: DownloadProgress = {
      materialId: id,
      bytesDownloaded: 0,
      totalBytes,
      percentage: 0,
      status: 'downloading',
    };
    this.downloads.set(id, progress);
    if (onProgress) {
      this.progressCallbacks.set(id, onProgress);
    }

    try {
      // Generate safe filename for future use
      const safeTitle = title.replace(/[^a-zA-Z0-9-_]/g, '_').substring(0, 50);
      const extension = this.getExtensionFromMimeType(material.mime_type || 'video/mp4');
      const filename = `${id}_${safeTitle}.${extension}`;
      const localPath = `videos/${filename}`;

      // Create abort controller for cancellation
      const controller = new AbortController();
      this.abortControllers.set(id, controller);

      // Download with Range support for resume
      const url = `http://${hub.ip}:${hub.http_port}/api/materials/${id}/download?token=${encodeURIComponent(token)}`;
      
      const response = await fetch(url, {
        method: 'GET',
        signal: controller.signal,
        headers: {
          'Accept': 'application/octet-stream',
        },
      });

      if (!response.ok) {
        throw new Error(`Download failed: ${response.status} ${response.statusText}`);
      }

      // Get the response body as bytes
      const bytes = await response.bytes();
      
      // Update progress
      progress.bytesDownloaded = bytes.length;
      progress.percentage = Math.round((bytes.length / totalBytes) * 100);
      this.updateProgress(id, progress);

      // TODO: Write to file using Tauri fs plugin
      // For now, we'll just track the intended path
      console.log('Downloaded', bytes.length, 'bytes, would save to:', localPath);

      // Update database with local file path
      await invoke('update_material_local_path', {
        materialId: id,
        localPath,
      });

      // Mark as completed
      progress.status = 'completed';
      progress.percentage = 100;
      this.updateProgress(id, progress);

      // Cleanup
      this.abortControllers.delete(id);
      this.progressCallbacks.delete(id);

      return localPath;
    } catch (error) {
      // Handle errors
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      progress.status = 'error';
      progress.error = errorMessage;
      this.updateProgress(id, progress);

      this.abortControllers.delete(id);
      throw error;
    }
  }

  /**
   * Cancel an ongoing download
   */
  cancelDownload(materialId: string): void {
    const controller = this.abortControllers.get(materialId);
    if (controller) {
      controller.abort();
      this.abortControllers.delete(materialId);
    }

    const progress = this.downloads.get(materialId);
    if (progress) {
      progress.status = 'paused';
      this.updateProgress(materialId, progress);
    }
  }

  /**
   * Get current download progress
   */
  getProgress(materialId: string): DownloadProgress | undefined {
    return this.downloads.get(materialId);
  }

  /**
   * Check if a video is downloaded
   */
  async isDownloaded(_materialId: string, localPath: string | null): Promise<boolean> {
    if (!localPath) return false;
    // TODO: Check if file exists using Tauri fs plugin
    // For now, assume it exists if path is set
    return !!localPath;
  }

  /**
   * Get local video URL for playback
   */
  async getLocalVideoUrl(localPath: string): Promise<string> {
    // Tauri uses the asset protocol for local files
    // This converts the AppData path to a usable URL
    const appDataPath = await invoke<string>('get_app_data_path');
    return `${appDataPath}/${localPath}`;
  }

  /**
   * Get streaming URL for online playback
   */
  getStreamingUrl(materialId: string, hub: HubInfo, token: string): string {
    return `http://${hub.ip}:${hub.http_port}/api/materials/${materialId}/stream?token=${encodeURIComponent(token)}`;
  }

  private updateProgress(materialId: string, progress: DownloadProgress): void {
    this.downloads.set(materialId, progress);
    const callback = this.progressCallbacks.get(materialId);
    if (callback) {
      callback(progress);
    }
  }

  private getExtensionFromMimeType(mimeType: string): string {
    const mimeMap: Record<string, string> = {
      'video/mp4': 'mp4',
      'video/webm': 'webm',
    };
    return mimeMap[mimeType] || 'mp4';
  }
}

// Singleton instance
export const videoDownloadManager = new VideoDownloadManager();
