/**
 * Handles HTTP background file uploads from an iOS or Android device.
 */
import { NativeModules, DeviceEventEmitter } from 'react-native';
import {
  AddListener,
  ChunkInfo,
  FileInfo,
  MultipartUploadOptions,
  UploadId,
  UploadOptions,
} from 'types';

const NativeModule =
  NativeModules.VydiaRNFileUploader || NativeModules.RNFileUploader; // iOS is VydiaRNFileUploader and Android is RNFileUploader
const eventPrefix = 'RNFileUploader-';

// for IOS, register event listeners or else they don't fire on DeviceEventEmitter
if (NativeModules.VydiaRNFileUploader) {
  NativeModule.addListener(eventPrefix + 'progress');
  NativeModule.addListener(eventPrefix + 'error');
  NativeModule.addListener(eventPrefix + 'cancelled');
  NativeModule.addListener(eventPrefix + 'completed');
}

/*
Gets file information for the path specified.
Example valid path is:
  Android: '/storage/extSdCard/DCIM/Camera/20161116_074726.mp4'
  iOS: 'file:///var/mobile/Containers/Data/Application/3C8A0EFB-A316-45C0-A30A-761BF8CCF2F8/tmp/trim.A5F76017-14E9-4890-907E-36A045AF9436.MOV;

Returns an object:
  If the file exists: {extension: "mp4", size: "3804316", exists: true, mimeType: "video/mp4", name: "20161116_074726.mp4"}
  If the file doesn't exist: {exists: false} and might possibly include name or extension

The promise should never be rejected.
*/
export const getFileInfo = (path: string): Promise<FileInfo> => {
  return NativeModule.getFileInfo(path).then((data: FileInfo) => {
    if (data.size) {
      // size comes back as a string on android so we convert it here.  if it's already a number this won't hurt anything
      data.size = +data.size;
    }
    return data;
  });
};

/*
Starts uploading a file to an HTTP endpoint.
Options object:
{
  url: string.  url to post to.
  path: string.  path to the file on the device
  headers: hash of name/value header pairs
  method: HTTP method to use.  Default is "POST"
  notification: hash for customizing tray notifiaction
    enabled: boolean to enable/disabled notifications, true by default.
}

Returns a promise with the string ID of the upload.  Will reject if there is a connection problem, the file doesn't exist, or there is some other problem.

It is recommended to add listeners in the .then of this promise.

*/
export const startUpload = ({
  path,
  ...options
}: UploadOptions | MultipartUploadOptions): Promise<UploadId> => {
  if (!path.match(/^file:\/\//)) {
    path = 'file://' + path;
  }

  return NativeModule.startUpload({ ...options, path });
};

/*
Cancels active upload by string ID of the upload.

Upload ID is returned in a promise after a call to startUpload method,
use it to cancel started upload.

Event "cancelled" will be fired when upload is cancelled.

Returns a promise with boolean true if operation was successfully completed.
Will reject if there was an internal error or ID format is invalid.

*/
export const cancelUpload = (cancelUploadId: string): Promise<boolean> => {
  if (typeof cancelUploadId !== 'string') {
    return Promise.reject(new Error('Upload ID must be a string'));
  }
  return NativeModule.cancelUpload(cancelUploadId);
};

/*
Listens for the given event on the given upload ID (resolved from startUpload).
If you don't supply a value for uploadId, the event will fire for all uploads.
Events (id is always the upload ID):
  progress - { id: string, progress: int (0-100) }
  error - { id: string, error: string }
  cancelled - { id: string, error: string }
  completed - { id: string }
*/
export const addListener: AddListener = (eventType, uploadId, listener) => {
  return DeviceEventEmitter.addListener(eventPrefix + eventType, (data) => {
    if (!uploadId || !data || !data.id || data.id === uploadId) {
      listener(data);
    }
  });
};

export const ios = {
  /*
  Splits a parent file into {numChunks} chunks and place them into the specified directory.
  Each chunk file will be named by its corresponding index (0, 1, 2,...).
  */
  chunkFile: (
    parentFilePath: string,
    chunkDirPath: string,
    numChunks: number,
  ): Promise<ChunkInfo[]> =>
    NativeModule.chunkFile(parentFilePath, chunkDirPath, numChunks),

  /*
  Directly check the state of a single upload task.
  Note that there's no way to distinguish between a task being completed, errored, or non-existent.
  They're all "inactive". You will need to check that with whichever API service you're using.
  */
  getUploadStatus: async (
    jobId: string,
  ): Promise<{
    state: 'running' | 'suspended' | 'canceling' | 'inactive';
    bytesSent?: number;
    totalBytes?: number;
  }> => (await NativeModule.getUploadStatus(jobId)) || { state: 'inactive' },
};

export default {
  startUpload,
  cancelUpload,
  addListener,
  getFileInfo,
  ios,
};
