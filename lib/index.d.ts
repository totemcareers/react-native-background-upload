import { AddListener, ChunkInfo, FileInfo, UploadId, UploadOptions } from './types';
export declare const getFileInfo: (path: string) => Promise<FileInfo>;
export declare const startUpload: ({ path, ...options }: UploadOptions) => Promise<UploadId>;
export declare const cancelUpload: (cancelUploadId: string) => Promise<boolean>;
export declare const addListener: AddListener;
export declare const chunkFile: (parentFilePath: string, chunkDirPath: string, numChunks: number) => Promise<ChunkInfo[]>;
export declare const ios: {
    getUploadStatus: (jobId: string) => Promise<{
        state: 'running' | 'suspended' | 'canceling' | undefined;
        bytesSent?: number;
        totalBytes?: number;
    }>;
};
declare const _default: {
    startUpload: ({ path, ...options }: UploadOptions) => Promise<string>;
    cancelUpload: (cancelUploadId: string) => Promise<boolean>;
    addListener: AddListener;
    getFileInfo: (path: string) => Promise<FileInfo>;
    chunkFile: (parentFilePath: string, chunkDirPath: string, numChunks: number) => Promise<ChunkInfo[]>;
    ios: {
        getUploadStatus: (jobId: string) => Promise<{
            state: "running" | "suspended" | "canceling";
            bytesSent?: number;
            totalBytes?: number;
        }>;
    };
};
export default _default;
