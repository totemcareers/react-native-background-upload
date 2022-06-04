import { AddListener, ChunkInfo, FileInfo, MultipartUploadOptions, UploadId, UploadOptions } from './types';
export declare const getFileInfo: (path: string) => Promise<FileInfo>;
export declare const startUpload: ({ path, ...options }: UploadOptions | MultipartUploadOptions) => Promise<UploadId>;
export declare const cancelUpload: (cancelUploadId: string) => Promise<boolean>;
export declare const addListener: AddListener;
export declare const chunkFile: (parentFilePath: string, chunkDirPath: string, numChunks: number) => Promise<ChunkInfo[]>;
declare const _default: {
    startUpload: ({ path, ...options }: UploadOptions | MultipartUploadOptions) => Promise<string>;
    cancelUpload: (cancelUploadId: string) => Promise<boolean>;
    addListener: AddListener;
    getFileInfo: (path: string) => Promise<FileInfo>;
};
export default _default;
